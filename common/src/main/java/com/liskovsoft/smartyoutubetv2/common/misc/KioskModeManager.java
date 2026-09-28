package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Build;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils; // KIDS v1.2.7: postDelayed / foreground check

/**
 * KIDS: Kiosk mode — the child cannot leave the app.
 *
 * Two levels of protection, chosen automatically:
 *
 * 1. FULL LOCK (Device Owner). If the app was set as device owner via ADB
 *    (adb shell dpm set-device-owner app.smarttubekids/com.liskovsoft.smartyoutubetv2.common.misc.KioskDeviceAdminReceiver)
 *    then this class allowlists the package for Lock Task and makes the app the
 *    persistent HOME. Result: HOME/RECENTS keys do nothing, no notifications,
 *    no confirmation dialogs, the app relaunches itself after a TV reboot and
 *    the only way out is the PIN-protected switch inside Kids Mode settings.
 *    Lock Task is safe for the app's own activities: every activity of an
 *    allowlisted package keeps working (playback, Settings, dialogs).
 *
 * 2. SOFT LOCK (no Device Owner, best effort). The BACK key never exits
 *    (intercepted in LeanbackActivity.finish) and when the app loses the screen
 *    — e.g. the child presses HOME — it tries to come back on its own
 *    (scheduleReentry). Weaker than the full lock but requires no setup.
 *
 * v1.2.7: the old "screen pinning" fallback (startLockTask without Device Owner)
 * was REMOVED. This app runs every activity as launchMode=singleInstance — each
 * one in its own task (Browse, Playback, dialogs...). System screen pinning
 * confines the task it was started from, so with a pinned Browse task every
 * launch into another task was refused by the system: the child could no longer
 * open a clip or the Settings screen ("it says blocked / kiosk mode" — the exact
 * v1.2.6 bug report). Full lock via Device Owner does not have this problem
 * because the whole package is allowlisted — which is why lock task now runs
 * only in that mode.
 *
 * IMPORTANT (lesson from the v1.2.0 black screen): everything here is wrapped
 * in try/catch(Throwable) and is only called from Activity.onResume (never from
 * constructors or restoreState paths). Auto-lock attempts are throttled and
 * delayed so transient tasks (Splash) and dialogs (AppDialogActivity) are never
 * pinned — pinning a task that immediately finishes would drop the lock.
 */
public class KioskModeManager {
    private static final String TAG = KioskModeManager.class.getSimpleName();

    // Throttle repeated auto-lock attempts: without Device Owner the system may show
    // a confirmation prompt on every try, don't spam it on each activity resume.
    private static final long AUTO_LOCK_THROTTLE_MS = 15_000;
    // Let short-lived activities (e.g. SplashActivity) finish before locking their task.
    private static final long AUTO_LOCK_DELAY_MS = 2_000;
    // KIDS v1.2.7: soft lock grace period before the app climbs back on screen.
    private static final long REENTRY_DELAY_MS = 2_500;

    private static final int DO_STATE_UNKNOWN = 0;
    private static final int DO_STATE_APPLIED = 1;
    private static final int DO_STATE_CLEARED = 2;

    private static long sLastAutoLockMs;
    private static boolean sLockRequestedInProcess; // fallback for API 21-22 (no public lock task query API)
    private static int sDoPoliciesState = DO_STATE_UNKNOWN;

    private KioskModeManager() {
    }

    /**
     * KIDS: parent-controlled preference. When ON the app refuses to exit on BACK
     * and re-applies the system lock on every activity resume.
     */
    public static boolean isKioskEnabled(Context context) {
        try {
            return KidsModeData.instance(context).isKioskEnabled();
        } catch (Throwable e) {
            Log.e(TAG, e);
            return false;
        }
    }

