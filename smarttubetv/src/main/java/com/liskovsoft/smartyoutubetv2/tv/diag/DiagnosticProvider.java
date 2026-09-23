package com.liskovsoft.smartyoutubetv2.tv.diag;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * KIDS DIAGNOSTIC BUILD.
 *
 * A ContentProvider runs BEFORE Application.onCreate, so this is the earliest place
 * we can install a global crash handler and start logging boot breadcrumbs.
 *
 * Everything is written to a file in the app's external files dir AND kept in memory.
 * On any uncaught exception, or if the UI never shows up (hang/black screen),
 * {@link DiagnosticActivity} is launched to print the collected info ON SCREEN.
 *
 * NOTE: This whole class is temporary and should be removed once the black screen is fixed.
 */
public class DiagnosticProvider extends ContentProvider {
    public static final String BREADCRUMB_FILE = "kids_diag_breadcrumbs.txt";

    private static final List<String> sCrumbs = new ArrayList<>();
    private static volatile boolean sUiShown = false;
    private static final long UI_WATCHDOG_MS = 9_000;

    public static void crumb(String msg) {
        String line = time() + "  " + msg;
        synchronized (sCrumbs) {
            sCrumbs.add(line);
        }
        persist(line);
    }

    public static void markUiShown() {
        sUiShown = true;
        crumb("UI SHOWN (BrowseActivity/Splash visible)");
    }

    public static String dump() {
        synchronized (sCrumbs) {
            StringBuilder sb = new StringBuilder();
            for (String c : sCrumbs) {
                sb.append(c).append('\n');
            }
            return sb.toString();
        }
    }

    private static String time() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static void persist(String line) {
        try {
            Context ctx = sContextRef;
            if (ctx == null) return;
            File f = new File(ctx.getExternalFilesDir(null), BREADCRUMB_FILE);
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((line + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private static Context sContextRef;

    @Override
    public boolean onCreate() {
        sContextRef = getContext() != null ? getContext().getApplicationContext() : null;
        // Fresh log each launch
        try {
            if (sContextRef != null) {
                File f = new File(sContextRef.getExternalFilesDir(null), BREADCRUMB_FILE);
                if (f.exists()) f.delete();
            }
        } catch (Throwable ignored) {
        }

        crumb("=== DiagnosticProvider.onCreate (BEFORE Application.onCreate) ===");
        crumb("Device: " + android.os.Build.MODEL + " / " + android.os.Build.MANUFACTURER
                + " / Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");

        installCrashHandler();
        startUiWatchdog();

        return true;
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                String trace = "FATAL EXCEPTION on thread [" + thread.getName() + "]:\n" + sw;

                crumb("!!! UNCAUGHT EXCEPTION !!!\n" + trace);
                showDiagScreen(trace);
            } catch (Throwable ignored) {
            }

            if (prev != null) {
                prev.uncaughtException(thread, ex);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private void startUiWatchdog() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!sUiShown) {
                String msg = "UI NEVER SHOWED UP within " + (UI_WATCHDOG_MS / 1000)
                        + "s -> black screen / hang.\n\nLast breadcrumbs:\n" + dump();
                crumb("!!! WATCHDOG: UI not shown !!!");
                showDiagScreen(msg);
            }
        }, UI_WATCHDOG_MS);
    }

    private void showDiagScreen(String message) {
        try {
            Context ctx = sContextRef;
            if (ctx == null) return;
            Intent i = new Intent(ctx, DiagnosticActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            i.putExtra(DiagnosticActivity.EXTRA_MESSAGE, message);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    // --- unused ContentProvider stubs ---
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
