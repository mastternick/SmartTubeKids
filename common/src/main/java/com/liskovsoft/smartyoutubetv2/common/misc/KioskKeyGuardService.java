package com.liskovsoft.smartyoutubetv2.common.misc;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.TargetApi;
import android.os.Build;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * KIDS v1.6: kiosk key guard — the only layer that blocks HOME / RECENTS / SEARCH
 * device-wide without Device Owner (see the assist-key limit below).
 *
 * Why this exists: HOME, APP_SWITCH (RECENTS) and SEARCH are handled by
 * PhoneWindowManager.interceptKeyBeforeDispatching — i.e. AFTER the input filter,
 * so consuming them here really kills them. (AOSP 14 InputDispatcher::notifyKey:
 * interceptKeyBeforeQueueing → filterInputEvent → dispatch →
 * interceptKeyBeforeDispatching.) On many Android TV 14 builds system screen
 * pinning (startLockTask) is silently disabled, so the v1.2.8 soft lock degraded
 * to "only BACK is blocked" — this guard is what actually holds HOME down.
 *
 * LIMIT — assist keys: KEYCODE_ASSIST / KEYCODE_VOICE_ASSIST are handled in
 * PhoneWindowManager.interceptKeyBeforeQueueing, which runs BEFORE any input
 * filter, so the assistant launch is already posted by the time we see the event.
 * They are kept in the blocklist (they still never reach the app, and the in-app
 * veto covers them), but to kill the mic button completely the assistant app has
 * to go: `adb shell pm disable-user --user 0 <assistant pkg>` (no Device Owner
 * needed) or Device Owner Lock Task, which refuses the assistant's activity start.
 * Remotes whose mic button sends KEYCODE_SEARCH are fully covered here.
 *
 * Enable path (parent, one time, no PC needed): Kids Mode settings → kiosk
 * switch ON → the app opens Accessibility settings (KioskModeManager
 * .startKeyGuardSetup) and the parent ticks "SmartTube Kids Kiosk Key Guard".
 * ADB alternative is documented in KIOSK.md.
 *
 * Safety: the guard is INERT unless kiosk is actually ON, and it never traps
 * the parent — the PIN exit and the key-guard setup window both open a bypass
 * in which every key passes through. The setup window is the in-memory grace
 * (KioskModeManager.isInExitGrace); the PIN exit is the PERSISTED release
 * (KioskModeManager.isReleasedByParent), because the exit kills the process and
 * this service is then rebound by the system in a fresh one — honouring only
 * the in-memory window there pulled the app back on screen right after the
 * parent had unlocked and left.
 * Volume / power / media / DPAD keys are never touched.
 *
 * Everything is wrapped in try/catch(Throwable): this runs for EVERY key press
 * system-wide; a crash here would be felt outside the app too.
 */
public class KioskKeyGuardService extends AccessibilityService {
    private static final String TAG = KioskKeyGuardService.class.getSimpleName();

    @TargetApi(18) // accessibility key filtering: API 18+. Below that the guard is inert (kiosk still uses pinning/watchdog).
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        try {
            // Re-assert the filter flag: some TV images drop it when restoring
            // a persisted accessibility config. Cheap, idempotent.
            if (Build.VERSION.SDK_INT >= 18) {
                AccessibilityServiceInfo info = getServiceInfo();

                if (info != null) {
                    info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                    setServiceInfo(info);
                }
            }

            Log.d(TAG, "Kiosk key guard connected (kiosk=%s, released=%s)",
                    KioskModeManager.isKioskEnabled(this), KioskModeManager.isReleasedByParent(this));

            // Boot / process restart: with kiosk ON the app belongs on screen (the
            // watchdog may not be armed yet). No-op when kiosk is off, and no-op while
            // a PIN-approved parent exit is in effect — that is what keeps the app
            // from being pulled back right after the parent unlocked and left.
            KioskModeManager.bringAppBack(this);
        } catch (Throwable e) {
            Log.e(TAG, e);
        }
    }

    /**
     * Global input filter. Runs BEFORE PhoneWindowManager for every key on the
     * device, including while another app (launcher, assistant) is on screen.
     * Must stay cheap: it is called on the main thread for every key event.
     */
    @TargetApi(18)
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            if (event == null || !KioskModeManager.isKeyGuardActive(this)) {
                return false; // kiosk OFF / parent session / exit grace — pass through
            }

            if (KioskModeManager.isEscapeKey(event.getKeyCode(), true)) {
                Log.d(TAG, "Kiosk key guard: swallowed key %s", event.getKeyCode());

                // The key is dead device-wide; if another app happened to be on
                // screen (HOME leak, app killed, watchdog not armed yet) also pull
                // the child straight back into SmartTubeKids. No-op while on screen.
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    // KIDS: on the time-up black screen HOME/RECENTS are escape attempts
                    // too — they die here, so the mash counter must be fed from here or
                    // "press HOME 10 times" could never reach its threshold.
                    KidsTimeUpLock.onGuardEscapeKey(event);
                    KioskModeManager.bringAppBack(this);
                }

                return true; // consume — the system never sees HOME/RECENTS/mic
            }
        } catch (Throwable e) {
            Log.e(TAG, e);
        }

        return false; // fail open: anything unexpected must never brick the remote
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used: the guard is a pure key filter.
    }

    @Override
    public void onInterrupt() {
        // Not used.
    }
}