    /**
     * @return true when the app is the device owner (full kiosk possible, no prompts).
     */
    public static boolean isDeviceOwner(Context context) {
        if (Build.VERSION.SDK_INT < 21) {
            return false;
        }

        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
        } catch (Throwable e) {
            Log.e(TAG, e);
            return false;
        }
    }

    /**
     * @return true when lock task mode (full lock or screen pinning) is currently active.
     */
    public static boolean isLockTaskActive(Context context) {
        if (Build.VERSION.SDK_INT < 23) {
            return sLockRequestedInProcess; // best effort: no public API below 23
        }

        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        } catch (Throwable e) {
            Log.e(TAG, e);
            return false;
        }
    }

    /**
     * KIDS: remove the lock and the Device Owner policies. Called when the parent
     * turns the kiosk switch OFF (PIN-protected) or when the stored preference is OFF.
     */
    public static void stopKiosk(Activity activity) {
        if (activity == null) {
            return;
        }

        try {
            clearDeviceOwnerPolicies(activity);

            if (Build.VERSION.SDK_INT >= 21 && isLockTaskActive(activity)) {
                activity.stopLockTask();
            }

            sLockRequestedInProcess = false;
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    /**
     * KIDS: single entry point for MotherActivity.onResume.
     * Applies the lock when kiosk is ON, releases it when OFF.
     * Must never throw (startup path).
     *
     * KIDS v1.2.7: Lock Task is only applied with Device Owner. Without DO the
     * soft lock (BACK interception + scheduleReentry) is used, and a stale pin
     * left over from v1.2.6 builds is actively released — pinning the Browse
     * task would refuse every other singleInstance task the app starts
     * (Playback, Settings), which is exactly what broke playback in v1.2.6.
     */
    public static void applyOnResume(Activity activity) {
        try {
            if (isKioskEnabled(activity)) {
                if (isDeviceOwner(activity)) {
                    applyDeviceOwnerPolicies(activity);
                    autoLockIfNeeded(activity);
                } else if (isLockTaskActive(activity)) {
                    stopKiosk(activity); // release stale screen pinning (v1.2.6 upgrade path)
                }
            } else if (isLockTaskActive(activity)) {
                stopKiosk(activity);
            } else {
                clearDeviceOwnerPolicies(activity); // no-op unless DO policies are still set
            }
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    /**
     * KIDS v1.2.7: called from MotherActivity.onStop — the soft lock part.
     * When kiosk is ON but there's no Device Owner and the app really left the
     * screen (HOME pressed, another app opened — not screen-off, not PIP),
     * re-launch our own main activity so the child gets back to the app.
     * On Android 10+ the system may silently refuse background activity starts;
     * in that case nothing happens (guarded, no crash) and the BACK interception
     * still keeps the app un-exitable from the inside.
     */
    public static void scheduleReentry(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT < 21 || !isKioskEnabled(activity)
                    || isDeviceOwner(activity) || isTransientTask(activity)) {
                return;
            }

            final Context context = activity.getApplicationContext();

            Utils.postDelayed(() -> {
                try {
                    if (!isKioskEnabled(context) // parent turned it off meanwhile
                            || Utils.isAppInForegroundFixed() // normal in-app navigation
                            || isInPipPlayback(context) // PIP window is fine to keep
                            || !isScreenInteractive(context)) { // screen off / standby
                        return;
                    }

                    Intent launch = context.getPackageManager()
                            .getLaunchIntentForPackage(context.getPackageName());

                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launch);
                        Log.d(TAG, "Kiosk soft lock: bringing the app back on screen");
                    }
                } catch (Throwable e) {
                    Log.e(TAG, e);
                }
            }, REENTRY_DELAY_MS);
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    private static boolean isInPipPlayback(Context context) {
        try {
            return com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter
                    .instance(context).isInPipMode();
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isScreenInteractive(Context context) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    context.getSystemService(Context.POWER_SERVICE);

            if (pm == null) {
                return true;
            }

            // minSdk 17: isInteractive() exists from API 20, isScreenOn() before (lint NewApi).
            return Build.VERSION.SDK_INT >= 20 ? pm.isInteractive() : pm.isScreenOn();
        } catch (Throwable e) {
            return true; // fail open: treat as interactive, the other guards still apply
        }
    }

    private static void autoLockIfNeeded(Activity activity) {
        if (Build.VERSION.SDK_INT < 21) {
            MessageHelpers.showMessage(activity, R.string.kids_kiosk_unsupported);
            return;
        }

        if (isLockTaskActive(activity) || isTransientTask(activity)) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - sLastAutoLockMs < AUTO_LOCK_THROTTLE_MS) {
            return;
        }

        // Device Owner policies (lock task allowlist + persistent HOME) don't depend
        // on the current task, apply them right away.
        applyDeviceOwnerPolicies(activity);

        activity.getWindow().getDecorView().postDelayed(() -> {
            try {
                // The activity may have finished in the meantime (e.g. SplashActivity).
                // Locking a task that disappears would immediately drop the lock.
                if (activity.isFinishing() || activity.isDestroyed() || isLockTaskActive(activity)) {
                    return;
                }

                // Throttle is stamped here (not at schedule time) so a cold start
                // Splash -> Browse doesn't consume the window before Browse locks.
                sLastAutoLockMs = System.currentTimeMillis();

                // Device Owner: silent full lock. Otherwise: system screen-pinning prompt.
                activity.startLockTask();
                sLockRequestedInProcess = true;
                Log.d(TAG, "Lock task requested from %s", activity.getClass().getSimpleName());
            } catch (Throwable e) {
                Log.e(TAG, e);
            }
        }, AUTO_LOCK_DELAY_MS);
    }

    /**
     * Dialogs (AppDialogActivity: noHistory + excludeFromRecents) live in their own
     * transient tasks. Locking such a task is pointless: it ends with the dialog.
     */
    private static boolean isTransientTask(Activity activity) {
        try {
            ComponentName component = activity.getComponentName();

            if (component == null) {
                return false;
            }

            ActivityInfo info = activity.getPackageManager().getActivityInfo(component, 0);

            // Manifest attributes map to flag bits (there are no boolean fields on ActivityInfo)
            return (info.flags & (ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS | ActivityInfo.FLAG_NO_HISTORY)) != 0;
        } catch (Throwable e) {
            Log.e(TAG, e);
            return false;
        }
    }

    /**
     * Device Owner only: allowlist the package for Lock Task (no confirmation
     * dialogs, HOME/RECENTS blocked) and make the app the persistent HOME
     * (direct launch after boot, HOME key never leaves the app).
     */
    private static void applyDeviceOwnerPolicies(Context context) {
        if (Build.VERSION.SDK_INT < 21 || sDoPoliciesState == DO_STATE_APPLIED || !isDeviceOwner(context)) {
            return;
        }

        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, KioskDeviceAdminReceiver.class);

            if (dpm == null) {
                return;
            }

            dpm.setLockTaskPackages(admin, new String[]{context.getPackageName()});

            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());

            if (launchIntent != null && launchIntent.getComponent() != null) {
                IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
                homeFilter.addCategory(Intent.CATEGORY_HOME);
                homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
                dpm.addPersistentPreferredActivity(admin, homeFilter, launchIntent.getComponent());
            }

            sDoPoliciesState = DO_STATE_APPLIED;
            Log.d(TAG, "Device Owner kiosk policies applied");
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    /**
     * Device Owner only: undo applyDeviceOwnerPolicies. Safe to call repeatedly.
     */
    private static void clearDeviceOwnerPolicies(Context context) {
        if (Build.VERSION.SDK_INT < 21 || sDoPoliciesState == DO_STATE_CLEARED || !isDeviceOwner(context)) {
            return;
        }

        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, KioskDeviceAdminReceiver.class);

            if (dpm == null) {
                return;
            }

            dpm.clearPackagePersistentPreferredActivities(admin, context.getPackageName());
            dpm.setLockTaskPackages(admin, new String[0]);

            sDoPoliciesState = DO_STATE_CLEARED;
            Log.d(TAG, "Device Owner kiosk policies cleared");
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }
}
