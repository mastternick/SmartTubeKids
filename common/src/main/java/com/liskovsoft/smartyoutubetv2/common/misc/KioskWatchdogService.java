package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * KIDS v1.4: kiosk soft-lock guardian (runs only when kiosk is ON without Device Owner).
 *
 * The problem this solves: without Device Owner the lock relies on system screen
 * pinning, which has unavoidable escape windows — the pin is released moments
 * before every internal navigation (v1.2.8), the child can drop it with the
 * system combo (BACK+HOME hold), and on some TV boxes pinning doesn't engage
 * reliably at all. During those windows a HOME press used to land the child on
 * the launcher, and the one-shot re-entry (scheduleReentry) could arrive too late
 * or not at all.
 *
 * The watchdog closes every window with a persistent loop:
 * - armed from MotherActivity.onStop through KioskModeManager.scheduleReentry;
 * - while the app is NOT on screen it relaunches the main activity every second
 *   (the visible overlay window is itself a background-activity-start exemption,
 *   so the climb-back also works on devices that refuse silent background starts);
 * - after a couple of background ticks it shows a fullscreen SYSTEM_ALERT_WINDOW
 *   cover ("returning to the app…") so the child never operates the launcher or
 *   another app. On Android TV this permission is granted at install automatically.
 *   Any key press while covered is swallowed — the cover cannot be poked through;
 *   BACK long-press just forces an immediate relaunch attempt.
 * - stops itself when the app is back on screen, kiosk was switched OFF, the
 *   parent used the approved PIN exit, playback runs in PIP, or the screen is off.
 * - SAFETY VALVE: if the app cannot get on screen after ~45 s (a broken install
 *   must not brick the TV), the cover is removed and the service stops; the pin
 *   and the next key press inside the app still re-arm everything.
 *
 * Everything is wrapped in try/catch(Throwable): this runs in a service started
 * from lifecycle callbacks, and a crash here would take down the whole app
 * (lesson from the v1.2.0 black screen).
 */
public class KioskWatchdogService extends Service {
    private static final String TAG = KioskWatchdogService.class.getSimpleName();

