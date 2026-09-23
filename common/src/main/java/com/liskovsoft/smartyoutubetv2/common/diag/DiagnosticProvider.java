package com.liskovsoft.smartyoutubetv2.common.diag;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;

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
 * KIDS DIAGNOSTIC BUILD (temporary — remove after the black screen is fixed).
 *
 * A ContentProvider's onCreate runs BEFORE Application.onCreate, so this is the
 * earliest place to install a global crash handler and start boot breadcrumbs.
 *
 * - Every breadcrumb is kept in memory AND appended to a file.
 * - On any uncaught exception: the stack trace is shown FULLSCREEN on the TV
 *   and the process is kept alive (diagnostic build only).
 * - If the UI never becomes visible within UI_WATCHDOG_MS, the watchdog
 *   (running on its OWN thread, immune to a blocked main thread) shows the
 *   breadcrumbs on screen.
 *
 * The user photographs the yellow-on-black screen — no adb needed.
 */
public class DiagnosticProvider extends ContentProvider {
    public static final String BREADCRUMB_FILE = "kids_diag_breadcrumbs.txt";

    private static final List<String> sCrumbs = new ArrayList<>();
    private static volatile boolean sUiShown = false;
    private static volatile boolean sDiagScreenShown = false;
    private static final long UI_WATCHDOG_MS = 10_000;

    private static Context sContextRef;

    public static void crumb(String msg) {
        String line = time() + "  " + msg;
        synchronized (sCrumbs) {
            sCrumbs.add(line);
        }
        persist(line);
    }

    public static void markUiShown() {
        sUiShown = true;
        crumb("UI SHOWN (main screen visible)");
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
        startUiWatchdogOnOwnThread();

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

                // DIAG BUILD: show the error on screen and KEEP THE PROCESS ALIVE
                // (normally we'd forward to prev/kill, but then the user sees nothing)
                showDiagScreen(trace);
                return; // do not kill: the diag screen must stay visible
            } catch (Throwable ignored) {
            }

            // Fallback: only if showing the screen failed
            if (prev != null) {
                prev.uncaughtException(thread, ex);
            }
        });
    }

    /**
     * Watchdog on a dedicated thread: even if the main thread is blocked
     * (hang/black screen), this still fires and shows the breadcrumbs.
     */
    private void startUiWatchdogOnOwnThread() {
        HandlerThread thread = new HandlerThread("kids-diag-watchdog");
        thread.start();
        new Handler(thread.getLooper()).postDelayed(() -> {
            if (!sUiShown) {
                String msg = "UI NEVER SHOWED UP within " + (UI_WATCHDOG_MS / 1000)
                        + "s -> black screen / hang.\n\nBreadcrumbs:\n" + dump();
                crumb("!!! WATCHDOG: UI not shown within " + (UI_WATCHDOG_MS / 1000) + "s !!!");
                showDiagScreen(msg);
            } else {
                thread.quit();
            }
        }, UI_WATCHDOG_MS);
    }

    private void showDiagScreen(String message) {
        if (sDiagScreenShown) {
            return;
        }
        sDiagScreenShown = true;

        try {
            Context ctx = sContextRef;
            if (ctx == null) return;
            Intent i = new Intent(ctx, DiagnosticActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            i.putExtra(DiagnosticActivity.EXTRA_MESSAGE, message);
            ctx.startActivity(i);
        } catch (Throwable t) {
            try {
                crumb("showDiagScreen FAILED: " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    // --- unused ContentProvider stubs ---
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
