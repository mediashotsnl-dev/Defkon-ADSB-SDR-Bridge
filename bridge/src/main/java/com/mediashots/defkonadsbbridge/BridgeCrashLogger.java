package com.mediashots.defkonadsbbridge;

import android.content.Context;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicBoolean;

final class BridgeCrashLogger {
    private static final String TAG = "DefkonAdsbBridge";
    private static final String PREFS = "bridge_diagnostics";
    private static final String LAST_CRASH = "last_crash";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private BridgeCrashLogger() {
    }

    static void install(Context context) {
        if (!INSTALLED.compareAndSet(false, true)) return;

        Context applicationContext = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            saveCrash(applicationContext, thread, error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private static void saveCrash(Context context, Thread thread, Throwable error) {
        try {
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "BRIDGE FATAL CRASH thread=" + thread.getName(), error);
            }
            StringWriter writer = new StringWriter();
            PrintWriter printWriter = new PrintWriter(writer);
            printWriter.println("Thread: " + thread.getName());
            printWriter.println("Time: " + System.currentTimeMillis());
            error.printStackTrace(printWriter);
            printWriter.flush();
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(LAST_CRASH, writer.toString())
                .apply();
        } catch (RuntimeException ignored) {
        }
    }
}
