package com.mediashots.defkonadsbbridge;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

final class RtlTcpAdsbReader implements Runnable {
    interface LineSink {
        void onSbsLine(String line);
    }

    interface StatusSink {
        void onStatus(String status);
    }

    private static final int RTL_TCP_SET_FREQUENCY = 0x01;
    private static final int RTL_TCP_SET_SAMPLE_RATE = 0x02;
    private static final int RTL_TCP_SET_GAIN_MODE = 0x03;
    private static final int RTL_TCP_SET_AGC_MODE = 0x08;
    private static final int ADSB_FREQUENCY_HZ = 1_090_000_000;
    private static final int SAMPLE_RATE_HZ = 2_000_000;
    private static final int RTL_TCP_PORT = 14423;
    private static final int IQ_BUFFER_SIZE = 32 * 1024;
    private static final int MAG_TAIL_SIZE = 256;
    private static final int PREAMBLE_SAMPLES = 16;
    private static final int MESSAGE_BITS = 112;
    private static final int MESSAGE_BYTES = 14;
    private static final int MESSAGE_SAMPLES = MESSAGE_BITS * 2;
    private static final long AIRCRAFT_STATE_TTL_MS = 10 * 60_000L;
    private static final long AIRCRAFT_PRUNE_INTERVAL_MS = 30_000L;
    private static final int MAX_AIRCRAFT_STATES = 4096;
    private static final int[] MODES_CHECKSUM_TABLE = {
        0x3935ea, 0x1c9af5, 0xf1b77e, 0x78dbbf, 0xc397db, 0x9e31e9, 0xb0e2f0, 0x587178,
        0x2c38bc, 0x161c5e, 0x0b0e2f, 0xfa7d13, 0x82c48d, 0xbe9842, 0x5f4c21, 0xd05c14,
        0x682e0a, 0x341705, 0xe5f186, 0x72f8c3, 0xc68665, 0x9cb936, 0x4e5c9b, 0xd8d449,
        0x939020, 0x49c810, 0x24e408, 0x127204, 0x093902, 0x049c81, 0xfdb444, 0x7eda22,
        0x3f6d11, 0xe04c8c, 0x702646, 0x381323, 0xe3f395, 0x8e03ce, 0x4701e7, 0xdc7af7,
        0x91c77f, 0xb719bb, 0xa476d9, 0xadc168, 0x56e0b4, 0x2b705a, 0x15b82d, 0xf52612,
        0x7a9309, 0xc2b380, 0x6159c0, 0x30ace0, 0x185670, 0x0c2b38, 0x06159c, 0x030ace,
        0x018567, 0xff38b7, 0x80665f, 0xbfc92b, 0xa01e91, 0xaff54c, 0x57faa6, 0x2bfd53,
        0xea04ad, 0x8af852, 0x457c29, 0xdd4410, 0x6ea208, 0x375104, 0x1ba882, 0x0dd441,
        0xf91024, 0x7c8812, 0x3e4409, 0xe0d800, 0x706c00, 0x383600, 0x1c1b00, 0x0e0d80,
        0x0706c0, 0x038360, 0x01c1b0, 0x00e0d8, 0x00706c, 0x003836, 0x001c1b, 0xfff409
    };

    private final String host;
    private final LineSink lineSink;
    private final StatusSink statusSink;
    private final Map<String, AircraftState> aircraft = new HashMap<>();
    private volatile boolean running = true;
    private Socket socket;
    private int framesSeen;
    private int sbsLines;
    private long candidatePreambles;
    private long checksumRejects;
    private final int[] tail = new int[MAG_TAIL_SIZE];
    private final byte[] messageScratch = new byte[MESSAGE_BYTES];
    private final SimpleDateFormat sbsDateFormat = utcFormat("yyyy/MM/dd");
    private final SimpleDateFormat sbsTimeFormat = utcFormat("HH:mm:ss.SSS");
    private int[] magBuffer = new int[MAG_TAIL_SIZE + (IQ_BUFFER_SIZE / 2)];
    private int tailSize;
    private boolean hasPendingIqByte;
    private byte pendingIqByte;
    private long lastAircraftPruneMs;

    RtlTcpAdsbReader(String host, LineSink lineSink, StatusSink statusSink) {
        this.host = host;
        this.lineSink = lineSink;
        this.statusSink = statusSink;
    }

    static int port() {
        return RTL_TCP_PORT;
    }

    static int sampleRateHz() {
        return SAMPLE_RATE_HZ;
    }

    static int frequencyHz() {
        return ADSB_FREQUENCY_HZ;
    }

