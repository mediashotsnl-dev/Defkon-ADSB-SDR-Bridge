package com.mediashots.defkonadsbbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String TAG = "DefkonAdsbBridge";
    private static final String USB_PERMISSION_ACTION = "com.mediashots.defkonadsbbridge.USB_PERMISSION";
    private static final String SOURCE_CODE_URL = BuildConfig.BRIDGE_SOURCE_URL;
    private static final String DEFKON_PACKAGE = "com.mediashots.defkoniv";
    private static final String DEFKON_ACTION_USE_ADSB_SDR = "com.mediashots.defkoniv.action.USE_ADSB_SDR";
    private static final String DEFKON_EXTRA_USE_ADSB_SDR = "com.mediashots.defkoniv.extra.USE_ADSB_SDR";
    private static final String PREFS = "bridge_diagnostics";
    private static final String LAST_CRASH = "last_crash";
    private static final String PREF_DECODER_MODE = "decoder_mode";
    private static final String PREF_ECO_MODE = "eco_mode";
    private static final String PREF_DECODER_DEFAULT_REV = "decoder_default_rev";
    private static final int DECODER_DEFAULT_REV_READSB_CORE = 3;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> logLines = new ArrayList<>();

    private UsbManager usbManager;
    private TextView mainStatus;
    private TextView dongleStatus;
    private TextView driverStatus;
    private TextView serverStatus;
    private TextView clientStatus;
    private TextView decoderStatus;
    private TextView logView;
    private Button primaryAction;
    private Button normalReadsbButton;
    private Button ecoReadsbButton;
    private Button advancedToggle;
    private LinearLayout advancedControls;
    private boolean receiverRegistered;
    private boolean serviceStatusReceiverRegistered;
    private boolean advancedVisible;
    private boolean advancedUnlocked;
    private boolean ecoMode;
    private boolean bridgeRunning;
    private long lastDefkonLaunchMs;
    private long lastCustomerFlowStartMs;
    private boolean defkonLaunchRequested;
    private boolean customerFlowQueued;
    private final Runnable customerFlowRunnable = new Runnable() {
        @Override
        public void run() {
            customerFlowQueued = false;
            startCustomerFlow();
        }
    };
    private final Runnable revealAdvancedRunnable = new Runnable() {
        @Override
        public void run() {
            revealAdvancedControls();
        }
    };

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (USB_PERMISSION_ACTION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    appendLog("USB PERMISSION GRANTED");
                    defkonLaunchRequested = true;
                    queueCustomerFlow(250L);
                } else {
                    setText(mainStatus, "STATUS | USB ACCESS DENIED");
                    setPrimaryButtonState("ALLOW USB", false);
                    setText(decoderStatus, "SIGNAL | USB PERMISSION DENIED");
                    appendLog("USB PERMISSION DENIED");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                onUsbDeviceAttached(device);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                onUsbDeviceDetached(device);
            }
        }
    };

    private final BroadcastReceiver serviceStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BridgeService.ACTION_STATUS.equals(intent.getAction())) return;
            String server = intent.getStringExtra(BridgeService.EXTRA_SERVER);
            int clients = intent.getIntExtra(BridgeService.EXTRA_CLIENTS, 0);
            String log = intent.getStringExtra(BridgeService.EXTRA_LOG);
            bridgeRunning = intent.getBooleanExtra(BridgeService.EXTRA_SDR_RUNNING, bridgeRunning);
            boolean serviceEcoMode = intent.getBooleanExtra(BridgeService.EXTRA_ECO_MODE, ecoMode);
            if (serviceEcoMode != ecoMode) {
                ecoMode = serviceEcoMode;
                persistReadsbProfile();
                updateReadsbProfileButtons();
            }
            if (server != null) updateServerStatus(server);
            setText(clientStatus, clients > 0 ? "DEFKON | CONNECTED" : "DEFKON | WAITING");
            if (log != null && !log.trim().isEmpty()) {
                appendLog(log);
                updateMainStatusFromLog(log);
                if (log.startsWith("SDR ") || log.startsWith("ADSB ") ||
                    log.startsWith("NATIVE ") || log.startsWith("READSB ")) {
                    setText(decoderStatus, "SIGNAL | " + friendlySignalStatus(log));
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BridgeCrashLogger.install(this);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ecoMode = prefs.getBoolean(PREF_ECO_MODE, false);
        prefs.edit()
            .putInt(PREF_DECODER_MODE, BridgeService.DECODER_MODE_READSB_CORE)
            .putInt(PREF_DECODER_DEFAULT_REV, DECODER_DEFAULT_REV_READSB_CORE)
            .apply();
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
        registerUsbReceiver();
        refreshSdrDriverStatus();
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent() != null ? getIntent().getAction() : null)) {
            defkonLaunchRequested = true;
            queueCustomerFlow(250L);
        } else {
            scanUsbDevice();
        }
        showLastCrashIfPresent();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerServiceStatusReceiver();
        startBridgeService(BridgeService.ACTION_START);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSdrDriverStatus();
        mainHandler.postDelayed(this::scanUsbDevice, 250L);
    }

    @Override
    protected void onStop() {
        unregisterServiceStatusReceiver();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterUsbReceiver();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void registerUsbReceiver() {
        if (receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(USB_PERMISSION_ACTION);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerInternalReceiver(usbPermissionReceiver, filter);
            receiverRegistered = true;
        } catch (RuntimeException error) {
            appendLog("USB RECEIVER ERROR " + error.getClass().getSimpleName());
        }
    }

    private void unregisterUsbReceiver() {
        if (!receiverRegistered) return;
        try {
            unregisterReceiver(usbPermissionReceiver);
        } catch (RuntimeException ignored) {
        } finally {
            receiverRegistered = false;
        }
    }

    private void registerServiceStatusReceiver() {
        if (serviceStatusReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(BridgeService.ACTION_STATUS);
            registerInternalReceiver(serviceStatusReceiver, filter);
            serviceStatusReceiverRegistered = true;
        } catch (RuntimeException error) {
            appendLog("STATUS RECEIVER ERROR " + error.getClass().getSimpleName());
        }
    }

    private void registerInternalReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    private void unregisterServiceStatusReceiver() {
        if (!serviceStatusReceiverRegistered) return;
        try {
            unregisterReceiver(serviceStatusReceiver);
        } catch (RuntimeException ignored) {
        } finally {
            serviceStatusReceiverRegistered = false;
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1090);
    }

    private View buildUi() {
        int background = Color.rgb(5, 8, 7);
        int panel = Color.rgb(11, 23, 17);
        int text = Color.rgb(216, 255, 224);
        int accent = Color.rgb(69, 255, 105);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(background);
        frame.addView(new GridBackgroundView(this));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        root.setGravity(Gravity.CENTER);
        scroll.addView(root, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.MATCH_PARENT
        ));

        AccessibleTextView title = textView("DEFKON ADSB SDR BRIDGE", 22, accent, true);
        title.setOnTouchListener((view, event) -> {
            if (advancedUnlocked) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                mainHandler.postDelayed(revealAdvancedRunnable, 5_000L);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP ||
                event.getAction() == MotionEvent.ACTION_CANCEL) {
                mainHandler.removeCallbacks(revealAdvancedRunnable);
                if (event.getAction() == MotionEvent.ACTION_UP) view.performClick();
                return true;
            }
            return true;
        });
        root.addView(title);
        root.addView(textView("Local SBS/BaseStation output for compatible clients", 12, text, false));

        mainStatus = statusRow("STATUS", "CHECKING");
        dongleStatus = statusRow("DONGLE", "SCAN");
        driverStatus = statusRow("RTL-SDR DRIVER", "CHECK");
        serverStatus = statusRow("BRIDGE", "STARTING");
        clientStatus = statusRow("DEFKON", "WAITING");
        decoderStatus = statusRow("SIGNAL", "WAITING");

        root.addView(card(mainStatus, panel));

        primaryAction = button("START ADS-B", accent, background);
        primaryAction.setOnClickListener(v -> {
            defkonLaunchRequested = true;
            setPrimaryButtonState("STARTING READSB...", true);
            startCustomerFlow();
        });

        root.addView(card(dongleStatus, panel));
        root.addView(card(serverStatus, panel));
        root.addView(card(clientStatus, panel));
        root.addView(card(decoderStatus, panel));

        root.addView(primaryAction);

        Button closeBridge = button("STOP ADS-B AND EXIT BRIDGE", accent, background);
        closeBridge.setOnClickListener(v -> closeBridgeApp());
        root.addView(closeBridge);

        root.addView(sectionHeader("READSB CORE PROFILE", accent));

        normalReadsbButton = button("NORMAL READSB CORE", accent, background);
        normalReadsbButton.setOnClickListener(v -> selectReadsbProfile(false));
        root.addView(normalReadsbButton);

        ecoReadsbButton = button("ECO READSB CORE", accent, background);
        ecoReadsbButton.setOnClickListener(v -> selectReadsbProfile(true));
        root.addView(ecoReadsbButton);

        TextView profileInfo = textView(
            "Normal: fastest status updates. Eco: lower app overhead with the same 2.4 MS/s reception.",
            11,
            text,
            false
        );
        root.addView(profileInfo);
        updateReadsbProfileButtons();

        AccessibleTextView openSource = textView("Open source and licenses", 10, accent, false);
        openSource.setGravity(Gravity.CENTER);
        openSource.setPaintFlags(openSource.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        openSource.setClickable(true);
        openSource.setFocusable(true);
        openSource.setPadding(12, 22, 12, 12);
        openSource.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.setAlpha(0.45f);
            } else if (event.getAction() == MotionEvent.ACTION_UP ||
                event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.setAlpha(1f);
            }
            return false;
        });
        openSource.setOnClickListener(v -> showOpenSourceInfo());
        root.addView(openSource);

        advancedToggle = button("ADVANCED", accent, background);
        advancedToggle.setOnClickListener(v -> toggleAdvanced());
        advancedToggle.setVisibility(View.GONE);
        root.addView(advancedToggle);

        advancedControls = new LinearLayout(this);
        advancedControls.setOrientation(LinearLayout.VERTICAL);
        advancedControls.setGravity(Gravity.CENTER_HORIZONTAL);
        advancedControls.setVisibility(View.GONE);
        advancedControls.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        root.addView(advancedControls);

        advancedControls.addView(sectionHeader("USB SETUP", accent));
        advancedControls.addView(card(driverStatus, panel));

        Button scan = button("SCAN USB", accent, background);
        scan.setOnClickListener(v -> scanUsbDevice());
        advancedControls.addView(scan);

        Button permission = button("REQUEST USB PERMISSION", accent, background);
        permission.setOnClickListener(v -> requestUsbPermission());
        advancedControls.addView(permission);

        advancedControls.addView(sectionHeader("READSB CORE", accent));

        Button nativeDriver = button("START READSB CORE", accent, background);
        nativeDriver.setOnClickListener(v -> startBridgeService(BridgeService.ACTION_START_NATIVE_SDR));
        advancedControls.addView(nativeDriver);

        advancedControls.addView(sectionHeader("DIAGNOSTICS", accent));

        Button openJsonFeed = button("OPEN AIRCRAFT.JSON", accent, background);
        openJsonFeed.setOnClickListener(v -> openAircraftJsonFeed());
        advancedControls.addView(openJsonFeed);

        Button test = button("SEND TEST SBS TARGET", accent, background);
        test.setOnClickListener(v -> startBridgeService(BridgeService.ACTION_SEND_TEST));
        advancedControls.addView(test);

        logView = textView("", 11, text, false);
        advancedControls.addView(card(logView, panel));

        advancedControls.addView(sectionHeader("SERVICE CONTROL", accent));

        Button stop = button("STOP BACKGROUND BRIDGE", accent, background);
        stop.setOnClickListener(v -> startBridgeService(BridgeService.ACTION_STOP));
        advancedControls.addView(stop);

        frame.addView(scroll, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        return frame;
    }

    private void showOpenSourceInfo() {
        new AlertDialog.Builder(this)
            .setTitle("OPEN SOURCE")
            .setMessage(
                "DEFKON ADSB SDR BRIDGE is licensed under the GNU General Public " +
                "License version 3 or later.\n\n" +
                "It includes readsb, rtl-sdr and libusb components. Complete " +
                "corresponding source code for this release is available at:\n\n" +
                SOURCE_CODE_URL
            )
            .setPositiveButton("SOURCE CODE", (dialog, which) -> openUrl(SOURCE_CODE_URL))
            .setNeutralButton("LICENSES", (dialog, which) -> showLicenseTexts())
            .setNegativeButton("CLOSE", null)
            .show();
    }

    private void showLicenseTexts() {
        String licenses = readLicenseAssets();
        new AlertDialog.Builder(this)
            .setTitle("OPEN SOURCE LICENSES")
            .setMessage(licenses)
            .setPositiveButton("SOURCE CODE", (dialog, which) -> openUrl(SOURCE_CODE_URL))
            .setNegativeButton("CLOSE", null)
            .show();
    }

    private String readLicenseAssets() {
        String[] assets = {
            "licenses/THIRD_PARTY_NOTICES.md",
            "licenses/GPL-3.0.txt",
            "licenses/GPL-2.0.txt",
            "licenses/LGPL-2.1.txt",
            "licenses/READSB-LICENSE.txt"
        };
        StringBuilder result = new StringBuilder();
        for (String asset : assets) {
            if (result.length() > 0) result.append("\n\n");
            result.append("===== ").append(asset.substring(asset.lastIndexOf('/') + 1)).append(" =====\n\n");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open(asset),
                StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line).append('\n');
                }
            } catch (IOException error) {
                result.append("License text unavailable in this package.\n")
                    .append("See ").append(SOURCE_CODE_URL);
            }
        }
        return result.toString();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException error) {
            appendLog("OPEN SOURCE LINK ERROR " + error.getClass().getSimpleName());
        }
    }

    private TextView statusRow(String label, String value) {
        TextView view = textView(label + " | " + value, 14, Color.rgb(216, 255, 224), true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private View card(View content, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(18, 14, 18, 14);
        box.setBackgroundColor(color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 16, 0, 0);
        box.setLayoutParams(params);
        box.addView(content);
        return box;
    }

    private TextView sectionHeader(String value, int color) {
        TextView view = textView(value, 12, color, true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 24, 0, 2);
        view.setLayoutParams(params);
        return view;
    }

    private AccessibleTextView textView(String value, int sp, int color, boolean bold) {
        AccessibleTextView view = new AccessibleTextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setTypeface(android.graphics.Typeface.MONOSPACE, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return view;
    }

    private Button button(String label, int accent, int background) {
        Button button = new Button(this);
        button.setText(label);
        int pressed = Color.rgb(35, 150, 65);
        int[][] textStates = new int[][]{
            new int[]{android.R.attr.state_pressed},
            new int[]{android.R.attr.state_selected},
            new int[]{}
        };
        int[] textColors = new int[]{Color.BLACK, Color.BLACK, accent};
        button.setTextColor(new ColorStateList(textStates, textColors));
        button.setTextSize(12);
        button.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        StateListDrawable states = new StateListDrawable();
        states.addState(
            new int[]{android.R.attr.state_pressed},
            buttonBackground(pressed, pressed, 2)
        );
        states.addState(
            new int[]{android.R.attr.state_selected},
            buttonBackground(accent, accent, 2)
        );
        states.addState(
            new int[]{},
            buttonBackground(background, accent, 2)
        );
        button.setBackground(states);
        button.setMinHeight(56);
        button.setPadding(18, 8, 18, 8);
        button.setStateListAnimator(null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 12, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private GradientDrawable buttonBackground(int fillColor, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setStroke(strokeWidth, strokeColor);
        drawable.setCornerRadius(10f);
        return drawable;
    }

    private void setPrimaryButtonState(String label, boolean active) {
        if (primaryAction == null) return;
        mainHandler.post(() -> {
            primaryAction.setText(label);
            primaryAction.setSelected(active);
        });
    }

    private void persistReadsbProfile() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putInt(PREF_DECODER_MODE, BridgeService.DECODER_MODE_READSB_CORE)
            .putBoolean(PREF_ECO_MODE, ecoMode)
            .apply();
    }

    private void selectReadsbProfile(boolean useEcoMode) {
        ecoMode = useEcoMode;
        persistReadsbProfile();
        updateReadsbProfileButtons();
        setText(decoderStatus, "SIGNAL | READSB " + (ecoMode ? "ECO" : "NORMAL") + " SELECTED");
        appendLog("READSB PROFILE " + (ecoMode ? "ECO" : "NORMAL"));
        if (bridgeRunning) {
            setPrimaryButtonState("APPLYING " + (ecoMode ? "ECO" : "NORMAL") + "...", true);
            startBridgeService(BridgeService.ACTION_START_NATIVE_SDR);
        }
    }

    private void updateReadsbProfileButtons() {
        if (normalReadsbButton != null) {
            normalReadsbButton.setSelected(!ecoMode);
            normalReadsbButton.setText(ecoMode ? "NORMAL READSB CORE" : "✓ NORMAL READSB CORE");
        }
        if (ecoReadsbButton != null) {
            ecoReadsbButton.setSelected(ecoMode);
            ecoReadsbButton.setText(ecoMode ? "✓ ECO READSB CORE" : "ECO READSB CORE");
        }
    }

    private void queueCustomerFlow(long delayMs) {
        if (customerFlowQueued) {
            appendLog("START ALREADY QUEUED");
            return;
        }
        customerFlowQueued = true;
        mainHandler.postDelayed(customerFlowRunnable, delayMs);
    }

    private void startCustomerFlow() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastCustomerFlowStartMs < 1500L) {
                appendLog("START IGNORED | ALREADY STARTING");
                return;
            }
            lastCustomerFlowStartMs = now;
            refreshSdrDriverStatus();
            if (usbManager == null) {
                setText(mainStatus, "STATUS | USB NOT AVAILABLE");
                setPrimaryButtonState("START ADS-B", false);
                appendLog("USB MANAGER NOT AVAILABLE");
                return;
            }

            UsbDevice device = firstRtlSdrDevice();
            if (device == null) {
                setText(mainStatus, "STATUS | CONNECT RTL-SDR");
                setPrimaryButtonState("START ADS-B", false);
                scanUsbDevice();
                return;
            }

            if (!usbManager.hasPermission(device)) {
                setText(mainStatus, "STATUS | ALLOW USB ACCESS");
                setPrimaryButtonState("ALLOW USB", true);
                requestUsbPermission();
                return;
            }

            setText(mainStatus, "STATUS | STARTING ADS-B");
            setPrimaryButtonState("STARTING " + (ecoMode ? "ECO" : "NORMAL") + " READSB...", true);
            if (nativeCoreReady()) {
                setText(decoderStatus, "SIGNAL | STARTING READSB " + (ecoMode ? "ECO" : "NORMAL"));
                startBridgeService(BridgeService.ACTION_START_NATIVE_SDR);
            } else {
                setText(mainStatus, "STATUS | READSB CORE NOT READY");
                setText(decoderStatus, "SIGNAL | READSB CORE ERROR");
                setPrimaryButtonState("START ADS-B", false);
                appendLog("READSB CORE NOT READY");
            }
        } catch (RuntimeException error) {
            setText(mainStatus, "STATUS | CHECK SETUP");
            setText(decoderStatus, "SIGNAL | START ERROR");
            setPrimaryButtonState("START ADS-B", false);
            appendLog("START ERROR " + error.getClass().getSimpleName());
        }
    }

    private void toggleAdvanced() {
        advancedVisible = !advancedVisible;
        if (advancedControls != null) {
            advancedControls.setVisibility(advancedVisible ? View.VISIBLE : View.GONE);
        }
        setText(advancedToggle, advancedVisible ? "HIDE ADVANCED" : "ADVANCED");
    }

    private void revealAdvancedControls() {
        advancedUnlocked = true;
        if (advancedToggle != null) {
            advancedToggle.setVisibility(View.VISIBLE);
        }
        appendLog("ADVANCED UNLOCKED");
    }

    private void closeBridgeApp() {
        appendLog("STOP ADS-B");
        startBridgeService(BridgeService.ACTION_STOP);
        finishAndRemoveTask();
    }

    private void openAircraftJsonFeed() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:5051/aircraft.json"));
            startActivity(intent);
        } catch (RuntimeException error) {
            appendLog("OPEN JSON ERROR " + error.getClass().getSimpleName());
        }
    }

    private void onUsbDeviceAttached(UsbDevice device) {
        if (device != null && !isLikelyRtlSdr(device.getVendorId(), device.getProductId())) return;
        defkonLaunchRequested = true;
        setText(mainStatus, "STATUS | RTL-SDR CONNECTED");
        setPrimaryButtonState("STARTING READSB...", true);
        setText(dongleStatus, "DONGLE | DETECTED");
        setText(decoderStatus, "SIGNAL | STARTING READSB");
        appendLog("USB RTL-SDR ATTACHED");
        queueCustomerFlow(500L);
    }

    private void onUsbDeviceDetached(UsbDevice device) {
        if (device != null && !isLikelyRtlSdr(device.getVendorId(), device.getProductId())) return;
        startBridgeService(BridgeService.ACTION_USB_DETACHED);
        setText(mainStatus, "STATUS | CONNECT RTL-SDR");
        setPrimaryButtonState("WAITING FOR RTL-SDR", false);
        setText(dongleStatus, "DONGLE | NOT CONNECTED");
        setText(decoderStatus, "SIGNAL | WAIT DONGLE");
        appendLog("USB RTL-SDR DETACHED");
        mainHandler.postDelayed(this::scanUsbDevice, 350L);
    }

    private void scanUsbDevice() {
        try {
            refreshSdrDriverStatus();
            if (usbManager == null) {
                setText(mainStatus, "STATUS | USB NOT AVAILABLE");
                setText(dongleStatus, "DONGLE | USB MANAGER N/A");
                setPrimaryButtonState("START ADS-B", false);
                return;
            }

            UsbDevice device = firstRtlSdrDevice();
            if (device == null) {
                setText(mainStatus, "STATUS | CONNECT RTL-SDR");
                setPrimaryButtonState("START ADS-B", false);
                setText(dongleStatus, "DONGLE | NOT CONNECTED");
                setText(decoderStatus, "SIGNAL | WAIT DONGLE");
                appendLog("NO RTL-SDR DEVICE FOUND");
                return;
            }

            String label = deviceLabel(device);
            if (usbManager.hasPermission(device)) {
                setText(mainStatus, bridgeRunning ? "STATUS | LISTENING 1090 MHZ" : "STATUS | READY");
                setPrimaryButtonState(bridgeRunning ? "RUNNING READSB" : "START ADS-B", bridgeRunning);
                setText(dongleStatus, "DONGLE | READY " + label);
                appendLog("RTL-SDR READY " + label);
                setText(
                    decoderStatus,
                    nativeCoreReady()
                        ? "SIGNAL | READSB " + (ecoMode ? "ECO" : "NORMAL") + " READY"
                        : "SIGNAL | READSB CORE ERROR"
                );
                if (defkonLaunchRequested && !customerFlowQueued) {
                    queueCustomerFlow(250L);
                }
            } else {
                setText(mainStatus, "STATUS | ALLOW USB ACCESS");
                setPrimaryButtonState("ALLOW USB", false);
                setText(dongleStatus, "DONGLE | NEED PERMISSION " + label);
                setText(decoderStatus, "SIGNAL | USB PERMISSION");
                appendLog("RTL-SDR NEEDS USB PERMISSION " + label);
            }
        } catch (RuntimeException error) {
            setText(mainStatus, "STATUS | USB ERROR");
            setPrimaryButtonState("START ADS-B", false);
            setText(dongleStatus, "DONGLE | SCAN ERROR");
            setText(decoderStatus, "SIGNAL | USB ERROR");
            appendLog("USB SCAN ERROR " + error.getClass().getSimpleName());
        }
    }

    private void requestUsbPermission() {
        try {
            UsbDevice device = firstRtlSdrDevice();
            if (device == null || usbManager == null) {
                scanUsbDevice();
                return;
            }

            PendingIntent intent = PendingIntent.getBroadcast(
                this,
                0,
                new Intent(USB_PERMISSION_ACTION).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            usbManager.requestPermission(device, intent);
            setText(mainStatus, "STATUS | ALLOW USB ACCESS");
            setPrimaryButtonState("ALLOW USB", false);
            appendLog("USB PERMISSION REQUESTED");
        } catch (RuntimeException error) {
            setText(mainStatus, "STATUS | USB PERMISSION ERROR");
            appendLog("USB PERMISSION ERROR " + error.getClass().getSimpleName());
        }
    }

    private void refreshSdrDriverStatus() {
        String nativeStatus = NativeRtlSdrDriver.nativeStatus();
        String prefix = nativeCoreReady() ? "READSB CORE | READY" : "READSB CORE | NOT READY";
        setText(driverStatus, prefix + " | " + nativeStatus);
    }

    private boolean nativeCoreReady() {
        return NativeRtlSdrDriver.isCoreLinked();
    }

    private void launchDefkonSdrMode() {
        try {
            if (!defkonLaunchRequested) return;
            long now = System.currentTimeMillis();
            if (now - lastDefkonLaunchMs < 4000L) return;
            lastDefkonLaunchMs = now;
            defkonLaunchRequested = false;
            Intent intent = getPackageManager().getLaunchIntentForPackage(DEFKON_PACKAGE);
            if (intent == null) {
                appendLog("DEFKON APP NOT FOUND");
                return;
            }
            intent.setAction(DEFKON_ACTION_USE_ADSB_SDR);
            intent.putExtra(DEFKON_EXTRA_USE_ADSB_SDR, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            appendLog("OPEN DEFKON | ADS-B SDR MODE");
        } catch (RuntimeException error) {
            appendLog("OPEN DEFKON ERROR " + error.getClass().getSimpleName());
        }
    }

    private UsbDevice firstRtlSdrDevice() {
        if (usbManager == null) return null;
        for (Map.Entry<String, UsbDevice> entry : usbManager.getDeviceList().entrySet()) {
            UsbDevice device = entry.getValue();
            if (isLikelyRtlSdr(device.getVendorId(), device.getProductId())) {
                return device;
            }
        }
        return null;
    }

    private boolean isLikelyRtlSdr(int vendorId, int productId) {
        return vendorId == 0x0BDA && (productId == 0x2830 || productId == 0x2831 || productId == 0x2832 || productId == 0x2838);
    }

    private String deviceLabel(UsbDevice device) {
        String name = null;
        try {
            name = device.getProductName();
        } catch (RuntimeException ignored) {
        }
        if (name == null || name.trim().isEmpty()) name = device.getDeviceName();
        return name + " " + String.format(Locale.US, "%04X:%04X", device.getVendorId(), device.getProductId());
    }

    private void startBridgeService(String action) {
        try {
            Intent intent = new Intent(this, BridgeService.class)
                .setAction(action)
                .putExtra(BridgeService.EXTRA_DECODER_MODE, BridgeService.DECODER_MODE_READSB_CORE)
                .putExtra(BridgeService.EXTRA_ECO_MODE, ecoMode);
            // Activity actions originate while the UI is visible. The service promotes itself
            // only after it has permission to a real USB SDR device.
            startService(intent);
        } catch (RuntimeException error) {
            appendLog("SERVICE START ERROR " + error.getClass().getSimpleName());
        }
    }

    private void setText(TextView view, String text) {
        if (view == null) return;
        mainHandler.post(() -> view.setText(text));
    }

    private void updateServerStatus(String server) {
        if (server.contains("SBS")) {
            setText(serverStatus, "BRIDGE | READY");
        } else if (server.contains("ERROR")) {
            setText(serverStatus, "BRIDGE | ERROR");
            setText(mainStatus, "STATUS | CHECK SETUP");
        } else if (server.contains("STOPPED")) {
            setText(serverStatus, "BRIDGE | STOPPED");
            setText(mainStatus, "STATUS | STOPPED");
            bridgeRunning = false;
            setPrimaryButtonState("START ADS-B", false);
        } else {
            setText(serverStatus, "BRIDGE | STARTING");
        }
    }

    private void updateMainStatusFromLog(String log) {
        String status = log.toUpperCase(Locale.US);
        if (status.startsWith("ADSB FRAMES")) {
            setText(mainStatus, "STATUS | RECEIVING ADS-B");
            setPrimaryButtonState("RUNNING READSB", true);
            launchDefkonSdrMode();
        } else if (status.contains("CLIENT CONNECTED")) {
            setText(mainStatus, "STATUS | DEFKON CONNECTED");
            setText(clientStatus, "DEFKON | CONNECTED");
            setPrimaryButtonState("RUNNING READSB", true);
        } else if (status.contains("SBS SERVER READY")) {
            setText(mainStatus, "STATUS | READY FOR DEFKON");
        } else if (status.contains("TUNED") || status.contains("SDR READER ALREADY RUNNING")) {
            setText(mainStatus, "STATUS | LISTENING 1090 MHZ");
            setPrimaryButtonState("RUNNING READSB", true);
            launchDefkonSdrMode();
        } else if (status.contains("USB LOST") || status.contains("READ STOP") || status.contains("RESTART")) {
            setText(mainStatus, "STATUS | RECONNECTING SDR");
            setPrimaryButtonState("RECONNECTING READSB...", true);
        } else if (status.contains("WAIT RTL-SDR") || status.contains("WAIT DONGLE")) {
            setText(mainStatus, "STATUS | CONNECT RTL-SDR");
            setPrimaryButtonState("WAITING FOR RTL-SDR", false);
        } else if (status.contains("USB PERMISSION")) {
            setText(mainStatus, "STATUS | ALLOW USB ACCESS");
            setPrimaryButtonState("ALLOW USB", false);
        } else if (status.contains("STOPPED")) {
            setText(mainStatus, "STATUS | STOPPED");
            setPrimaryButtonState("START ADS-B", false);
        } else if (status.contains("ERROR") || status.contains("FAILED")) {
            setText(mainStatus, "STATUS | CHECK SETUP");
            setPrimaryButtonState("START ADS-B", false);
        }
    }

    private String friendlySignalStatus(String log) {
        String status = log.toUpperCase(Locale.US);
        if (status.startsWith("ADSB FRAMES")) return "RECEIVING ADS-B";
        if (status.contains("USB LOST") || status.contains("READ STOP") || status.contains("RESTART")) return "RECONNECTING SDR";
        if (status.contains("WAIT RTL-SDR") || status.contains("WAIT DONGLE")) return "WAIT DONGLE";
        if (status.contains("USB PERMISSION")) return "USB PERMISSION";
        if (status.contains("TUNED")) return "LISTENING 1090 MHZ";
        if (status.contains("ALREADY RUNNING")) return "RUNNING";
        if (status.contains("ERROR") || status.contains("FAILED")) return "CHECK SETUP";
        return log;
    }

    private void appendLog(String line) {
        if (BuildConfig.DEBUG && line != null && !line.trim().isEmpty()) {
            Log.w(TAG, "UI " + line);
        }
        mainHandler.post(() -> {
            if (logView == null) return;
            logLines.add(0, line);
            while (logLines.size() > 14) {
                logLines.remove(logLines.size() - 1);
            }
            StringBuilder builder = new StringBuilder();
            for (String item : logLines) {
                builder.append(item).append('\n');
            }
            logView.setText(builder.toString());
        });
    }

    private void showLastCrashIfPresent() {
        String crash = lastCrashLog();
        if (crash == null || crash.trim().isEmpty()) {
            appendLog("NO STORED CRASH LOG");
            return;
        }

        String firstLine = crash.split("\\n", 2)[0];
        appendLog("LAST CRASH STORED");
        appendLog(firstLine);
    }

    private String lastCrashLog() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getString(LAST_CRASH, "");
    }

    private static final class AccessibleTextView extends TextView {
        AccessibleTextView(Context context) {
            super(context);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private static final class GridBackgroundView extends View {
        private final Paint finePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint boldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Bitmap bridgeLogo;

        GridBackgroundView(Context context) {
            super(context);
            finePaint.setColor(Color.argb(30, 69, 255, 105));
            finePaint.setStrokeWidth(1f);
            boldPaint.setColor(Color.argb(44, 69, 255, 105));
            boldPaint.setStrokeWidth(2f);
            logoPaint.setAlpha(34);
            bridgeLogo = BitmapFactory.decodeResource(context.getResources(), R.drawable.defkon_bridge);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            float step = 42f;

            for (float x = 0; x <= width; x += step) {
                canvas.drawLine(x, 0, x, height, finePaint);
            }
            for (float y = 0; y <= height; y += step) {
                canvas.drawLine(0, y, width, y, finePaint);
            }
            for (float x = 0; x <= width; x += step * 4f) {
                canvas.drawLine(x, 0, x, height, boldPaint);
            }
            for (float y = 0; y <= height; y += step * 4f) {
                canvas.drawLine(0, y, width, y, boldPaint);
            }

            if (bridgeLogo == null) {
                return;
            }
            float logoSize = Math.min(width * 0.90f, height * 0.62f);
            float left = (width - logoSize) / 2f;
            float top = (height - logoSize) / 2f;
            canvas.drawBitmap(bridgeLogo, null, new RectF(left, top, left + logoSize, top + logoSize), logoPaint);
        }
    }
}
