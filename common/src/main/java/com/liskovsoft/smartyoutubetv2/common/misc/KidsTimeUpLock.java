package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.content.Context;

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
 * is a GATE: every key press is swallowed and answered with a PIN dialog, the
 * overlay survives the playback activity (re-applied on the resumed browse activity),
 * and only the correct Kids Mode PIN removes it.
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
    }

    /**
     * Called from MotherActivity.dispatchKeyEvent while the lock is armed: swallow the
     * key, keep the screen covered and ask for the PIN.
     */
    public static void onKeyPress(Activity activity) {
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

        if (System.currentTimeMillis() - sPinDialogShownAtMs < PIN_DIALOG_THROTTLE_MS) {
            return; // dialog already open (or its failure is still fresh)
        }

        sPinDialogShownAtMs = System.currentTimeMillis();

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
                    // Cancel/BACK: still locked, still black. Reset the throttle at once so
                    // the next key press can ask again.
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
     * Drop the lock (and the overlay when an activity is given).
     */
    public static void release(Activity activity) {
        sLocked = false;
        sPinDialogShownAtMs = 0;

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
    }
}