    void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void run() {
        statusSink.onStatus("SDR TCP CONNECT 1090 MHz");
        try (Socket tcp = new Socket()) {
            socket = tcp;
            tcp.connect(new InetSocketAddress(host, RTL_TCP_PORT), 7000);
            tcp.setSoTimeout(3000);
            configureRtlTcp(tcp.getOutputStream());
            statusSink.onStatus("SDR TUNED 1090.000 MHz");

            BufferedInputStream input = new BufferedInputStream(tcp.getInputStream(), 64 * 1024);
            byte[] buffer = new byte[IQ_BUFFER_SIZE];
            while (running) {
                int read = input.read(buffer);
                if (read < 0) break;
                processIq(buffer, read);
            }
        } catch (IOException error) {
            if (running) {
                statusSink.onStatus("SDR TCP ERROR " + error.getClass().getSimpleName());
            }
        } finally {
            running = false;
            statusSink.onStatus("SDR READER STOPPED");
        }
    }

    private void configureRtlTcp(OutputStream output) throws IOException {
        sendCommand(output, RTL_TCP_SET_SAMPLE_RATE, SAMPLE_RATE_HZ);
        sendCommand(output, RTL_TCP_SET_FREQUENCY, ADSB_FREQUENCY_HZ);
        sendCommand(output, RTL_TCP_SET_GAIN_MODE, 0);
        sendCommand(output, RTL_TCP_SET_AGC_MODE, 1);
        output.flush();
    }

    private void sendCommand(OutputStream output, int command, int value) throws IOException {
        output.write(command & 0xff);
        output.write((value >> 24) & 0xff);
        output.write((value >> 16) & 0xff);
        output.write((value >> 8) & 0xff);
        output.write(value & 0xff);
    }

    void processIq(byte[] iq, int length) {
        if (iq == null) return;
        int safeLength = Math.max(0, Math.min(length, iq.length));
        int pairs = (safeLength + (hasPendingIqByte ? 1 : 0)) / 2;
        if (pairs <= 0) {
            if (safeLength == 1 && !hasPendingIqByte) {
                pendingIqByte = iq[0];
                hasPendingIqByte = true;
            }
            return;
        }

        int magLength = tailSize + pairs;
        ensureMagnitudeCapacity(magLength);
        System.arraycopy(tail, 0, magBuffer, 0, tailSize);

        int inputIndex = 0;
        int pairIndex = 0;
        if (hasPendingIqByte && safeLength > 0) {
            writeMagnitude(pairIndex++, pendingIqByte, iq[inputIndex++]);
            hasPendingIqByte = false;
        }
        while (inputIndex + 1 < safeLength) {
            writeMagnitude(pairIndex++, iq[inputIndex], iq[inputIndex + 1]);
            inputIndex += 2;
        }
        if (inputIndex < safeLength) {
            pendingIqByte = iq[inputIndex];
            hasPendingIqByte = true;
        }

        int scanLimit = magLength - PREAMBLE_SAMPLES - MESSAGE_SAMPLES;
        for (int pos = 0; pos < scanLimit; pos++) {
            if (looksLikePreamble(magBuffer, pos)) {
                candidatePreambles += 1;
                if (decodeMessage(magBuffer, pos + PREAMBLE_SAMPLES, magLength, messageScratch)) {
                    handleMessage(messageScratch);
                    pos += PREAMBLE_SAMPLES + MESSAGE_SAMPLES - 1;
                }
            }
        }

        tailSize = Math.min(tail.length, magLength);
        System.arraycopy(magBuffer, magLength - tailSize, tail, 0, tailSize);
    }

    private void writeMagnitude(int pairIndex, byte iValue, byte qValue) {
        int iSample = (iValue & 0xff) - 127;
        int qSample = (qValue & 0xff) - 127;
        magBuffer[tailSize + pairIndex] = iSample * iSample + qSample * qSample;
    }

    private void ensureMagnitudeCapacity(int requiredLength) {
        if (magBuffer.length >= requiredLength) return;
        int newLength = magBuffer.length;
        while (newLength < requiredLength) {
            newLength *= 2;
        }
        magBuffer = new int[newLength];
    }

