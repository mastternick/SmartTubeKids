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
 * KIDS DIAGNOSTIC BUILD v2 (temporary — remove after the black screen is fixed).
 *
 * v1 flaw: when the MAIN thread crashed, the process died before the diagnostic
 * activity could be drawn -> user saw nothing but black.
 *
 * v2 design:
 *  - Crash handler installed from MainApplication.attachBaseContext (earliest Java point)
 *    AND from this provider (belt and suspenders).
 *  - Breadcrumbs are persisted to a file, so they survive process death.
 *  - The diagnostic activity runs in a SEPARATE process (":diag" in the manifest).
 *    Even if the main process is dead or its main thread is blocked, the diag
 *    process draws the error/breadcrumbs on screen.
 *  - A watchdog on its own thread shows the breadcrumbs if the UI never appears.
 */
public class DiagnosticProvider extends ContentProvider {
    public static final String BREADCRUMB_FILE = "kids_diag_breadcrumbs.txt";
    public static final String CRASH_FILE = "kids_diag_crash.txt";

    private static final List<String> sCrumbs = new ArrayList<>();
    private static volatile boolean sUiShown = false;
    private static volatile boolean sDiagScreenRequested = false;
    private static volatile boolean sHandlerInstalled = false;
    private static final long UI_WATCHDOG_MS = 10_000;

    private static Context sContextRef;

    /**
     * Called from MainApplication.attachBaseContext — the earliest Java code that runs.
     */
    public static synchronized void installEarly(Context context) {
        if (context != null) {
            sContextRef = context.getApplicationContext();
        }

        if (sHandlerInstalled) {
            return;
        }
        sHandlerInstalled = true;

        // Fresh logs each launch
        deleteFile(BREADCRUMB_FILE);
        deleteFile(CRASH_FILE);

        crumb("installEarly (attachBaseContext) OK");
        installCrashHandler();
    }

    public static void crumb(String msg) {
        String line = time() + "  " + msg;
        synchronized (sCrumbs) {
            sCrumbs.add(line);
        }
        appendFile(BREADCRUMB_FILE, line);
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

    private static void appendFile(String name, String line) {
        try {
            Context ctx = sContextRef;
            if (ctx == null) return;
            File f = new File(ctx.getExternalFilesDir(null), name);
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((line + "\n").getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private static void deleteFile(String name) {
        try {
            Context ctx = sContextRef;
            if (ctx == null) return;
            File f = new File(ctx.getExternalFilesDir(null), name);
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                String trace = "FATAL EXCEPTION on thread [" + thread.getName() + "]:\n" + sw;

                // Persist FIRST (survives process death)
                appendFile(CRASH_FILE, trace);
                crumb("!!! UNCAUGHT EXCEPTION (see crash file) !!!");

                // Show on screen from the separate :diag process
                showDiagScreen(trace);

                // Give the system time to spawn/draw the diag process
                Thread.sleep(2_000);
                return; // DIAG BUILD: don't forward — keep the scene visible
            } catch (Throwable ignored) {
            }

            if (prev != null) {
                prev.uncaughtException(thread, ex);
            }
        });
    }

    @Override
    public boolean onCreate() {
        if (sContextRef == null) {
            sContextRef = getContext() != null ? getContext().getApplicationContext() : null;
        }
        installEarly(sContextRef);

        crumb("=== DiagnosticProvider.onCreate ===");
        crumb("Device: " + android.os.Build.MODEL + " / " + android.os.Build.MANUFACTURER
                + " / Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");

        startUiWatchdogOnOwnThread();

        return true;
    }

    /**
     * Watchdog on a dedicated thread: even if the main thread is blocked
     * (hang/black screen), this still fires and shows the breadcrumbs via the :diag process.
     */
    private void startUiWatchdogOnOwnThread() {
        HandlerThread thread = new HandlerThread("kids-diag-watchdog");
        thread.start();
        new Handler(thread.getLooper()).postDelayed(() -> {
            if (!sUiShown && !sDiagScreenRequested) {
                String msg = "UI NEVER SHOWED UP within " + (UI_WATCHDOG_MS / 1000)
                        + "s -> black screen / hang.\n\nBreadcrumbs:\n" + dump();
                crumb("!!! WATCHDOG: UI not shown !!!");
                showDiagScreen(msg);
            }
            thread.quit();
        }, UI_WATCHDOG_MS);
    }

    private void showDiagScreen(String message) {
        if (sDiagScreenRequested) {
            return;
        }
        sDiagScreenRequested = true;

        try {
            Context ctx = sContextRef;
            if (ctx == null) return;
            Intent i = new Intent(ctx, DiagnosticActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            i.putExtra(DiagnosticActivity.EXTRA_MESSAGE, message);
            ctx.startActivity(i); // runs in the separate :diag process
        } catch (Throwable t) {
            appendFile(CRASH_FILE, "showDiagScreen FAILED: " + t);
        }
    }

    // --- unused ContentProvider stubs ---
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