    private static final int NOTIFICATION_ID = 0x4B05; // "KB05"
    private static final String CHANNEL_ID = "kiosk_watchdog";
    // First check happens after the normal internal-task handoff completed (onStop
    // usually fires BEFORE the next activity resumes; 1.2 s lets it settle).
    private static final long FIRST_DELAY_MS = 1_200;
    // Relaunch cadence while the app is off screen.
    private static final long POLL_MS = 1_000;
    // Cover the screen only after the app stayed background for ~2 ticks —
    // quick internal navigations must never flash the overlay.
    private static final int OVERLAY_AFTER_STRIKES = 2;
    // Soft-brick protection: give up covering after ~45 s of failed relaunches.
    private static final int GIVE_UP_STRIKES = 45;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private WindowManager mWindowManager;
    private View mOverlay;
    private int mStrikes;
    private boolean mNoOverlayLogged;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Android 8+ requires startForeground() within seconds of
        // startForegroundService(); a failure must not crash the app.
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable e) {
            Log.e(TAG, e);

            try {
                stopSelf();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mStrikes = 0; // freshly armed (internal navigation or key-driven re-arm)
        scheduleNext(FIRST_DELAY_MS);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mTick);
        hideOverlay();

        super.onDestroy();
    }

    private void tick() {
        try {
            if (!KioskModeManager.isKioskEnabled(this)) {
                stopSafely(); // parent switched kiosk off
                return;
            }

            if (Utils.isAppInForegroundFixed()) {
                hideOverlay();
                stopSafely(); // back on screen — the next onStop re-arms us
                return;
            }

            if (KioskModeManager.isInExitGrace()) {
                // Parent exited on purpose with the PIN: stay quiet, don't cover,
                // don't drag the app back. The window is short and the switch-off
                // (or the next app start) is the parent's job.
                hideOverlay();
                stopSafely();
                return;
            }

            if (KioskModeManager.isInPipPlayback(this) || !KioskModeManager.isScreenInteractive(this)) {
                hideOverlay(); // PIP / screen off are legit "not fullscreen" states
                scheduleNext(POLL_MS);
                return;
            }

            mStrikes++;

            if (mStrikes >= GIVE_UP_STRIKES) {
                Log.e(TAG, "Kiosk watchdog: the app won't come back, releasing the screen (safety valve)");
                stopSafely();
                return;
            }

            if (mStrikes >= OVERLAY_AFTER_STRIKES) {
                showOverlay();
            }

            relaunchApp();
            scheduleNext(POLL_MS);
        } catch (Throwable e) {
            Log.e(TAG, e);
            scheduleNext(POLL_MS); // never die silently — the lock must stay armed
        }
    }

    private void relaunchApp() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());

            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                Log.d(TAG, "Kiosk watchdog: bringing the app back on screen");
            }
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    @SuppressWarnings("deprecation")
    private void showOverlay() {
        if (mOverlay != null) {
            return;
        }

        try {
            // Android TV grants SYSTEM_ALERT_WINDOW automatically (normal permission
            // on TV); phones from Marshmallow need the "draw over other apps" toggle.
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                if (!mNoOverlayLogged) {
                    mNoOverlayLogged = true;
                    Log.e(TAG, "Kiosk watchdog: no overlay permission — climb-back keeps running without the cover");
                }
                return; // relaunch loop still works; just no visual cover
            }

            if (mWindowManager == null) {
                mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            }

            TextView cover = new TextView(this);
            cover.setText(getString(R.string.kids_kiosk_returning));
            cover.setTextColor(0xFFFFFFFF);
            cover.setTextSize(22f);
            cover.setGravity(Gravity.CENTER);
            cover.setBackgroundColor(0xF0000000);
            cover.setPadding(48, 48, 48, 48);
            cover.setFocusable(true);
            cover.setFocusableInTouchMode(true);
            cover.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    // Swallow everything (DPAD, ENTER, BACK, MENU...): while covered,
                    // the child cannot operate whatever launcher sits behind.
                    // A held BACK simply forces the next relaunch attempt now.
                    if (event.getAction() == KeyEvent.ACTION_DOWN
                            && keyCode == KeyEvent.KEYCODE_BACK
                            && event.getRepeatCount() >= 3) {
                        mStrikes = 0;
                        relaunchApp();
                        scheduleNext(FIRST_DELAY_MS);
                    }
                    return true;
                }
            });

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;

            mWindowManager.addView(cover, lp);
            mOverlay = cover;
            cover.requestFocus();
            Log.d(TAG, "Kiosk watchdog: covering the screen while climbing back");
        } catch (Throwable e) {
            Log.e(TAG, e); // BadTokenException etc. — fall back to the invisible loop
            mOverlay = null;
        }
    }

    private void hideOverlay() {
        try {
            if (mOverlay != null && mWindowManager != null) {
                mWindowManager.removeView(mOverlay);
            }
        } catch (Throwable e) {
            Log.e(TAG, e);
        } finally {
            mOverlay = null;
        }
    }

    private void stopSafely() {
        try {
            mHandler.removeCallbacks(mTick);
            hideOverlay();
            stopSelf();
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    private void scheduleNext(long delayMs) {
        mHandler.removeCallbacks(mTick);
        mHandler.postDelayed(mTick, delayMs);
    }

    private Notification buildNotification() {
        String title = getString(R.string.kids_kiosk_watchdog_notification);

        if (Build.VERSION.SDK_INT >= 26) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel channel = new NotificationChannel(
                            CHANNEL_ID, title, NotificationManager.IMPORTANCE_MIN);
                    channel.setShowBadge(false);
                    nm.createNotificationChannel(channel);
                }
            } catch (Throwable e) {
                Log.e(TAG, e);
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(title)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true);

        try {
            Class<? extends android.app.Activity> root =
                    ViewManager.instance(getApplicationContext()).getRootActivity();

            if (root != null) {
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;

                if (Build.VERSION.SDK_INT >= 23) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }

                builder.setContentIntent(PendingIntent.getActivity(
                        this, 0, new Intent(this, root), flags));
            }
        } catch (Throwable e) {
            Log.e(TAG, e); // notification stays without a tap target — fine
        }

        return builder.build();
    }
}
