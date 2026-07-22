package com.mediashots.defkonadsbbridge;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("InlinedApi")
public class BridgeService extends Service {
    private static final String TAG = "DefkonAdsbBridge";
    public static final String ACTION_START = "com.mediashots.defkonadsbbridge.action.START";
    public static final String ACTION_STOP = "com.mediashots.defkonadsbbridge.action.STOP";
    public static final String ACTION_SEND_TEST = "com.mediashots.defkonadsbbridge.action.SEND_TEST";
    public static final String ACTION_START_SDR = "com.mediashots.defkonadsbbridge.action.START_SDR";
    public static final String ACTION_STOP_SDR = "com.mediashots.defkonadsbbridge.action.STOP_SDR";
    public static final String ACTION_START_NATIVE_SDR = "com.mediashots.defkonadsbbridge.action.START_NATIVE_SDR";
    public static final String ACTION_START_EXTERNAL_SDR = "com.mediashots.defkonadsbbridge.action.START_EXTERNAL_SDR";
    public static final String ACTION_STATUS = "com.mediashots.defkonadsbbridge.action.STATUS";
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_CLIENTS = "clients";
    public static final String EXTRA_LOG = "log";
    public static final String EXTRA_DECODER_MODE = "decoder_mode";
    public static final int DECODER_MODE_LEGACY_JAVA = 0;
    public static final int DECODER_MODE_NATIVE_FAST = 1;
    public static final int DECODER_MODE_READSB_CORE = 2;

