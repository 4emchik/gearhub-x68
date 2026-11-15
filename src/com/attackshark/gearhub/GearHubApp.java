package com.attackshark.gearhub;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Installs a crash handler that survives the Activity and stores the trace in
 * the app's private files directory.  CrashActivity runs in its own process and
 * can read that file even after the main process dies.
 */
public class GearHubApp extends Application {

    static final String CRASH_FILE = "crash.txt";
    static final String STARTUP_FILE = "startup.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Context app = this;
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                String trace = Log.getStackTraceString(error);
                write(app, CRASH_FILE, trace);
                append(app, STARTUP_FILE, "CRASH " + thread.getName() + ": " + error + "\n");
                Intent intent = new Intent(app, CrashActivity.class);
                intent.putExtra(CrashActivity.EXTRA_TRACE, trace);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                app.startActivity(intent);
                // Give the system a moment to start the crash activity in its
                // own process before this one is killed.
                Thread.sleep(600);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });

        mark(this, "application created");
    }

    static void mark(Context context, String step) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                .format(new Date()) + "  " + step + "\n";
        append(context, STARTUP_FILE, line);
    }

    static void append(Context context, String name, String text) {
        try (FileOutputStream out = context.openFileOutput(name, Context.MODE_APPEND)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    static void write(Context context, String name, String text) {
        try (FileOutputStream out = context.openFileOutput(name, Context.MODE_PRIVATE)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    static String read(Context context, String name) {
        try (FileInputStream in = context.openFileInput(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
