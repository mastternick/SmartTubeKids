package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.content.Context;
import android.view.KeyEvent;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import java.lang.ref.WeakReference;

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
 * pressed {@link KidsTimeUpKeyGate#REQUIRED_PRESSES} times, each within
 * {@link KidsTimeUpKeyGate#GAP_MAX_MS} of the previous press (see KidsTimeUpKeyGate). One stray BACK used to
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
    private static final long GATE_SESSION_MS = 10 * 60_000L;
    private static final long ROLLOVER_MIN_MS = 1_000L; // clamp for a boundary that rounds to now

    private static boolean sLocked;
    private static long sPinDialogShownAtMs;
    private static long sGateUnlockedUntilMs;
    // The authorization was granted while the quota was exhausted (the time-up gate): only then
    // does it also suspend the hard stop. A Lock-at-end unlock with quota left must not.
    private static boolean sGateBypassesTimeUp;
    // Armed-state flag: mile overlay vs classic black screen. Not the pref — a settings change mid-lock
    // cannot swap the art under the child; next arm re-reads the pref.
    private static boolean sMileMarkerShown;
    // KIDS v1.9.4: the quota gate opens itself at the end of the reset window (no PIN).
    // One-shot task + the context it was scheduled with. sQuotaGate distinguishes the
    // quota gate (rollover applies) from Lock-at-end (it does not).
    private static final Runnable sRolloverTask = KidsTimeUpLock::onRolloverCheck;
    private static WeakReference<Context> sRolloverContext;
    private static boolean sQuotaGate;

    private KidsTimeUpLock() {
    }

    /**
     * @return true when the parent explicitly unlocked a gate with a valid PIN.
     *         Separated from the general settings session (KidsPinGate.isUnlocked()) so
     *         configuring settings does not deactivate timer expiration.
     */
    public static boolean isGateUnlocked() {
        return getGateAuthorizationRemainingMs() > 0;
    }

    private static long getGateAuthorizationRemainingMs() {
        return Math.max(0, sGateUnlockedUntilMs - System.currentTimeMillis());
    }

    /**
     * @return how long the hard stop stays suspended by a time-up gate unlock (0 = not suspended).
     */
    public static long getTimeUpBypassRemainingMs() {
        return sGateBypassesTimeUp ? getGateAuthorizationRemainingMs() : 0;
    }

    /**
     * Authorizes proceeding past a gate after a valid PIN was verified at the gate. When the
     * quota is exhausted at that moment, the hard stop is suspended for the same window so the
     * parent who just typed the PIN is not re-locked by the very next video.
     */
    public static void unlockGateSession(Context context) {
        sGateUnlockedUntilMs = System.currentTimeMillis() + GATE_SESSION_MS;
        sGateBypassesTimeUp = isQuotaExhausted(context);
    }

    /** Invalidate gate authorization when its protecting settings change. */
    public static void invalidateGateAuthorization() {
        sGateUnlockedUntilMs = 0;
        sGateBypassesTimeUp = false;
    }

    /**
     * @return true when the black screen may only be left with the PIN.
     */
    public static boolean isLocked() {
        return sLocked;
    }

    /**
     * @return true when force stop is armed: Kids Mode on, the "stop immediately"
     *         switch on, a PIN available to unlock the screen with, AND the hard stop is not
     *         suspended by a time-up gate unlock.
     *
     * Why the bypass: entering the correct PIN at the time-up gate (quota exhausted) grants a
     * short authenticated window to leave the gate without an immediate PIN re-lock. It does
     * NOT lift the quota: onNewVideo still ends playback with the calm exit while the quota is
     * exhausted; the parent grants more time ("+N min") or turns the limit off. A gate unlock
     * with quota left (Lock at end) does not suspend the hard stop. General parent settings
     * sessions (KidsPinGate.isUnlocked()) never deactivate this check.
     */
    public static boolean isForceStopActive(Context context) {
        return isKidsGateBase(context)
                && KidsModeData.instance(context).isForceStopOnExpire()
                && getTimeUpBypassRemainingMs() <= 0;
    }

    /**
     * KIDS v1.7.5: the end-of-video lock is armed — Kids Mode on, a PIN to unlock with,
     * no gate unlock in effect, the parent offered the mode in Kids Mode
     * ({@link KidsModeData#isLockAtVideoEnd}) AND it is the player's current playback
     * mode. The mode value alone is not enough: turning the Kids switch off must disarm
     * the lock even if the selection lingers in {@code PlayerData}.
     */
    public static boolean isLockAtEndActive(Context context) {
        return isKidsGateBase(context) && !isGateUnlocked() && isLockAtEndModeSelected(context);
    }

    /**
     * KIDS: does the PIN gate apply at all right now? True when EITHER reason is armed —
     * the quota force stop ({@link #isForceStopActive}) or the end-of-video lock
     * ({@link #isLockAtEndActive}).
     *
     * Used by the gate itself (releasing on the first key press when no reason is armed
     * any more, so nobody is ever trapped).
     */
    public static boolean isGateActive(Context context) {
        return isForceStopActive(context) || isLockAtEndActive(context);
    }

    /**
     * KIDS: may a Kids-mode gate arm at all? Kids Mode on + a PIN to unlock with.
     */
    private static boolean isKidsGateBase(Context context) {
        if (context == null) {
            return false;
        }

        KidsModeData data = KidsModeData.instance(context);

        return data.isEnabled() && data.isPinEnabled();
    }

    /** Persisted quota is used up (a window whose pause has run out counts as fresh). */
    private static boolean isQuotaExhausted(Context context) {
        if (context == null) {
            return false;
        }

        KidsModeData data = KidsModeData.instance(context);

        if (data.getTimerMinutes() <= 0) {
            return false;
        }

        long limitMs = KidsQuotaWindow.limitMs(data.getTimerMinutes(), data.getBonusMs());
        // KIDS v1.9.7: only an exhausted quota with a pause anchored at exhaustion can be reset
        // by the clock, so a stored anchor without the inCooldown flag (parent lowered the timer,
        // or a crash between the counter and anchor saves) is not "expired" — the budget stays spent.
        long usedMs = KidsQuotaWindow.isWindowExpired(data.getWindowStartMs(), System.currentTimeMillis(),
                data.getResetIntervalMinutes(), data.getUsedMs(), limitMs, data.isInCooldown()) ? 0 : data.getUsedMs();

        return usedMs >= limitMs;
    }

    /** KIDS v1.7.5: the parent offered the end-of-video lock AND the player is in that mode. */
    private static boolean isLockAtEndModeSelected(Context context) {
        return KidsModeData.instance(context).isLockAtVideoEnd()
                && PlayerData.instance(context).getPlaybackMode() == PlayerConstants.PLAYBACK_MODE_LOCK_AT_END;
    }

    /**
     * Arm the lock and cover the given activity (may be null: the next resumed
     * activity gets covered by {@link #applyOnResume} instead).
     */
    public static void arm(Activity activity) {
        armState();
        sMileMarkerShown = false;
        sQuotaGate = false; // KIDS v1.9.4: Lock-at-end is not quota-driven — no rollover task

        if (activity != null) {
            cover(activity);
        }

        Log.d(TAG, "Time-up lock armed (classic black)");
    }

    /**
     * KIDS v1.9: with {@code useMileMarker} the time-up black screen is replaced by the
     * night-road overlay reflecting the real reset-window progress. The gate semantics
     * (swallow keys, 10-press mash, PIN) are identical; Lock at end keeps classic black.
     */
    public static void arm(Activity activity, boolean useMileMarker) {
        armState();
        sMileMarkerShown = useMileMarker;

        if (activity != null) {
            cover(activity);
        }

        sQuotaGate = true;

        // KIDS v1.9.4: the quota gate must open BY ITSELF when the reset window rolls
        // over — nothing plays while the child sits on it, so the controller's tickle
        // (the only other rollover caller) never fires. One-shot at the window boundary,
        // not a poll.
        if (activity != null) {
            scheduleRolloverCheck(activity);
        }

        Log.d(TAG, "Time-up lock armed (mileMarker=%s)", sMileMarkerShown);
    }

    /** The art chosen at arm time, re-applied by every re-cover path. */
    private static void cover(Activity activity) {
        if (sMileMarkerShown) {
            MileMarkerOverlay.show(activity);
        } else {
            KidsScreenHelper.showBlackScreen(activity);
        }
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

        // KIDS v1.9.4: re-anchor the rollover one-shot on every resume — a TV that slept
        // past the boundary wakes up here; a quota gate armed without an activity gets its
        // first schedule. scheduleRolloverCheck removes any previous posting, no stacking.
        if (sQuotaGate) {
            scheduleRolloverCheck(activity);
        }

        if (sMileMarkerShown) {
            // Idempotent (tick chain guarded) and re-hosts the view on the new activity.
            MileMarkerOverlay.show(activity);
        } else if (!KidsScreenHelper.isBlackScreenShown(activity)) {
            KidsScreenHelper.showBlackScreen(activity);
            Log.d(TAG, "Time-up lock re-applied on %s", activity.getClass().getSimpleName());
        }

        // KIDS: a mash that completed while no activity could host the dialog (the kiosk
        // key guard swallows HOME even when the app is off screen) is still armed — this
        // resume is the moment it can finally be answered. Posted on purpose:
        // ActivityThread.handleResumeActivity runs onResume BEFORE wm.addView(decor), so
        // showing an AlertDialog inline here would hit BadTokenException on a fresh launch
        // (exactly the guard case: bringAppBack starts the activity from cold) and, because
        // the gate is consumed before showPassword, the earned 10 presses would be lost.
        // A null event never counts as a press (isEscapePress(null) is false), so this
        // cannot re-arm anything by itself.
        if (KidsTimeUpKeyGate.isArmed()) {
            Log.d(TAG, "Time-up mash armed from the key guard: asking for the PIN on resume");
            Utils.post(() -> {
                if (sLocked) {
                    onKeyPress(activity, null);
                }
            });
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
        cover(activity);

        final KidsModeData data = KidsModeData.instance(activity);

        // Release without asking when the gate no longer applies — Kids Mode, force stop, the
        // end-of-video mode or the PIN was turned off meanwhile. Never trap anybody.
        if (!isGateActive(activity)) {
            release(activity);
            return;
        }

        if (isEscapePress(event)) {
            KidsTimeUpKeyGate.onEscapeKeyPressed();
        }

        if (!KidsTimeUpKeyGate.isArmed()) {
            return; // below the threshold: NOTHING shows, the screen stays black
        }

        // KIDS v1.9.6: only the QUOTA time-up screen honours the opt-out — a Lock-at-end
        // lock keeps requiring the PIN (the switch text promises the time limit only).
        if (sQuotaGate && !data.isPinRequiredAfterMash()) {
            // Kiosk still wins: never open the app without the PIN when the key guard is on.
            if (KioskModeManager.isKioskEnabled(activity)) {
                Log.d(TAG, "Kiosk enabled: PIN still required to leave the time-up screen");
            } else {
                // Deliberately NO KidsPinGate.unlock() here: that opens a 10-minute parent
                // session which skips the PIN for the Kids Mode settings dialog, so ten
                // mashed BACK presses would let a child switch Kids Mode / the timer / the
                // PIN off. The presses only drop the time-up screen, nothing else.
                release(activity);
                Log.d(TAG, "Mash complete and PIN not required: time-up gate released");
                return;
            }
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
                        unlockGateSession(activity);
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
                        cover(activity);
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
        sMileMarkerShown = false;
        sQuotaGate = false;
        sRolloverContext = null;
        Utils.removeCallbacks(sRolloverTask);
        KidsTimeUpKeyGate.resetSequence();

        if (activity != null) {
            KidsScreenHelper.hideBlackScreen(activity);
            MileMarkerOverlay.hide(activity);
            KidsScreenHelper.clearPendingScreenOff();
        }

        Log.d(TAG, "Time-up lock released");
    }

    /**
     * Drop the lock without touching any view (new day, Kids Mode off, session extended).
     * A still visible overlay is removed here if there's a resumed activity to host it.
     */
    public static void releaseStateOnly() {
        sLocked = false;
        sPinDialogShownAtMs = 0;
        sQuotaGate = false;
        sRolloverContext = null;
        Utils.removeCallbacks(sRolloverTask);
        invalidateGateAuthorization();
        KidsTimeUpKeyGate.resetSequence();

        // KIDS v1.9.1: clear mile marker overlay if still on screen after lock release
        Activity activity = MotherActivity.getResumedActivity();
        if (activity != null && MileMarkerOverlay.isShown(activity)) {
            MileMarkerOverlay.clearFor(activity);
        }
    }

    /**
     * KIDS v1.9.4–1.9.5: (re)arm the one-shot that fires when the cooldown pause ends.
     * removeCallbacks first, so arm + every resume cannot stack chains.
     *
     * KIDS v1.9.5: the boundary is the STORED anchor + interval. Here that anchor is always the
     * exhaustion anchor written by {@link KidsModeData#anchorCooldown(long)} (the early return
     * in the body below requires an exhausted quota WITH the flag), so the gate opens exactly
     * one interval after the quota ran out. A hand-edited or corrupted blob with a 0 anchor still
     * recovers: the ROLLOVER_MIN_MS clamp fires the one-shot and {@link #onRolloverCheck()} treats
     * anchor 0 as expired.
     *
     * KIDS v1.9.7: with the pause starting at exhaustion, an unspent quota has NO boundary at
     * all, and neither has an anchor the {@code inCooldown} flag does not vouch for (parent
     * lowered the timer, or a crash between the counter save and the anchor save). Nothing is
     * scheduled unless {@code used >= limit} AND the flag is set — otherwise the one-shot would
     * fire into {@link #onRolloverCheck()} with "not expired yet" and re-post itself every
     * ROLLOVER_MIN_MS for as long as the gate is open.
     *
     * No limit set (timer 0) means there is no gate to open, so nothing is scheduled.
     */
    private static void scheduleRolloverCheck(Context context) {
        if (context == null) {
            return;
        }

        KidsModeData data = KidsModeData.instance(context);

        if (data.getTimerMinutes() <= 0) {
            return; // no watch limit: nothing rolls over, nothing to unlock
        }

        long limitMs = KidsQuotaWindow.limitMs(data.getTimerMinutes(), data.getBonusMs());

        if (data.getUsedMs() < limitMs || !data.isInCooldown()) {
            // The quota was not reached yet, or no pause was anchored at exhaustion: the interval
            // is not a running pause, so there is no boundary to wait for (and the controller
            // will anchor one on exhaustion). Without the flag check an ordinary anchor would
            // re-post this one-shot every ROLLOVER_MIN_MS for as long as the gate is up.
            Log.d(TAG, "Rollover check skipped: no pause anchored (%s/%s ms, inCooldown=%s)",
                    data.getUsedMs(), limitMs, data.isInCooldown());
            return;
        }

        // The host activity is destroyed right after arming (presenter.forceFinish()), so
        // anchor on the application context — a WeakReference to the activity would clear
        // and the check would silently never run if the child sleeps on the gate.
        sRolloverContext = new WeakReference<>(context.getApplicationContext());

        // KIDS v1.9.5: the anchor IS the cooldown start — written by the controller at the
        // expiry instant, before this call. Boundary = anchor + interval; no clock grid.
        long now = System.currentTimeMillis();
        int intervalMinutes = data.getResetIntervalMinutes();
        long anchor = data.getWindowStartMs();
        long boundaryMs = anchor + KidsQuotaWindow.intervalMs(intervalMinutes);
        long delayMs = Math.max(ROLLOVER_MIN_MS, boundaryMs - now);

        Utils.removeCallbacks(sRolloverTask);
        Utils.postDelayed(sRolloverTask, delayMs);
    }

    /**
     * KIDS v1.9.4: the reset window has moved on — write the fresh quota and open the gate
     * WITHOUT a PIN, so Back/Home work again. If the boundary shifted meanwhile (interval
     * change, backwards clock jump) the window is not expired yet: re-schedule instead.
     */
    private static void onRolloverCheck() {
        if (!sLocked || !sQuotaGate) {
            return; // released meanwhile
        }

        Context context = sRolloverContext != null ? sRolloverContext.get() : null;

        if (context == null) {
            Activity resumed = MotherActivity.getResumedActivity();

            if (resumed != null) {
                scheduleRolloverCheck(resumed);
            }

            return;
        }

        KidsModeData data = KidsModeData.instance(context);
        long now = System.currentTimeMillis();
        int intervalMinutes = data.getResetIntervalMinutes();
        long limitMs = KidsQuotaWindow.limitMs(data.getTimerMinutes(), data.getBonusMs());

        if (!KidsQuotaWindow.isWindowExpired(data.getWindowStartMs(), now, intervalMinutes,
                data.getUsedMs(), limitMs, data.isInCooldown())) {
            scheduleRolloverCheck(context); // boundary moved: wait for the real one
            return;
        }

        // KIDS v1.9.6: with kiosk enabled the gate never opens by itself — the parent must
        // enter the PIN. The window is deliberately NOT re-anchored here: doing so would
        // restart the Mile Marker car every interval while the screen stays locked. The
        // controller rolls the window on the next play (resetWindowIfNeeded), so nothing is
        // left insta-locked once the PIN is entered. The one-shot is not rescheduled.
        if (KioskModeManager.isKioskEnabled(context)) {
            Log.d(TAG, "Kiosk enabled: gate stays locked after reset, PIN required");
            return;
        }

        data.resetWindow(now);

        // View cleanup through release(): the mile scene / black screen must go with the gate.
        release(MotherActivity.getResumedActivity());

        Log.d(TAG, "Reset window rolled over while locked: gate opened without PIN");
    }
}