    private static final int SBS_PORT = 30003;
    private static final int AIRCRAFT_JSON_PORT = 5051;
    private static final int NOTIFICATION_ID = 1090;
    private static final int SDR_MODE_NONE = 0;
    private static final int SDR_MODE_NATIVE = 1;
    private static final int SDR_MODE_EXTERNAL = 2;
    private static final int NATIVE_FAILURES_BEFORE_FALLBACK = 2;
    private static final long SDR_RESTART_DELAY_MS = 1500L;
    private static final long REPEATED_STATUS_LOG_MS = 1000L;
    private static final long CLIENT_STATUS_LOG_MS = 2000L;
    private static final long CLIENT_STATUS_BROADCAST_MS = 500L;
    private static final int MAX_SBS_CLIENTS = 4;
    private static final int MAX_HTTP_REQUEST_BYTES = 8192;
    private static final int MAX_PENDING_SBS_LINES = 256;
    private static final String CHANNEL_ID = "defkon_adsb_bridge";
    private static final String PREFS = "bridge_diagnostics";
    private static final String PREF_REQUESTED_SDR_MODE = "requested_sdr_mode";
    private static final String PREF_DECODER_MODE = "decoder_mode";

    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final ExecutorService sbsClientIo = Executors.newFixedThreadPool(MAX_SBS_CLIENTS);
    private final ThreadPoolExecutor httpIo = new ThreadPoolExecutor(
        1,
        2,
        30L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16)
    );
    private final List<ClientConnection> clients = new CopyOnWriteArrayList<>();
    private final BridgeAircraftStore aircraftStore = new BridgeAircraftStore();

    private ServerSocket serverSocket;
    private ServerSocket aircraftJsonSocket;
    private RtlTcpAdsbReader externalReader;
    private NativeAdsbReader nativeReader;
    private volatile boolean running;
    private volatile boolean aircraftJsonRunning;
    private volatile boolean sdrRunning;
    private volatile boolean sdrStarting;
    private final Object sdrLock = new Object();
    private volatile int requestedSdrMode = SDR_MODE_NONE;
    private volatile int decoderMode = DECODER_MODE_READSB_CORE;
    private int consecutiveNativeFailures;
    private String serverStatus = "SERVER | STARTING";
    private String aircraftJsonStatus = "JSON | STARTING";
    private String lastLoggedStatus = "";
    private long lastStatusLogMs;
    private long lastClientStatusLogMs;
    private long lastClientStatusBroadcastMs;
    private int testMessageIndex;

    @Override
    public void onCreate() {
        super.onCreate();
        BridgeCrashLogger.install(this);
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        requestedSdrMode = sanitizeSdrMode(preferences.getInt(PREF_REQUESTED_SDR_MODE, SDR_MODE_NONE));
        decoderMode = sanitizeDecoderMode(preferences.getInt(PREF_DECODER_MODE, DECODER_MODE_READSB_CORE));
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        decoderMode = sanitizeDecoderMode(intent != null
            ? intent.getIntExtra(EXTRA_DECODER_MODE, decoderMode)
            : decoderMode);
        persistDecoderMode();
        startInForeground(foregroundTypeForAction(action));
        if (ACTION_STOP.equals(action)) {
            setRequestedSdrMode(SDR_MODE_NONE);
            stopBridge();
            stopSelf();
            return START_NOT_STICKY;
        }
        startServerIfNeeded();
        startAircraftJsonServerIfNeeded();
        if (ACTION_SEND_TEST.equals(action)) {
            io.execute(this::sendTestTarget);
        } else if (ACTION_START_SDR.equals(action)) {
            startBestSdrReaderIfNeeded();
        } else if (ACTION_STOP_SDR.equals(action)) {
            setRequestedSdrMode(SDR_MODE_NONE);
            stopSdrReader();
            startInForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            sendStatus("SDR READER STOPPED");
        } else if (ACTION_START_NATIVE_SDR.equals(action)) {
            setRequestedSdrMode(SDR_MODE_NATIVE);
            consecutiveNativeFailures = 0;
            promoteConnectedDeviceForegroundIfPermitted();
            startNativeSdrReaderIfNeeded();
        } else if (ACTION_START_EXTERNAL_SDR.equals(action)) {
            setRequestedSdrMode(SDR_MODE_EXTERNAL);
            consecutiveNativeFailures = 0;
            promoteConnectedDeviceForegroundIfPermitted();
            startExternalSdrReaderIfNeeded();
        } else if (ACTION_START.equals(action)) {
            resumeRequestedSdrModeIfNeeded();
        }
        sendStatus("BRIDGE SERVICE RUNNING");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopBridge();
        io.shutdownNow();
        httpIo.shutdownNow();
        sbsClientIo.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        sendStatus("FOREGROUND SERVICE TIMEOUT");
        stopBridge();
        stopSelf(startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startInForeground(int foregroundServiceType) {
        Notification notification = buildNotification("SBS " + SBS_PORT + " | JSON " + AIRCRAFT_JSON_PORT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private int foregroundTypeForAction(String action) {
        if (ACTION_STOP.equals(action) || ACTION_STOP_SDR.equals(action)) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        boolean sdrRequested = requestedSdrMode != SDR_MODE_NONE ||
            ACTION_START_SDR.equals(action) ||
            ACTION_START_NATIVE_SDR.equals(action) ||
            ACTION_START_EXTERNAL_SDR.equals(action);
        return sdrRequested && hasUsbDevicePermission()
            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
    }

    private Notification buildNotification(String text) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);

        return builder
            .setContentTitle("DEFKON ADSB Bridge running")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bridge_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "DEFKON ADSB Bridge",
            NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void setRequestedSdrMode(int mode) {
        requestedSdrMode = sanitizeSdrMode(mode);
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putInt(PREF_REQUESTED_SDR_MODE, requestedSdrMode)
            .apply();
    }

    private void persistDecoderMode() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putInt(PREF_DECODER_MODE, decoderMode)
            .apply();
    }

    private int sanitizeSdrMode(int mode) {
        if (mode == SDR_MODE_NATIVE || mode == SDR_MODE_EXTERNAL) return mode;
        return SDR_MODE_NONE;
    }

    private void resumeRequestedSdrModeIfNeeded() {
        if (requestedSdrMode == SDR_MODE_NATIVE) {
            promoteConnectedDeviceForegroundIfPermitted();
            startNativeSdrReaderIfNeeded();
        } else if (requestedSdrMode == SDR_MODE_EXTERNAL) {
            promoteConnectedDeviceForegroundIfPermitted();
            startExternalSdrReaderIfNeeded();
        }
    }

    private void promoteConnectedDeviceForegroundIfPermitted() {
        if (!hasUsbDevicePermission()) return;
        try {
            startInForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (SecurityException permissionRace) {
            sendStatus("CONNECTED DEVICE FOREGROUND PERMISSION LOST");
        }
    }

    private boolean hasUsbDevicePermission() {
        UsbManager usbManager = (UsbManager) getSystemService(USB_SERVICE);
        if (usbManager == null) return false;
        for (Map.Entry<String, UsbDevice> entry : usbManager.getDeviceList().entrySet()) {
            UsbDevice device = entry.getValue();
            if (isLikelyRtlSdr(device) && usbManager.hasPermission(device)) return true;
        }
        return false;
    }

    private boolean isLikelyRtlSdr(UsbDevice device) {
        if (device == null || device.getVendorId() != 0x0BDA) return false;
        int productId = device.getProductId();
        return productId == 0x2830 || productId == 0x2831 || productId == 0x2832 || productId == 0x2838;
    }

    private void startServerIfNeeded() {
        if (running) return;
        running = true;
        io.execute(() -> {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0.1", SBS_PORT));
                serverSocket = socket;
                serverStatus = "SERVER | SBS " + SBS_PORT + " | JSON " + AIRCRAFT_JSON_PORT;
                sendStatus("SBS SERVER READY");
                while (running) {
                    try {
                        Socket client = socket.accept();
                        if (clients.size() >= MAX_SBS_CLIENTS) {
                            closeQuietly(client);
                            sendStatus("SBS CLIENT LIMIT REACHED");
                            continue;
                        }
                        client.setTcpNoDelay(true);
                        ClientConnection connection;
                        try {
                            connection = new ClientConnection(client);
                        } catch (IOException error) {
                            closeQuietly(client);
                            throw error;
                        }
                        clients.add(connection);
                        connection.start(sbsClientIo, () -> clients.remove(connection));
                        sendStatus("CLIENT CONNECTED " + client.getInetAddress().getHostAddress());
                    } catch (IOException error) {
                        if (running) sendStatus("CLIENT ERROR " + error.getClass().getSimpleName());
                    }
                }
            } catch (IOException error) {
                serverStatus = "SERVER | ERROR " + error.getClass().getSimpleName();
                sendStatus("SERVER ERROR " + error.getMessage());
            } finally {
                running = false;
            }
        });
    }

    private void startAircraftJsonServerIfNeeded() {
        if (aircraftJsonRunning) return;
        aircraftJsonRunning = true;
        io.execute(() -> {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0.1", AIRCRAFT_JSON_PORT));
                aircraftJsonSocket = socket;
                aircraftJsonStatus = "JSON | 127.0.0.1:" + AIRCRAFT_JSON_PORT + "/aircraft.json";
                sendStatus("AIRCRAFT JSON READY");
                while (aircraftJsonRunning) {
                    try {
                        Socket client = socket.accept();
                        try {
                            httpIo.execute(() -> handleAircraftJsonClient(client));
                        } catch (RejectedExecutionException rejected) {
                            closeQuietly(client);
                            sendStatus("AIRCRAFT JSON BUSY");
                        }
                    } catch (IOException error) {
                        if (aircraftJsonRunning) sendStatus("AIRCRAFT JSON CLIENT ERROR " + error.getClass().getSimpleName());
                    }
                }
            } catch (IOException error) {
                aircraftJsonStatus = "JSON | ERROR " + error.getClass().getSimpleName();
                sendStatus("AIRCRAFT JSON ERROR " + error.getMessage());
            } finally {
                aircraftJsonRunning = false;
            }
        });
    }

    private void handleAircraftJsonClient(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(1500);
            String requestLine = readHttpRequestLine(socket.getInputStream());

            boolean ok = requestLine != null &&
                (requestLine.startsWith("GET /aircraft.json ") || requestLine.startsWith("GET /aircraft.json?"));
            String body = ok
                ? aircraftStore.toAircraftJson(System.currentTimeMillis())
                : "{\"error\":\"not found\"}";
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String statusLine = ok ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 404 Not Found\r\n";
            String headers = statusLine +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n\r\n";

            OutputStream output = socket.getOutputStream();
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(bodyBytes);
            output.flush();
        } catch (IOException ignored) {
        }
    }

    private String readHttpRequestLine(InputStream input) throws IOException {
        byte[] request = new byte[MAX_HTTP_REQUEST_BYTES];
        int count = 0;
        int headerEndMatch = 0;
        while (count < request.length) {
            int value = input.read();
            if (value < 0) break;
            request[count++] = (byte) value;
            if ((headerEndMatch == 0 || headerEndMatch == 2) && value == '\r') {
                headerEndMatch += 1;
            } else if ((headerEndMatch == 1 || headerEndMatch == 3) && value == '\n') {
                headerEndMatch += 1;
                if (headerEndMatch == 4) break;
            } else {
                headerEndMatch = value == '\r' ? 1 : 0;
            }
        }
        if (count == request.length && headerEndMatch != 4) return null;

        int lineEnd = -1;
        for (int i = 0; i + 1 < count; i++) {
            if (request[i] == '\r' && request[i + 1] == '\n') {
                lineEnd = i;
                break;
            }
        }
        if (lineEnd <= 0) return null;
        return new String(request, 0, lineEnd, StandardCharsets.US_ASCII);
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void stopBridge() {
        running = false;
        aircraftJsonRunning = false;
        stopSdrReader();
        for (ClientConnection client : clients) {
            client.close();
        }
        clients.clear();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        try {
            if (aircraftJsonSocket != null) aircraftJsonSocket.close();
        } catch (IOException ignored) {
        }
        serverSocket = null;
        aircraftJsonSocket = null;
        aircraftStore.clear();
        serverStatus = "SERVER | STOPPED";
        aircraftJsonStatus = "JSON | STOPPED";
        sendStatus("BRIDGE SERVICE STOPPED");
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void startBestSdrReaderIfNeeded() {
        if (NativeRtlSdrDriver.isCoreLinked()) {
            setRequestedSdrMode(SDR_MODE_NATIVE);
            consecutiveNativeFailures = 0;
            promoteConnectedDeviceForegroundIfPermitted();
            startNativeSdrReaderIfNeeded();
        } else {
            sendStatus("NATIVE SDR CORE NOT READY | USE EXTERNAL");
            setRequestedSdrMode(SDR_MODE_EXTERNAL);
            consecutiveNativeFailures = 0;
            promoteConnectedDeviceForegroundIfPermitted();
            startExternalSdrReaderIfNeeded();
        }
    }

    private void startNativeSdrReaderIfNeeded() {
        NativeAdsbReader reader;
        synchronized (sdrLock) {
            if (sdrRunning || sdrStarting) {
                sendStatus("SDR READER ALREADY RUNNING");
                return;
            }
            sdrStarting = true;
            sdrRunning = true;
            reader = new NativeAdsbReader(
                this,
                this::broadcastLine,
                this::sendStatus,
                decoderMode
            );
            nativeReader = reader;
        }

        io.execute(() -> {
            try {
                sdrStarting = false;
                reader.run();
            } finally {
                int exitCode = reader.exitCode();
                synchronized (sdrLock) {
                    sdrRunning = false;
                    sdrStarting = false;
                    if (nativeReader == reader) {
                        nativeReader = null;
                    }
                }
                handleNativeReaderFinished(exitCode);
            }
        });
    }

    private void startExternalSdrReaderIfNeeded() {
        RtlTcpAdsbReader reader;
        synchronized (sdrLock) {
            if (sdrRunning || sdrStarting) {
                sendStatus("SDR READER ALREADY RUNNING");
                return;
            }
            sdrStarting = true;
            sdrRunning = true;
            reader = new RtlTcpAdsbReader(
                "127.0.0.1",
                this::broadcastLine,
                this::sendStatus
            );
            externalReader = reader;
        }

        io.execute(() -> {
            try {
                sdrStarting = false;
                reader.run();
            } finally {
                synchronized (sdrLock) {
                    sdrRunning = false;
                    sdrStarting = false;
                    if (externalReader == reader) {
                        externalReader = null;
                    }
                }
                scheduleSdrRestartIfRequested(SDR_MODE_EXTERNAL, "EXTERNAL SDR RESTART");
            }
        });
    }

    private void scheduleSdrRestartIfRequested(int mode, String log) {
        scheduleSdrRestartIfRequested(mode, log, SDR_RESTART_DELAY_MS);
    }

    private void scheduleSdrRestartIfRequested(int mode, String log, long delayMs) {
        if (requestedSdrMode != mode || !running) return;
        io.execute(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            if (requestedSdrMode != mode || sdrRunning || sdrStarting || !running) return;
            sendStatus(log);
            if (mode == SDR_MODE_NATIVE) {
                startNativeSdrReaderIfNeeded();
            } else if (mode == SDR_MODE_EXTERNAL) {
                startExternalSdrReaderIfNeeded();
            }
        });
    }

    private void handleNativeReaderFinished(int exitCode) {
        if (requestedSdrMode != SDR_MODE_NATIVE || !running) return;

        if (isNativeFailureForFallback(exitCode)) {
            consecutiveNativeFailures += 1;
            sendStatus("NATIVE SDR USB LOST " + exitCode + " | RETRY " + consecutiveNativeFailures + "/" + NATIVE_FAILURES_BEFORE_FALLBACK);
            if (consecutiveNativeFailures >= NATIVE_FAILURES_BEFORE_FALLBACK) {
                consecutiveNativeFailures = 0;
                setRequestedSdrMode(SDR_MODE_EXTERNAL);
                sendStatus("NATIVE SDR FALLBACK REQUESTED");
                startExternalSdrReaderIfNeeded();
                return;
            }
        } else if (exitCode == NativeAdsbReader.EXIT_NO_DEVICE) {
            sendStatus("NATIVE SDR WAIT DONGLE");
        } else if (exitCode == NativeAdsbReader.EXIT_NO_PERMISSION) {
            sendStatus("NATIVE SDR WAIT USB PERMISSION");
        }

        scheduleSdrRestartIfRequested(SDR_MODE_NATIVE, "NATIVE SDR RESTART");
    }

    private boolean isNativeFailureForFallback(int exitCode) {
        return exitCode < 0 &&
            exitCode != NativeAdsbReader.EXIT_NO_DEVICE &&
            exitCode != NativeAdsbReader.EXIT_NO_PERMISSION;
    }

    private void stopSdrReader() {
        RtlTcpAdsbReader externalToStop;
        NativeAdsbReader nativeToStop;
        synchronized (sdrLock) {
            sdrRunning = false;
            sdrStarting = false;
            consecutiveNativeFailures = 0;
            aircraftStore.clear();
            externalToStop = externalReader;
            nativeToStop = nativeReader;
            externalReader = null;
            nativeReader = null;
        }
        if (externalToStop != null) {
            externalToStop.stop();
        }
        if (nativeToStop != null) {
            nativeToStop.stop();
        }
    }

    private void sendTestTarget() {
        testMessageIndex += 1;
        double lat = 52.0900 + (testMessageIndex % 5) * 0.003;
        double lon = 5.1200 + (testMessageIndex % 5) * 0.004;
        String hex = String.format(Locale.US, "DF%04X", testMessageIndex & 0xffff);
        String line = String.format(
            Locale.US,
            "MSG,3,1,1,%s,1,2026/06/22,12:00:00.000,2026/06/22,12:00:00.000,DFK%03d,1200,95,270,%.5f,%.5f,0,0,0,0,0,0",
            hex,
            testMessageIndex,
            lat,
            lon
        );
        broadcastLine(line);
        sendStatus("TEST SBS TARGET SENT " + hex);
    }

    private void broadcastLine(String line) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            io.execute(() -> broadcastLine(line));
            return;
        }

        aircraftStore.mergeSbsLine(line, System.currentTimeMillis());
        if (requestedSdrMode == SDR_MODE_NATIVE) {
            consecutiveNativeFailures = 0;
        }
        String payload = line + "\n";
        for (ClientConnection client : clients) {
            if (!client.enqueue(payload)) {
                clients.remove(client);
                client.close();
            }
        }
        sendStatus("CLIENTS " + clients.size() + " | JSON AC " + aircraftStore.recentAircraftCount(System.currentTimeMillis()));
    }

    private synchronized void sendStatus(String log) {
        writeStatusLogcat(log);
        long nowMs = System.currentTimeMillis();
        if (log != null && (log.startsWith("CLIENTS ") || log.startsWith("ADSB FRAMES "))) {
            if (nowMs - lastClientStatusBroadcastMs < CLIENT_STATUS_BROADCAST_MS) return;
            lastClientStatusBroadcastMs = nowMs;
        }
        Intent status = new Intent(ACTION_STATUS)
            .setPackage(getPackageName())
            .putExtra(EXTRA_SERVER, serverStatus + " | " + aircraftJsonStatus)
            .putExtra(EXTRA_CLIENTS, clients.size())
            .putExtra(EXTRA_LOG, log);
        sendBroadcast(status);
    }

    private void writeStatusLogcat(String log) {
        if (!BuildConfig.DEBUG) return;
        if (log == null || log.trim().isEmpty()) return;

        long nowMs = System.currentTimeMillis();
        if (log.startsWith("CLIENTS ")) {
            if (nowMs - lastClientStatusLogMs < CLIENT_STATUS_LOG_MS) return;
            lastClientStatusLogMs = nowMs;
        } else if (log.equals(lastLoggedStatus) && nowMs - lastStatusLogMs < REPEATED_STATUS_LOG_MS) {
            return;
        }

        lastLoggedStatus = log;
        lastStatusLogMs = nowMs;
        Log.w(
            TAG,
            log + " | mode=" + sdrModeName(requestedSdrMode) +
                " | decoder=" + decoderModeName(decoderMode) +
                " | sdrRunning=" + sdrRunning +
                " | clients=" + clients.size()
        );
    }

    private String sdrModeName(int mode) {
        if (mode == SDR_MODE_NATIVE) return "native";
        if (mode == SDR_MODE_EXTERNAL) return "external";
        return "none";
    }

    private int sanitizeDecoderMode(int mode) {
        if (mode == DECODER_MODE_LEGACY_JAVA) return DECODER_MODE_LEGACY_JAVA;
        if (mode == DECODER_MODE_NATIVE_FAST) return DECODER_MODE_NATIVE_FAST;
        return DECODER_MODE_READSB_CORE;
    }

    private String decoderModeName(int mode) {
        if (mode == DECODER_MODE_LEGACY_JAVA) return "legacy-java";
        if (mode == DECODER_MODE_READSB_CORE) return "readsb-core";
        return "native-fast";
    }

    private static final class ClientConnection {
        private final Socket socket;
        private final BufferedWriter writer;
        private final ArrayBlockingQueue<String> pendingLines = new ArrayBlockingQueue<>(MAX_PENDING_SBS_LINES);
        private final AtomicBoolean open = new AtomicBoolean(true);

        ClientConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
        }

        void start(ExecutorService executor, Runnable onClosed) {
            try {
                executor.execute(() -> writeLoop(onClosed));
            } catch (RejectedExecutionException rejected) {
                close();
                onClosed.run();
            }
        }

        boolean enqueue(String line) {
            return open.get() && pendingLines.offer(line);
        }

        private void writeLoop(Runnable onClosed) {
            try {
                while (open.get() || !pendingLines.isEmpty()) {
                    String line = pendingLines.poll(500L, TimeUnit.MILLISECONDS);
                    if (line == null) continue;
                    writer.write(line);
                    writer.flush();
                }
            } catch (IOException ignored) {
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                close();
                onClosed.run();
            }
        }

        void close() {
            if (!open.compareAndSet(true, false)) return;
            pendingLines.clear();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
