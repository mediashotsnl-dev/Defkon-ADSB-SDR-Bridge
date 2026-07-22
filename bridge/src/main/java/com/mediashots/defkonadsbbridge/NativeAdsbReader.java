package com.mediashots.defkonadsbbridge;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class NativeAdsbReader implements Runnable {
    static final int EXIT_OK = 0;
    static final int EXIT_STOPPED = 1;
    static final int EXIT_LIBRARY_NOT_LOADED = -1000;
    static final int EXIT_NO_DEVICE = -1001;
    static final int EXIT_NO_PERMISSION = -1002;
    static final int EXIT_OPEN_FAILED = -1003;
    static final int EXIT_CORE_OPEN_FAILED = -1004;
    static final int EXIT_TUNE_FAILED = -1005;

    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int READSB_SAMPLE_RATE_HZ = 2_400_000;
    private static final int TWO_SAMPLE_DECODER_RATE_HZ = 2_000_000;
    private static final int LIBUSB_TIMEOUT = -7;
    private static final int MAX_CONSECUTIVE_READ_TIMEOUTS = 5;

    private final Context context;
    private final RtlTcpAdsbReader.LineSink lineSink;
    private final RtlTcpAdsbReader.StatusSink statusSink;
    private final RtlTcpAdsbReader decoder;
    private final int decoderMode;
    private final AtomicBoolean stopStarted = new AtomicBoolean(false);
    private volatile boolean running = true;
    private volatile int exitCode = EXIT_OK;
    private UsbDeviceConnection connection;

    NativeAdsbReader(
        Context context,
        RtlTcpAdsbReader.LineSink lineSink,
        RtlTcpAdsbReader.StatusSink statusSink,
        int decoderMode
    ) {
        this.context = context.getApplicationContext();
        this.lineSink = lineSink;
        this.statusSink = statusSink;
        this.decoderMode = decoderMode;
        this.decoder = new RtlTcpAdsbReader("native", lineSink, statusSink);
    }

    static boolean isNativeCoreAvailable() {
        return NativeRtlSdrDriver.isLibraryLoaded();
    }

    void stop() {
        if (!stopStarted.compareAndSet(false, true)) return;
        if (exitCode == EXIT_OK) {
            exitCode = EXIT_STOPPED;
        }
        running = false;
        NativeRtlSdrDriver.close();
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    int exitCode() {
        return exitCode;
    }

    @Override
    public void run() {
        exitCode = EXIT_OK;
        if (!NativeRtlSdrDriver.isLibraryLoaded()) {
            exitCode = EXIT_LIBRARY_NOT_LOADED;
            statusSink.onStatus("NATIVE SDR LIBRARY NOT LOADED");
            return;
        }

        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            exitCode = EXIT_OPEN_FAILED;
            statusSink.onStatus("NATIVE SDR USB MANAGER N/A");
            return;
        }

        UsbDevice device = firstRtlSdrDevice(usbManager);
        if (device == null) {
            exitCode = EXIT_NO_DEVICE;
            statusSink.onStatus("NATIVE SDR WAIT RTL-SDR");
            return;
        }
        if (!usbManager.hasPermission(device)) {
            exitCode = EXIT_NO_PERMISSION;
            statusSink.onStatus("NATIVE SDR USB PERMISSION");
            return;
        }

        connection = usbManager.openDevice(device);
        if (connection == null) {
            exitCode = EXIT_OPEN_FAILED;
            statusSink.onStatus("NATIVE SDR OPEN FAILED");
            return;
        }

        int fd = connection.getFileDescriptor();
        statusSink.onStatus("NATIVE SDR OPEN USB FD " + fd);
        if (!NativeRtlSdrDriver.openFromUsbFileDescriptor(fd)) {
            exitCode = EXIT_CORE_OPEN_FAILED;
            statusSink.onStatus("NATIVE SDR CORE OPEN FAILED");
            stop();
            return;
        }
        int sampleRateHz = sampleRateForDecoderMode(decoderMode);
        if (!NativeRtlSdrDriver.tuneAdsb1090(sampleRateHz)) {
            exitCode = EXIT_TUNE_FAILED;
            statusSink.onStatus("NATIVE SDR TUNE 1090 FAILED");
            stop();
            return;
        }

        boolean useNativeDecoder = decoderMode != BridgeService.DECODER_MODE_LEGACY_JAVA && NativeModeSDecoder.isAvailable();
        if (decoderMode != BridgeService.DECODER_MODE_LEGACY_JAVA && !useNativeDecoder) {
            statusSink.onStatus("NATIVE DECODER NOT READY | LEGACY JAVA");
        }
        if (useNativeDecoder) {
            NativeModeSDecoder.reset();
        }

        statusSink.onStatus(useNativeDecoder
            ? "NATIVE SDR TUNED 1090.000 MHz | " + sampleRateHz + " SPS | DECODER " + decoderModeLabel()
            : "NATIVE SDR TUNED 1090.000 MHz | " + sampleRateHz + " SPS | DECODER LEGACY JAVA");
        byte[] buffer = new byte[BUFFER_SIZE];
        int consecutiveReadTimeouts = 0;
        while (running) {
            int read = NativeRtlSdrDriver.readSamples(buffer, buffer.length);
            if (read == LIBUSB_TIMEOUT) {
                consecutiveReadTimeouts += 1;
                if (consecutiveReadTimeouts < MAX_CONSECUTIVE_READ_TIMEOUTS) continue;
                exitCode = read;
                statusSink.onStatus("NATIVE SDR USB TIMEOUT");
                break;
            }
            if (read == 0) {
                continue;
            }
            if (read < 0) {
                if (!running) break;
                exitCode = read;
                statusSink.onStatus("NATIVE SDR READ STOP " + read);
                break;
            }
            consecutiveReadTimeouts = 0;
            if (useNativeDecoder) {
                emitNativeDecoderLines(NativeModeSDecoder.processIq(buffer, read, decoderMode));
            } else {
                decoder.processIq(buffer, read);
            }
        }
        if (exitCode == EXIT_OK && !running) {
            exitCode = EXIT_STOPPED;
        }
        stop();
        statusSink.onStatus("NATIVE SDR READER STOPPED");
    }

    private void emitNativeDecoderLines(String lines) {
        if (lines == null || lines.isEmpty()) return;
        int start = 0;
        int length = lines.length();
        for (int i = 0; i < length; i++) {
            if (lines.charAt(i) == '\n') {
                if (i > start) {
                    lineSink.onSbsLine(lines.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (start < length) {
            lineSink.onSbsLine(lines.substring(start));
        }
    }

    private String decoderModeLabel() {
        return decoderMode == BridgeService.DECODER_MODE_READSB_CORE ? "READSB CORE" : "NATIVE FAST";
    }

    static int sampleRateForDecoderMode(int decoderMode) {
        return decoderMode == BridgeService.DECODER_MODE_READSB_CORE
            ? READSB_SAMPLE_RATE_HZ
            : TWO_SAMPLE_DECODER_RATE_HZ;
    }

    private UsbDevice firstRtlSdrDevice(UsbManager usbManager) {
        for (Map.Entry<String, UsbDevice> entry : usbManager.getDeviceList().entrySet()) {
            UsbDevice device = entry.getValue();
            if (isLikelyRtlSdr(device.getVendorId(), device.getProductId())) {
                return device;
            }
        }
        return null;
    }

    private boolean isLikelyRtlSdr(int vendorId, int productId) {
        return vendorId == 0x0BDA &&
            (productId == 0x2830 || productId == 0x2831 || productId == 0x2832 || productId == 0x2838);
    }
}
