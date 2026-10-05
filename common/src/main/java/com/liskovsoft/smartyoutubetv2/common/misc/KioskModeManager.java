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
import android.provider.Settings; // KIDS v1.6: key guard setup / status
import android.view.KeyEvent; // KIDS v1.6: foreground key consumption

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils; // KIDS v1.2.7: postDelayed / foreground check

import java.lang.ref.WeakReference; // KIDS v1.2.8

/**
 * KIDS: Kiosk mode — the child cannot leave the app.
 *
 * Two levels of protection, chosen automatically, plus the key guard:
 *
 * 0. KEY GUARD (KIDS v1.6, no Device Owner needed). {@link KioskKeyGuardService},
 *    an AccessibilityService with flagRequestFilterKeyEvents, swallows HOME,
 *    RECENTS and the mic/assistant keys system-wide while kiosk is ON. This is
 *    what closes the soft-lock hole on Android TV 14 builds where system screen
 *    pinning (level 2) is disabled: without it startLockTask() is a silent no-op
 *    and only BACK (in-app) stays blocked. Inert unless kiosk is ON; the parent
 *    can leave it installed permanently. Enabled once from Kids Mode settings
 *    (startKeyGuardSetup) or via ADB (see KIOSK.md).
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
 * 2. SOFT LOCK (no Device Owner, best effort — KIDS v1.2.8). The current screen
 *    is pinned with system screen pinning (startLockTask), so HOME/RECENTS do
 *    nothing while a screen is up. To keep navigation alive, the pin is released
 *    RIGHT BEFORE every internal launch (ViewManager / MotherActivity call
 *    releaseForNavigation) and re-applied when the new screen resumes. BACK at a
 *    root screen asks for the PIN — only the correct PIN exits the app
 *    (notifyParentExit opens a grace window so the soft lock doesn't drag the
 *    app back). If the child drops the pin with the system combo (BACK+HOME
 *    hold), the next key press re-pins (ensureLockOnKeyPress); if the app still
 *    loses the screen, KioskWatchdogService climbs back on its own — every
 *    second, with a fullscreen cover on top of the launcher (KIDS v1.4), so
 *    HOME leaks during the unpin/re-pin cycle are closed automatically.
 *
 * v1.2.7 history: this app runs every activity as launchMode=singleInstance —
 * each one in its own task (Browse, Playback, dialogs...). System screen pinning
 * confines the task it was started from, so with a pinned Browse task every
 * launch into another task was refused by the system: the child could no longer
 * open a clip or the Settings screen ("it says blocked / kiosk mode" — the exact
 * v1.2.6 bug report). v1.2.7 reacted by dropping pinning without Device Owner
 * (but then HOME was no longer blocked); v1.2.8 restores it — pinning is what
 * blocks HOME — but releases the pin before each navigation and re-pins the new
 * screen, so clips and Settings work again while HOME stays blocked. Full lock
 * via Device Owner doesn't need any of this: the whole package is allowlisted.
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
    // KIDS v1.2.8: exit approved with the PIN — the soft lock must not drag the
    // app back or re-pin during this window.
    private static final long EXIT_GRACE_MS = 15_000;
    // KIDS v1.2.8: re-pin throttle on key presses (after the system unpin combo).
    private static final long LOCK_ON_KEY_THROTTLE_MS = 3_000;
    // KIDS v1.6: the parent is sent to the system Accessibility screen (key
    // guard setup). Needs more than the 15 s exit grace to navigate there.
    private static final long SETUP_GRACE_MS = 5 * 60_000;
    // KIDS v1.6: throttle the key-guard climb-back (a held escape key must not
    // spam activity starts).
    private static final long BRING_BACK_THROTTLE_MS = 1_000;

    private static final int DO_STATE_UNKNOWN = 0;
    private static final int DO_STATE_APPLIED = 1;
    private static final int DO_STATE_CLEARED = 2;

    private static long sLastAutoLockMs;
    private static boolean sLockRequestedInProcess; // fallback for API 21-22 (no public lock task query API)
    private static int sDoPoliciesState = DO_STATE_UNKNOWN;
    private static long sExitGraceUntilMs; // KIDS v1.2.8: PIN-approved exit window
    private static long sLastLockOnKeyMs; // KIDS v1.2.8: throttle for ensureLockOnKeyPress
    private static WeakReference<Activity> sPinnedActivity; // KIDS v1.2.8: the task we pinned ourselves
    private static boolean sKeyGuardHintShown; // KIDS v1.6: one key-guard hint per process
    private static long sLastBringBackMs; // KIDS v1.6: key-guard climb-back throttle

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
     * KIDS v1.6: TRUE when the global key guard must swallow escape keys —
     * kiosk is ON and no parent bypass is open. Used by KioskKeyGuardService
     * (system-wide input filter) and by foreground key consumption.
     *
     * The ONLY bypass is the grace window opened by an approved action
     * (notifyParentExit on a PIN exit, startKeyGuardSetup while the parent ticks
     * the guard). KidsPinGate's 10-minute parent session is deliberately NOT a
     * bypass: it is armed by any PIN entry, so honouring it would re-open HOME
     * for the child for ten minutes after the parent adjusted any Kids setting.
     * The parent inside the app uses BACK+PIN (or the switch) to get out.
     */
    public static boolean isKeyGuardActive(Context context) {
        try {
            return isKioskEnabled(context) && !isInExitGrace();
        } catch (Throwable e) {
            Log.e(TAG, e);
            return false;
        }
    }

    /**
     * KIDS v1.6: is the key guard accessibility service enabled by the parent?
     * Reads the system's enabled-services list (no permission needed to read).
     */
    public static boolean isKeyGuardEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (enabled == null) {
                return false;
            }

            String expected = new ComponentName(context, KioskKeyGuardService.class).flattenToString();

            for (String entry : enabled.split(":")) {
                if (entry.equalsIgnoreCase(expected)) {
                    return true;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, e);
        }

        return false;
    }

    /**
     * KIDS v1.6: parent flow — open the system Accessibility screen so the key
     * guard can be ticked. Soft lock only (Device Owner blocks HOME/RECENTS
     * itself; the guard is optional there for HOME/RECENTS/the search key, and is
     * the best available layer for assist keys).
     *
     * Opens a long grace window first: without it the watchdog would drag the
     * parent straight back from the settings screen (and the guard would eat
     * the keys they need). Same mechanics as the PIN exit, just longer.
     */
    public static void startKeyGuardSetup(Activity activity) {
        if (activity == null) {
            return;
        }

        try {
            sExitGraceUntilMs = System.currentTimeMillis() + SETUP_GRACE_MS;
            sKeyGuardHintShown = true; // the guidance below IS the hint — no duplicate toast on resume
            releaseForNavigation(activity);
            stopWatchdog(activity);

            openKeyGuardSettings(activity); // starts the activity via the MotherActivity hook (pin released)
        } catch (Throwable e) {
            Log.e(TAG, e);
            MessageHelpers.showMessage(activity, R.string.kids_kiosk_key_guard_failed);
        }
    }

    // KIDS v1.6: hidden/system action — no public constant in the SDK stubs
    // (verified against android-34), and no reliable public API level either,
    // hence a literal + a runtime fallback instead of a version gate.
    private static final String ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";

    /**
     * KIDS v1.6: open the system Accessibility screen. Deep-links to the guard's
     * own toggle when the build has one (hidden action + EXTRA_COMPONENT_NAME, as
     * used by the AOSP AccessibilityDetailsSettingsFragment, which itself falls
     * back to the plain list when the service is not toggleable yet — e.g. blocked
     * by Android 13+ "restricted settings" for a side-loaded APK, which is why the
     * guidance message mentions it). Builds without the details activity (or with
     * a protected one) drop to the plain accessibility list.
     */
    private static void openKeyGuardSettings(Activity activity) {
        try {
            Intent details = new Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
            details.putExtra(Intent.EXTRA_COMPONENT_NAME,
                    new ComponentName(activity, KioskKeyGuardService.class).flattenToString());
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(details);
            return;
        } catch (Throwable e) {
            Log.d(TAG, "No accessibility details screen (%s) — opening the list", e.getMessage());
        }

        Intent list = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(list); // failure here surfaces as kids_kiosk_key_guard_failed
    }

    /**
     * KIDS v1.6: foreground safety net — consume the escape keys if they are
     * ever dispatched to our window (the accessibility guard already kills them
     * system-wide; this covers devices where the guard is not enabled yet).
     * SEARCH is deliberately NOT consumed here: inside the app it opens the
     * in-app search, not another app.
     */
    public static boolean consumeEscapeKey(Context context, KeyEvent event) {
        try {
            return event != null && isKeyGuardActive(context)
                    && isEscapeKey(event.getKeyCode(), false);
        } catch (Throwable e) {
            Log.e(TAG, e);
            return false;
        }
    }

    /**
     * KIDS v1.6: pull the app back to the front. Called by the key guard whenever
     * an escape key arrives while another app is on screen (HOME leaked, the app
     * was killed, the watchdog isn't armed): the key is swallowed AND the child
     * lands back in SmartTubeKids. Also called on service connect (boot / process
     * restart). SYSTEM_ALERT_WINDOW — granted on TV — exempts the app from the
     * background-activity-start restriction. Throttled; no-op while on screen and
     * during the parent grace windows (PIN exit / key-guard setup).
     */
    public static void bringAppBack(Context context) {
        try {
            // Same guards as KioskWatchdogService: never launch into a sleeping
            // screen, never pull the app out of a legitimate PIP window.
            // isInExitGrace() covers the PIN exit AND the key-guard setup window:
            // while the parent is ticking the guard in the system Accessibility
            // screen, onServiceConnected fires and must NOT drag them out of it.
            if (!isKioskEnabled(context) || isInExitGrace() || Helpers.isAppInForeground()
                    || isInPipPlayback(context) || !isScreenInteractive(context)) {
                return;
            }

            long now = System.currentTimeMillis();

            if (now - sLastBringBackMs < BRING_BACK_THROTTLE_MS) {
                return;
            }

            sLastBringBackMs = now;

            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());

            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                Log.d(TAG, "Kiosk key guard: bringing the app back on screen");
            }
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    // Escape keys that take the child out of the app or summon another app
    // (assistant / mic / launcher overlays). Literals on purpose: referencing
    // KeyEvent.KEYCODE_ASSIST / KEYCODE_VOICE_ASSIST directly trips lint NewApi
    // against minSdk 17 (the constants land in API 11 / 21).
    private static final int KEYCODE_ASSIST = 219;       // KeyEvent.KEYCODE_ASSIST
    private static final int KEYCODE_VOICE_ASSIST = 231; // KeyEvent.KEYCODE_VOICE_ASSIST (mic button)
    private static final int KEYCODE_EXPLORER = 64;      // KeyEvent.KEYCODE_EXPLORER (browser key)

    /**
     * KIDS v1.6: single source of truth for the kiosk blocklist, shared by the
     * global key guard (includeSearch = true — the remote's search/mic button is
     * the assistant's entry point device-wide) and the in-app safety net
     * (includeSearch = false — in-app the search key opens the app's own search).
     * BACK, volume, power, media and DPAD keys are never in this list.
     *
     * HOME, RECENTS and SEARCH are handled in interceptKeyBeforeDispatching, i.e.
     * after the accessibility input filter, so the guard really kills them. ASSIST
     * and VOICE_ASSIST are handled in interceptKeyBeforeQueueing (before the
     * filter): the guard still keeps them away from the app, but the assistant
     * launch is already posted — see KioskKeyGuardService's LIMIT note.
     */
    public static boolean isEscapeKey(int keyCode, boolean includeSearch) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH: // RECENTS
            case KEYCODE_ASSIST:
            case KEYCODE_VOICE_ASSIST:
            case KEYCODE_EXPLORER:
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                return includeSearch;
            default:
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
                sPinnedActivity = null; // KIDS v1.2.8: nothing pinned by us anymore
            }

            sLockRequestedInProcess = false;
            stopWatchdog(activity); // KIDS v1.4: switch OFF removes the guardian immediately
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    /**
     * KIDS: single entry point for MotherActivity.onResume.
     * Applies the lock when kiosk is ON, releases it when OFF.
     * Must never throw (startup path).
     *
     * KIDS v1.2.8: without Device Owner the current screen is pinned again —
     * that's what blocks HOME. Cross-task navigation stays possible because
     * every internal launch first calls releaseForNavigation(), and the new
     * screen re-pins itself on its own resume. With Device Owner the whole
     * package is allowlisted, so no release/re-pin cycle is needed.
     */
    public static void applyOnResume(Activity activity) {
        try {
            if (isKioskEnabled(activity)) {
                if (isDeviceOwner(activity)) {
                    applyDeviceOwnerPolicies(activity);
                }
                autoLockIfNeeded(activity); // KIDS v1.2.8: also without DO = screen pinning (HOME blocked); cross-task fixed by releaseForNavigation()
                hintKeyGuardIfNeeded(activity); // KIDS v1.6: point the parent to the full block
            } else if (isLockTaskActive(activity)) {
                stopKiosk(activity);
            } else {
                clearDeviceOwnerPolicies(activity); // no-op unless DO policies are still set
            }

            stopWatchdog(activity); // KIDS v1.4: the app is on screen again — guardian idles until the next onStop
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
     *
     * KIDS v1.2.8: skipped during the PIN-approved exit window (notifyParentExit).
     */
    public static void scheduleReentry(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT < 21 || System.currentTimeMillis() < sExitGraceUntilMs
                    || !isKioskEnabled(activity)
                    || isDeviceOwner(activity) || isTransientTask(activity)) {
                return;
            }

            final Context context = activity.getApplicationContext();

            // KIDS v1.4: the one-shot climb-back below is not enough — pin release
            // windows and the BACK+HOME unpin combo can leave the child on the
            // launcher. The watchdog keeps relaunching every second and covers the
            // screen until the app is back on top (no-op during the PIN exit grace,
            // it checks all the same guards itself).
            startWatchdog(activity);

            Utils.postDelayed(() -> {
                try {
                    if (System.currentTimeMillis() < sExitGraceUntilMs // KIDS v1.2.8: parent is leaving on purpose
                            || !isKioskEnabled(context) // parent turned it off meanwhile
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

    /**
     * KIDS v1.2.8: release OUR OWN screen pin right before an internal navigation.
     * System screen pinning confines one task, and this app runs every screen in
     * its own task (launchMode=singleInstance) — launching Playback or Settings
     * while Browse is pinned was refused by the system (the v1.2.6 bug). Called
     * from ViewManager.safeStartActivityInt and MotherActivity.startActivity for
     * every internal launch. Without Device Owner the app pinned the task itself,
     * so it can drop the pin without any system prompt; the new screen re-pins on
     * its own resume (applyOnResume + sLastAutoLockMs reset below).
     * No-op with Device Owner (package allowlisted: no cross-task problem).
     */
    public static void releaseForNavigation(Context context) {
        try {
            if (Build.VERSION.SDK_INT < 21 || !isKioskEnabled(context) || isDeviceOwner(context)) {
                return;
            }

            Activity pinned = sPinnedActivity != null ? sPinnedActivity.get() : null;

            if (pinned != null && isLockTaskActive(context)) {
                pinned.stopLockTask(); // the app pinned it, the app can unpin it — no system prompt
                sPinnedActivity = null;
                sLockRequestedInProcess = false; // API 21-22 fallback state
                sLastAutoLockMs = 0; // let the screen that's about to resume re-pin immediately
            }
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    /**
     * KIDS v1.2.8: the parent entered the correct PIN and is exiting the app on
     * purpose — open a grace window (scheduleReentry / auto-lock / key re-pin
     * stay quiet) and drop our own screen pin.
     */
    public static void notifyParentExit(Context context) {
        sExitGraceUntilMs = System.currentTimeMillis() + EXIT_GRACE_MS;
        releaseForNavigation(context);
        stopWatchdog(context); // KIDS v1.4: the parent is leaving on purpose — no cover, no drag-back
    }

    /**
     * KIDS v1.2.8: re-pin after the pin was dropped by the system unpin combo
     * (BACK+HOME hold). Called from every key press; throttled; never during the
     * PIN-approved exit window; no-op when already locked.
     */
    public static void ensureLockOnKeyPress(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT < 21 || !isKioskEnabled(activity)
                    || System.currentTimeMillis() < sExitGraceUntilMs
                    || isLockTaskActive(activity)) {
                return;
            }

            long now = System.currentTimeMillis();

            if (now - sLastLockOnKeyMs < LOCK_ON_KEY_THROTTLE_MS) {
                return;
            }

            sLastLockOnKeyMs = now;
            autoLockIfNeeded(activity); // KIDS v1.2.8: re-pin after a system unpin (BACK+HOME hold)
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    /**
     * KIDS v1.4: true while the parent-approved PIN exit window is open
     * (scheduleReentry / auto-lock / watchdog stay quiet).
     */
    public static boolean isInExitGrace() {
        return System.currentTimeMillis() < sExitGraceUntilMs;
    }

    /**
     * KIDS v1.4: soft-lock guardian. Started from scheduleReentry (MotherActivity.onStop)
     * whenever kiosk is ON without Device Owner; stopped whenever the app is back on
     * screen, kiosk goes off, or the parent exits with the PIN. Guarded both ways:
     * lifecycle paths must never throw.
     */
    private static void startWatchdog(Context context) {
        try {
            Utils.startService(context, KioskWatchdogService.class);
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    private static void stopWatchdog(Context context) {
        try {
            Utils.stopService(context, KioskWatchdogService.class);
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    // KIDS v1.4: package-visible (KioskWatchdogService reuses both guards).
    static boolean isInPipPlayback(Context context) {
        try {
            return com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter
                    .instance(context).isInPipMode();
        } catch (Throwable e) {
            return false;
        }
    }

    static boolean isScreenInteractive(Context context) {
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

    /**
     * KIDS v1.6: soft lock without the key guard is only a partial block on TV
     * builds where system screen pinning is disabled (startLockTask is then a
     * silent no-op and HOME/RECENTS leak). Tell the parent once per process how to
     * get the full lock. No-op with Device Owner or once the guard is enabled.
     */
    private static void hintKeyGuardIfNeeded(Activity activity) {
        if (sKeyGuardHintShown || isDeviceOwner(activity) || isKeyGuardEnabled(activity)) {
            return;
        }

        sKeyGuardHintShown = true;
        MessageHelpers.showMessage(activity, R.string.kids_kiosk_key_guard_needed);
    }

    private static void autoLockIfNeeded(Activity activity) {
        if (Build.VERSION.SDK_INT < 21) {
            MessageHelpers.showMessage(activity, R.string.kids_kiosk_unsupported);
            return;
        }

        // KIDS v1.2.8: the parent just approved an exit with the PIN — don't re-pin.
        if (System.currentTimeMillis() < sExitGraceUntilMs) {
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

                // Device Owner: full lock silențios. Altfel: screen pinning — asta
                // blochează HOME; navigarea rămâne ok datorită releaseForNavigation().
                activity.startLockTask();
                sPinnedActivity = new WeakReference<>(activity); // KIDS v1.2.8: remember the task we pinned
                sLockRequestedInProcess = true;
                Log.d(TAG, "Lock task requested from %s", activity.getClass().getSimpleName());

                // KIDS v1.6: on a lot of Android TV 14 builds screen pinning is
                // disabled at system level, so startLockTask() is a silent no-op.
                // Detect it and say so once — the key guard and the watchdog are
                // the layers that actually hold there. (API 23+ only: below that
                // there is no public lock task state to query.)
                if (Build.VERSION.SDK_INT >= 23) {
                    activity.getWindow().getDecorView().postDelayed(() -> {
                        try {
                            if (!isLockTaskActive(activity)) {
                                Log.d(TAG, "Screen pinning not engaged (disabled on this device) — key guard / watchdog carry the lock");
                            }
                        } catch (Throwable e) {
                            Log.e(TAG, e);
                        }
                    }, 1_500);
                }
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
