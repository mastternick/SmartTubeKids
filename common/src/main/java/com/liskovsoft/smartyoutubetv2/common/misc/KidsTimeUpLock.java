package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.content.Context;
import android.view.KeyEvent;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * KIDS: PIN-locked black screen shown when the daily watch limit expires with
 * "stop immediately" (force stop) enabled.
 *
 * Difference from the calm exit: there the black screen is only a transition — the
 * first key press removes it and the child is back on the playlist. Here the screen
 * is a GATE: every key press is swallowed and the screen stays pitch black, the
 * overlay survives the playback activity (re-applied on the resumed browse activity),
 * and only the correct Kids Mode PIN removes it.
 *
 * The PIN dialog is NOT shown before the child earned it: an escape key must be
 * pressed {@link KidsTimeUpKeyGate#REQUIRED_PRESSES} times within
 * {@link KidsTimeUpKeyGate#WINDOW_MS} (see KidsTimeUpKeyGate). One stray BACK used to
 * pop the PIN prompt outright, which is exactly the thing the gate is meant to hide.
 *
 * The state is in-memory only (never persisted) on purpose: a locked screen must
 * never come back after an app restart — see the v1.2.0/v1.2.4 poisoned-prefs black
 * screen lesson in KidsMigration. A restart simply starts a fresh, unlocked app; the
 * daily counter itself stays persisted, so the next video attempt re-arms the lock.
 *
 * NOTE: the lock is never armed without an enabled PIN — otherwise nobody, not even
 * the parent, could get past the black screen.
 */
public class KidsTimeUpLock {
    private static final String TAG = KidsTimeUpLock.class.getSimpleName();

    // A PIN dialog owns its own window, so keys don't reach the activity while it is
    // open; this throttle only prevents stacking dialogs. It MUST expire on its own:
    // if the dialog fails to show (BadTokenException is swallowed inside
    // SimpleEditDialog), a permanent "shown" flag would trap the child on black with
    // no way to ever type the PIN.
    private static final long PIN_DIALOG_THROTTLE_MS = 30_000L;

    private static boolean sLocked;
    private static long sPinDialogShownAtMs;

    private KidsTimeUpLock() {
    }

    /**
     * @return true when the black screen may only be left with the PIN.
     */
    public static boolean isLocked() {
        return sLocked;
    }

    /**
     * @return true when force stop is armed: Kids Mode on, the "stop immediately"
     *         switch on, a PIN available to unlock the screen with, AND no active
     *         parent session.
     *
     * Why the parent-session bypass: entering the correct PIN at the gate calls
     * {@link KidsPinGate#unlock()}, which — exactly like everywhere else in the app
     * (Settings, kiosk exit) — grants a short authenticated window. Without this bypass
     * the very next video would re-lock immediately (the daily quota is still full),
     * trapping even the parent who just typed the PIN. The parent's real controls to
     * grant more watching are "+N min" (extend) or turning Kids Mode off.
     *
     * A child who doesn't know the PIN can never set that window, so the guarantee
     * "expired → cannot leave the black screen without the PIN" still holds.
     */
    public static boolean isForceStopActive(Context context) {
        if (context == null || KidsPinGate.isUnlocked()) {
            return false;
        }

        KidsModeData data = KidsModeData.instance(context);

        return data.isEnabled() && data.isForceStopOnExpire() && data.isPinEnabled();
    }

    /**
     * Arm the lock and cover the given activity (may be null: the next resumed
     * activity gets covered by {@link #applyOnResume} instead).
     */
    public static void arm(Activity activity) {
        armState();

        if (activity != null) {
            KidsScreenHelper.showBlackScreen(activity);
        }

        Log.d(TAG, "Time-up lock armed");
    }

    /**
     * Arm the gate WITHOUT covering a view yet — used while the fade transition is still
     * running, so a video that finishes loading in the meantime cannot clear the screen.
     */
    public static void armState() {
        sLocked = true;
        KidsTimeUpKeyGate.resetSequence(); // a fresh time-out costs a fresh 10 presses
    }

    /**
     * Called from MotherActivity.onResume: re-apply the overlay after the playback
     * activity went away, so the child never lands on a visible playlist.
     */
    public static void applyOnResume(Activity activity) {
        if (!sLocked || activity == null) {
            return;
        }

        if (!KidsScreenHelper.isBlackScreenShown(activity)) {
            KidsScreenHelper.showBlackScreen(activity);
            Log.d(TAG, "Time-up lock re-applied on %s", activity.getClass().getSimpleName());
        }

        // KIDS: a mash that completed while no activity could host the dialog (the kiosk
        // key guard swallows HOME even when the app is off screen) is still armed — this
        // resume is the moment it can finally be answered. A null event never counts as a
        // press (isEscapePress(null) is false), so this cannot re-arm anything by itself.
        if (KidsTimeUpKeyGate.isArmed()) {
            Log.d(TAG, "Time-up mash armed from the key guard: asking for the PIN on resume");
            onKeyPress(activity, null);
        }
    }

    /**
     * Called from MotherActivity.dispatchKeyEvent while the lock is armed: swallow the
     * key and keep the screen covered. The PIN dialog is opened only once the mash gate
     * lets it through ({@link KidsTimeUpKeyGate}).
     *
     * @param event the key event being swallowed (non-escape keys are still swallowed —
     *              silently, they just never reveal the PIN dialog)
     */
    public static void onKeyPress(Activity activity, KeyEvent event) {
        if (activity == null) {
            return;
        }

        // Re-cover first: a dialog, a transition or a resumed activity may have dropped it
        KidsScreenHelper.showBlackScreen(activity);

        final KidsModeData data = KidsModeData.instance(activity);

        // Release without asking when the gate no longer applies — Kids Mode, force stop
        // or the PIN was turned off meanwhile. Never trap anybody.
        if (!isForceStopActive(activity)) {
            release(activity);
            return;
        }

        if (isEscapePress(event)) {
            KidsTimeUpKeyGate.onEscapeKeyPressed();
        }

        if (!KidsTimeUpKeyGate.isArmed()) {
            return; // below the threshold: NOTHING shows, the screen stays black
        }

        if (activity.isFinishing() || activity.isDestroyed()) {
            // No window to host the dialog: leave the gate armed (the mash is not lost) and
            // do NOT burn the throttle — the next usable activity consumes it (applyOnResume).
            return;
        }

        if (System.currentTimeMillis() - sPinDialogShownAtMs < PIN_DIALOG_THROTTLE_MS) {
            return; // dialog already open (or its failure is still fresh); stays armed
        }

        sPinDialogShownAtMs = System.currentTimeMillis();

        // The dialog is about to own the key input: a second sequence must be earned
        // again, so a cancel/BACK on the dialog does not pop it back up for free.
        KidsTimeUpKeyGate.resetSequence();

        SimpleEditDialog.showPassword(
                activity,
                activity.getString(R.string.kids_enter_pin),
                activity.getString(R.string.kids_time_up_pin_hint),
                null,
                newValue -> {
                    if (Utils.passwordMatch(data.getPin(), newValue)) {
                        // One unlock for the whole parent session, exactly like KidsPinGate:
                        // opening Settings right after must not ask again.
                        KidsPinGate.unlock();
                        release(activity);
                        return true;
                    }

                    MessageHelpers.showMessage(activity, R.string.kids_wrong_pin);
                    return false; // keep the dialog open, the screen stays black
                },
                () -> {
                    // Cancel/BACK: still locked, still black. The throttle is cleared so the
                    // gate can arm again — but a fresh 10 escapes are required, the PIN does
                    // not come back for free (KidsTimeUpKeyGate.resetSequence above).
                    // NOTE: this also fires after a SUCCESSFUL unlock (dismiss), hence the
                    // sLocked guard — re-covering there would trap the parent who just typed
                    // the correct PIN.
                    sPinDialogShownAtMs = 0;

                    if (sLocked) {
                        KidsScreenHelper.showBlackScreen(activity);
                    }
                });
    }

    /**
     * KIDS: one real escape press — the DOWN edge, not the auto-repeat tail of a held
     * key (holding BACK for two seconds must NOT count as ten presses), and only for the
     * keys a child would use to leave the app ({@link KidsTimeUpKeyGate#isEscapeKey}).
     *
     * Single definition on purpose: both the activity path and the guard-swallowed path
     * ({@link #onGuardEscapeKey}) go through it.
     */
    public static boolean isEscapePress(KeyEvent event) {
        return event != null
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && KidsTimeUpKeyGate.isEscapeKey(event.getKeyCode());
    }

    /**
     * KIDS: called by {@link KioskKeyGuardService} for the escape keys it swallows while
     * the time-up gate is armed. With kiosk on, HOME / RECENTS never reach an activity —
     * without this path the counter would only ever see BACK and "press HOME 10 times"
     * would stay silent forever.
     *
     * The guard service shares this process (no android:process in the manifest), so the
     * in-memory lock state and the gate below are the live ones.
     */
    public static void onGuardEscapeKey(KeyEvent event) {
        if (!sLocked || !isEscapePress(event)) {
            return;
        }

        if (!KidsTimeUpKeyGate.onEscapeKeyPressed()) {
            return; // below the threshold: NOTHING shows, the screen stays black
        }

        Activity activity = MotherActivity.getResumedActivity();

        if (activity == null) {
            // Stay armed: the sequence is not lost. The next key that reaches an activity
            // (or the next resume, see KidsTimeUpLock.applyOnResume) opens the dialog — never
            // trap a parent mid-mash.
            Log.d(TAG, "Time-up mash complete, no resumed activity to ask for the PIN on");
            return;
        }

        // Leave the input-filter callback before adding a window: this runs for EVERY key on
        // the device, and showing an AlertDialog while this very HOME event is still being
        // dispatched can hand it a stray event / lose focus on some TV builds.
        Utils.post(() -> {
            if (sLocked) {
                onKeyPress(activity, event);
            }
        });
    }

    /**
     * Drop the lock (and the overlay when an activity is given).
     */
    public static void release(Activity activity) {
        sLocked = false;
        sPinDialogShownAtMs = 0;
        KidsTimeUpKeyGate.resetSequence();

        if (activity != null) {
            KidsScreenHelper.hideBlackScreen(activity);
            KidsScreenHelper.clearPendingScreenOff();
        }

        Log.d(TAG, "Time-up lock released");
    }

    /**
     * Drop the lock without touching any view (new day, Kids Mode off, session extended).
     * A still visible overlay is removed by the next key press, which is then no longer
     * PIN-gated.
     */
    public static void releaseStateOnly() {
        sLocked = false;
        sPinDialogShownAtMs = 0;
        KidsTimeUpKeyGate.resetSequence();
    }
}