    private boolean looksLikePreamble(int[] mag, int pos) {
        int p0 = Math.max(mag[pos], mag[pos + 1]);
        int p1 = Math.max(mag[pos + 2], mag[pos + 3]);
        int p2 = Math.max(mag[pos + 7], mag[pos + 8]);
        int p3 = Math.max(mag[pos + 9], mag[pos + 10]);
        int quietSum =
            mag[pos + 4] + mag[pos + 5] + mag[pos + 6] +
            mag[pos + 11] + mag[pos + 12] + mag[pos + 13] + mag[pos + 14] + mag[pos + 15];
        int quietAverage = quietSum / 8;
        int quietMax = maxOf(
            mag[pos + 4],
            mag[pos + 5],
            mag[pos + 6],
            mag[pos + 11],
            mag[pos + 12],
            mag[pos + 13],
            mag[pos + 14],
            mag[pos + 15]
        );
        int peakMin = Math.min(Math.min(p0, p1), Math.min(p2, p3));
        int peakSum = p0 + p1 + p2 + p3;
        return peakMin * 3 > quietAverage * 5 &&
            peakMin * 4 > quietMax * 5 &&
            peakSum > quietSum;
    }

    private boolean decodeMessage(int[] mag, int start, int magLength, byte[] message) {
        if (start + MESSAGE_SAMPLES > magLength) return false;
        for (int i = 0; i < message.length; i++) {
            message[i] = 0;
        }
        for (int bit = 0; bit < MESSAGE_BITS; bit++) {
            int first = mag[start + bit * 2];
            int second = mag[start + bit * 2 + 1];
            int signal = first + second;
            int diff = first - second;
            if (Math.abs(diff) * 16 < signal) {
                return false;
            }
            if (diff > 0) {
                message[bit / 8] |= (byte) (1 << (7 - (bit % 8)));
            }
        }
        int df = (message[0] & 0xff) >> 3;
        if (df != 17 && df != 18) return false;
        if (!isValidModeSChecksum(message)) {
            checksumRejects += 1;
            return false;
        }
        return true;
    }

    private boolean isValidModeSChecksum(byte[] message) {
        int checksum = 0;
        for (int bit = 0; bit < 88; bit++) {
            int value = ((message[bit / 8] & 0xff) >> (7 - (bit % 8))) & 1;
            if (value != 0) {
                checksum ^= MODES_CHECKSUM_TABLE[bit];
            }
        }
        int parity = ((message[11] & 0xff) << 16) |
            ((message[12] & 0xff) << 8) |
            (message[13] & 0xff);
        return checksum == parity;
    }

    private void handleMessage(byte[] message) {
        String hex = String.format(Locale.US, "%02X%02X%02X", message[1] & 0xff, message[2] & 0xff, message[3] & 0xff);
        int typeCode = getBits(message, 32, 5);
        if (typeCode <= 0 || typeCode > 31) return;

        long messageTimeMs = System.currentTimeMillis();
        pruneAircraftStates(messageTimeMs);
        AircraftState state = aircraft.get(hex);
        if (state == null) {
            state = new AircraftState(hex);
            aircraft.put(hex, state);
        }
        state.lastSeenMs = messageTimeMs;
        framesSeen += 1;

        String line = null;
        if (typeCode >= 1 && typeCode <= 4) {
            state.callsign = decodeCallsign(message);
            line = state.toSbsLine(1, false, sbsDateFormat, sbsTimeFormat);
        } else if (typeCode >= 9 && typeCode <= 18) {
            state.altitudeFt = decodeAltitudeFt(getBits(message, 40, 12));
            boolean odd = getBits(message, 53, 1) == 1;
            int cprLat = getBits(message, 54, 17);
            int cprLon = getBits(message, 71, 17);
            if (odd) {
                state.oddLat = cprLat;
                state.oddLon = cprLon;
                state.oddMs = state.lastSeenMs;
            } else {
                state.evenLat = cprLat;
                state.evenLon = cprLon;
                state.evenMs = state.lastSeenMs;
            }
            if (decodeGlobalPosition(state)) {
                line = state.toSbsLine(3, true, sbsDateFormat, sbsTimeFormat);
            }
        } else if (typeCode == 19) {
            decodeVelocity(message, state);
            line = state.toSbsLine(4, false, sbsDateFormat, sbsTimeFormat);
        }

        if (line != null) {
            sbsLines += 1;
            lineSink.onSbsLine(line);
            if (sbsLines % 10 == 1) {
                statusSink.onStatus("ADSB FRAMES " + framesSeen + " | SBS " + sbsLines + " | CAND " + candidatePreambles);
            }
        } else if (framesSeen % 50 == 0) {
            statusSink.onStatus("ADSB FRAMES " + framesSeen + " | WAIT DATA | CAND " + candidatePreambles);
        }
    }

    private void pruneAircraftStates(long nowMs) {
        if (aircraft.size() <= MAX_AIRCRAFT_STATES && nowMs - lastAircraftPruneMs < AIRCRAFT_PRUNE_INTERVAL_MS) return;
        lastAircraftPruneMs = nowMs;

        Iterator<Map.Entry<String, AircraftState>> iterator = aircraft.entrySet().iterator();
        while (iterator.hasNext()) {
            AircraftState state = iterator.next().getValue();
            if (nowMs - state.lastSeenMs > AIRCRAFT_STATE_TTL_MS) iterator.remove();
        }
        while (aircraft.size() > MAX_AIRCRAFT_STATES) {
            String oldestHex = null;
            long oldestSeenMs = Long.MAX_VALUE;
            for (Map.Entry<String, AircraftState> entry : aircraft.entrySet()) {
                if (entry.getValue().lastSeenMs < oldestSeenMs) {
                    oldestSeenMs = entry.getValue().lastSeenMs;
                    oldestHex = entry.getKey();
                }
            }
            if (oldestHex == null) break;
            aircraft.remove(oldestHex);
        }
    }

    private String decodeCallsign(byte[] message) {
        String table = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ#####_###############0123456789######";
        StringBuilder callsign = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            int value = getBits(message, 40 + i * 6, 6);
            char c = value < table.length() ? table.charAt(value) : ' ';
            if (c != '#' && c != '_') callsign.append(c);
        }
        return callsign.toString().trim();
    }

    private Integer decodeAltitudeFt(int code) {
        if (code == 0) return null;
        int qBit = (code >> 4) & 1;
        if (qBit == 0) return null;
        int n = ((code & 0x0fe0) >> 1) | (code & 0x000f);
        return n * 25 - 1000;
    }

    private void decodeVelocity(byte[] message, AircraftState state) {
        int subtype = getBits(message, 37, 3);
        if (subtype != 1 && subtype != 2) return;

        int ewSign = getBits(message, 45, 1);
        int ewVelocity = getBits(message, 46, 10) - 1;
        int nsSign = getBits(message, 56, 1);
        int nsVelocity = getBits(message, 57, 10) - 1;
        if (ewVelocity < 0 || nsVelocity < 0) return;

        double east = ewSign == 1 ? -ewVelocity : ewVelocity;
        double north = nsSign == 1 ? -nsVelocity : nsVelocity;
        state.speedKt = (int) Math.round(Math.sqrt(east * east + north * north));
        double track = Math.toDegrees(Math.atan2(east, north));
        if (track < 0) track += 360.0;
        state.trackDeg = (int) Math.round(track) % 360;
    }

    private boolean decodeGlobalPosition(AircraftState state) {
        if (state.evenLat == null || state.oddLat == null || state.evenLon == null || state.oddLon == null) return false;
        if (Math.abs(state.evenMs - state.oddMs) > 10_000L) return false;

        double evenLat = state.evenLat / 131072.0;
        double oddLat = state.oddLat / 131072.0;
        int j = (int) Math.floor((59 * evenLat) - (60 * oddLat) + 0.5);
        double rlatEven = 6.0 * (mod(j, 60) + evenLat);
        double rlatOdd = (360.0 / 59.0) * (mod(j, 59) + oddLat);
        if (rlatEven >= 270.0) rlatEven -= 360.0;
        if (rlatOdd >= 270.0) rlatOdd -= 360.0;
        if (cprNl(rlatEven) != cprNl(rlatOdd)) return false;

        boolean useOdd = state.oddMs > state.evenMs;
        double lat = useOdd ? rlatOdd : rlatEven;
        int nl = cprNl(lat) - (useOdd ? 1 : 0);
        if (nl < 1) nl = 1;

        double evenLon = state.evenLon / 131072.0;
        double oddLon = state.oddLon / 131072.0;
        int m = (int) Math.floor((evenLon * (cprNl(lat) - 1)) - (oddLon * cprNl(lat)) + 0.5);
        double lon = (360.0 / nl) * (mod(m, nl) + (useOdd ? oddLon : evenLon));
        if (lon > 180.0) lon -= 360.0;

        state.lat = lat;
        state.lon = lon;
        return true;
    }

    private int getBits(byte[] message, int start, int length) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            int bitIndex = start + i;
            int bit = ((message[bitIndex / 8] & 0xff) >> (7 - (bitIndex % 8))) & 1;
            value = (value << 1) | bit;
        }
        return value;
    }

    private int mod(int value, int divisor) {
        int result = value % divisor;
        return result < 0 ? result + divisor : result;
    }

    private int maxOf(int a, int b, int c, int d, int e, int f, int g, int h) {
        return Math.max(Math.max(Math.max(a, b), Math.max(c, d)), Math.max(Math.max(e, f), Math.max(g, h)));
    }

    private static SimpleDateFormat utcFormat(String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }

    private int cprNl(double lat) {
        double a = Math.abs(lat);
        if (a < 10.47047130) return 59;
        if (a < 14.82817437) return 58;
        if (a < 18.18626357) return 57;
        if (a < 21.02939493) return 56;
        if (a < 23.54504487) return 55;
        if (a < 25.82924707) return 54;
        if (a < 27.93898710) return 53;
        if (a < 29.91135686) return 52;
        if (a < 31.77209708) return 51;
        if (a < 33.53993436) return 50;
        if (a < 35.22899598) return 49;
        if (a < 36.85025108) return 48;
        if (a < 38.41241892) return 47;
        if (a < 39.92256684) return 46;
        if (a < 41.38651832) return 45;
        if (a < 42.80914012) return 44;
        if (a < 44.19454951) return 43;
        if (a < 45.54626723) return 42;
        if (a < 46.86733252) return 41;
        if (a < 48.16039128) return 40;
        if (a < 49.42776439) return 39;
        if (a < 50.67150166) return 38;
        if (a < 51.89342469) return 37;
        if (a < 53.09516153) return 36;
        if (a < 54.27817472) return 35;
        if (a < 55.44378444) return 34;
        if (a < 56.59318756) return 33;
        if (a < 57.72747354) return 32;
        if (a < 58.84763776) return 31;
        if (a < 59.95459277) return 30;
        if (a < 61.04917774) return 29;
        if (a < 62.13216659) return 28;
        if (a < 63.20427479) return 27;
        if (a < 64.26616523) return 26;
        if (a < 65.31845310) return 25;
        if (a < 66.36171008) return 24;
        if (a < 67.39646774) return 23;
        if (a < 68.42322022) return 22;
        if (a < 69.44242631) return 21;
        if (a < 70.45451075) return 20;
        if (a < 71.45986473) return 19;
        if (a < 72.45884545) return 18;
        if (a < 73.45177442) return 17;
        if (a < 74.43893416) return 16;
        if (a < 75.42056257) return 15;
        if (a < 76.39684391) return 14;
        if (a < 77.36789461) return 13;
        if (a < 78.33374083) return 12;
        if (a < 79.29428225) return 11;
        if (a < 80.24923213) return 10;
        if (a < 81.19801349) return 9;
        if (a < 82.13956981) return 8;
        if (a < 83.07199445) return 7;
        if (a < 83.99173563) return 6;
        if (a < 84.89166191) return 5;
        if (a < 85.75541621) return 4;
        if (a < 86.53536998) return 3;
        if (a < 87.00000000) return 2;
        return 1;
    }

    private static final class AircraftState {
        final String hex;
        String callsign;
        Double lat;
        Double lon;
        Integer altitudeFt;
        Integer speedKt;
        Integer trackDeg;
        Integer evenLat;
        Integer evenLon;
        Integer oddLat;
        Integer oddLon;
        long evenMs;
        long oddMs;
        long lastSeenMs;

        AircraftState(String hex) {
            this.hex = hex;
        }

        String toSbsLine(
            int transmissionType,
            boolean includePosition,
            SimpleDateFormat dateFormat,
            SimpleDateFormat timeFormat
        ) {
            if (transmissionType == 1 && (callsign == null || callsign.isEmpty())) return null;
            if (transmissionType == 3 && (!includePosition || lat == null || lon == null)) return null;
            if (transmissionType == 4 && (speedKt == null || trackDeg == null)) return null;
            Date now = new Date(lastSeenMs > 0 ? lastSeenMs : System.currentTimeMillis());
            return String.format(
                Locale.US,
                "MSG,%d,1,1,%s,1,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,0,0,0,0,0,0",
                transmissionType,
                hex,
                dateFormat.format(now),
                timeFormat.format(now),
                dateFormat.format(now),
                timeFormat.format(now),
                callsign == null ? "" : callsign,
                altitudeFt == null ? "" : altitudeFt.toString(),
                speedKt == null ? "" : speedKt.toString(),
                trackDeg == null ? "" : trackDeg.toString(),
                includePosition && lat != null ? String.format(Locale.US, "%.5f", lat) : "",
                includePosition && lon != null ? String.format(Locale.US, "%.5f", lon) : ""
            );
        }
    }
}
