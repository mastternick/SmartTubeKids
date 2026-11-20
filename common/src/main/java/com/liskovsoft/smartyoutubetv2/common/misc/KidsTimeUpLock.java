package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.appcompat.app.AlertDialog;

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
 * KIDS: the time-up gate — the PIN-locked screen shown when the watch limit expires with
 * "stop immediately" (force stop) enabled — also, since v1.11, inside the bedtime window
 * even with that switch off ({@link #isBedtimeGateActive}).
 *
 * Difference from the calm exit: there the black screen is only a transition — the first
 * key press removes it and the child is back on the playlist. Here the screen is a GATE:
 * every key press is swallowed and the art stays (classic black, the Mile Marker car or the
 * night scene), and the overlay survives the playback activity (re-applied on the resumed
 * browse activity).
 *
 * What releases it (KIDS v1.15):
 *  - the car / classic gate once the reset pause is over: ONE escape press (BACK, or
 *    HOME/RECENTS through the guard) writes the fresh quota and drops the screen — no PIN,
 *    the pause was already served. The gate no longer opens itself at the boundary: the car
 *    stays parked at lamp 10 until that press.
 *  - the night art: a clock lock — the 10-press mash + PIN, or the 06:00 boundary.
 *  - the correct Kids Mode PIN at any other time (see the mash gate below).
 *
 * The PIN dialog is NOT shown before the child earned it: an escape key must be
 * pressed {@link KidsTimeUpKeyGate#REQUIRED_PRESSES} times, each within
 * {@link KidsTimeUpKeyGate#GAP_MAX_MS} of the previous press (see KidsTimeUpKeyGate). One stray BACK used to
 * pop the PIN prompt outright, which is exactly the thing the gate is meant to hide. The prompt
 * itself is non-cancelable: BACK cannot hide it, only a PIN or the Cancel button closes it.
 *
 * State: the in-memory {@code sLocked} is re-derived from the PERSISTED quota on the first resume
 * of each process ({@link #restoreIfDue}, v1.13) — a restart must not hand the playlist back while
 * the quota is spent, and since v1.15 an unreleased night gate survives it too. The overlay itself
 * is never persisted; {@link #applyOnResume} re-hosts it. The counter stays persisted either way.
 *
 * NOTE: the lock is never armed without an enabled PIN — otherwise nobody, not even
 * the parent, could get past the screen.
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
    // Armed-state art: mile overlay, night scene or classic black screen. Not the pref — a settings
    // change mid-lock cannot swap the art under the child; next arm re-reads the pref.
    public static final int SCENE_PLAIN = 0;
    public static final int SCENE_MILE_MARKER = 1;
    public static final int SCENE_NIGHT = 2;
    private static int sScene;
    // KIDS v1.10: uptime the night scene started at — a re-cover continues the same five-minute run.
    private static long sNightStartMs;
    // KIDS v1.11: the night lock — a quota gate armed inside the bedtime window even with
    // "Stop immediately" OFF. The hard stop is not the reason there, so isGateActive needs this
    // third term; cleared wherever the gate is dropped, planted by armQuotaGate for SCENE_NIGHT.
    private static boolean sBedtimeLock;
    // KIDS v1.11: the live PIN dialog. The night scene must not pull the backlight to 0 under it
    // (the parent would lose a prompt they earned), so its countdown waits the dialog out. A weak
    // reference to the real dialog — not a boolean or a timeout — because SimpleEditDialog swallows
    // a BadTokenException and returns without ever dismissing: neither a flag nor a timer can tell
    // "still on screen" from "never showed", `isShowing()` can.
    private static WeakReference<AlertDialog> sPinDialog;
    // KIDS v1.13: the PIN prompt used to accept unlimited guesses — a child could simply
    // brute force a four-digit PIN. Five wrong tries close the prompt and pause the prompt
    // for PIN_LOCKOUT_MS; the gate itself stays armed, so the screen never opens early.
    private static final int MAX_PIN_ATTEMPTS = 5;
    private static final long PIN_LOCKOUT_MS = 5 * 60_000L;
    private static int sWrongPinAttempts;
    private static long sPinLockoutUntilMs;
    // KIDS v1.9.4: the quota gate is released at the end of the reset window (no PIN).
    // One-shot task + the context it was scheduled with. sQuotaGate distinguishes the
    // quota gate (rollover applies) from Lock-at-end (it does not).
    private static final Runnable sRolloverTask = KidsTimeUpLock::onRolloverCheck;
    private static WeakReference<Context> sRolloverContext;
    private static boolean sQuotaGate;
    // KIDS v1.15: the night art is a clock lock now — it never opens at the reset boundary, only by
    // the mash + PIN or by the 06:00 boundary. One-shot, re-armed on every resume.
    private static final long MORNING_MIN_MS = 1_000L;
    private static final long MORNING_RETRY_MS = 60_000L; // the TV was asleep at the boundary
    private static final Runnable sMorningTask = KidsTimeUpLock::onMorningCheck;
    private static WeakReference<Context> sNightContext;
    // KIDS v1.13: the persisted quota is inspected exactly once per PROCESS. onResume runs for
    // every activity (the playback one included), and at bedtime with force stop OFF a mid-clip
    // resume (dialog, PIP, settings) would otherwise re-arm the night gate and cut a clip the
    // parent allowed to finish. Not reset on release: this is per process, not per gate.
    private static boolean sRestoreChecked;

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

    /** KIDS v1.11: is the PIN dialog on screen right now? */
    public static boolean isPinDialogOpen() {
        AlertDialog dialog = sPinDialog != null ? sPinDialog.get() : null;
        return dialog != null && dialog.isShowing();
    }

    /**
     * KIDS v1.15: close the gate's PIN prompt. It is non-cancelable (BACK must not dismiss it), so
     * every gate release has to dismiss it explicitly — otherwise it would float over the playlist
     * with a dead BACK. Safe when no prompt ever showed (BadTokenException path leaves null).
     */
    private static void dismissGatePrompt() {
        AlertDialog dialog = sPinDialog != null ? sPinDialog.get() : null;

        if (dialog != null) {
            dialog.dismiss();
        }

        sPinDialog = null;
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
     * KIDS: does the PIN gate apply at all right now? True when EITHER of the three reasons is
     * armed — the quota force stop ({@link #isForceStopActive}), the end-of-video lock
     * ({@link #isLockAtEndActive}) or the night lock ({@link #isBedtimeGateActive}).
     *
     * Used by the gate itself (releasing on the first key press when no reason is armed
     * any more, so nobody is ever trapped).
     */
    public static boolean isGateActive(Context context) {
        return isForceStopActive(context) || isLockAtEndActive(context) || isBedtimeGateActive(context);
    }

    /**
     * KIDS v1.11: the gate armed inside the bedtime window holds even when the hard stop is off.
     * Same base as every other reason (`isKidsGateBase`: Kids Mode + a PIN to unlock with), so
     * turning either off still releases it — the "never trap" invariant stays intact.
     *
     * Deliberately NO quota predicate here: the gate must behave exactly like the hard-stop gate
     * and `isForceStopActive` does not consult the quota either. Every release path clears
     * `sBedtimeLock` (PIN unlock, per-step `openResetGate`, the v1.15 morning boundary, a parent
     * bonus via `releaseStateOnly`, Kids Mode or PIN switched off), and after the reset pause the
     * car/classic gate goes through the `sQuotaGate && isResetWindowDone(activity)` branch of
     * `onKeyPress`/`onGuardEscapeKey`, which now requires an escape press (the night art never
     * takes it). A quota predicate would instead release the gate on the first key press of a
     * rolled-over window WITHOUT writing the fresh quota, so the child would land on a screen that
     * re-locks on the next play attempt.
     */
    private static boolean isBedtimeGateActive(Context context) {
        return sBedtimeLock
                && !isGateUnlocked()
                && isKidsGateBase(context);
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
                data.getResetIntervalMinutes(), data.getUsedMs(), limitMs, data.isInCooldown(),
                data.getLastWatchMs()) ? 0 : data.getUsedMs();

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
        sScene = SCENE_PLAIN;
        sQuotaGate = false; // KIDS v1.9.4: Lock-at-end is not quota-driven — no rollover task

        if (activity != null) {
            cover(activity);
        }

        Log.d(TAG, "Time-up lock armed (classic black)");
    }

    /**
     * KIDS v1.9: with {@code useMileMarker} the time-up black screen is replaced by the
     * night-road overlay reflecting the real reset-window progress. The gate semantics
     * (swallow keys, 10-press mash, PIN) are identical; Lock at end keeps classic black
     * (v1.11: inside the bedtime window it gets the night art through
     * {@link #armNightArt(Activity)}, still without the quota-gate semantics).
     */
    public static void arm(Activity activity, boolean useMileMarker) {
        armQuotaGate(activity, useMileMarker ? SCENE_MILE_MARKER : SCENE_PLAIN);
    }

    /**
     * KIDS v1.10: the quota expired inside the bedtime window — the night scene takes the slot
     * of the mile marker. The gate is exactly the same: keys swallowed, 10-press mash, PIN,
     * automatic opening at the end of the reset.
     */
    public static void armNight(Activity activity) {
        armQuotaGate(activity, SCENE_NIGHT);
    }

    /**
     * KIDS v1.11: the night ART without the quota-gate semantics — used by "Lock at end" inside the
     * bedtime window. `sQuotaGate` stays false and `sBedtimeLock` stays false on purpose: this lock
     * is driven by the playback mode (`isLockAtEndActive`), so the reset boundary must NOT open it by
     * itself (the classic lock-at-end black is a PIN lock, not a quota gate) and the gate reason must
     * not outlive the mode. The art, anchor and re-cover behaviour are identical to a night quota
     * gate: five minutes, then `KidsScreenHelper.showScreenOff`.
     */
    public static void armNightArt(Activity activity) {
        armState();
        sScene = SCENE_NIGHT;
        sNightStartMs = SystemClock.elapsedRealtime();
        sQuotaGate = false;

        if (activity != null) {
            cover(activity);
        }

        Log.d(TAG, "Lock-at-end armed (night art, no quota gate)");
    }

    /**
     * The single quota-gate arm path, so the two atmospheres cannot drift: {@code sQuotaGate}
     * and the rollover one-shot give the no-PIN branch and the automatic opening at the reset.
     */
    private static void armQuotaGate(Activity activity, int scene) {
        armState();
        sScene = scene;
        sNightStartMs = scene == SCENE_NIGHT ? SystemClock.elapsedRealtime() : 0;
        sBedtimeLock = scene == SCENE_NIGHT;

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

        if (scene == SCENE_NIGHT && activity != null) {
            scheduleMorningCheck(activity);
        }

        Log.d(TAG, "Time-up lock armed (scene=%s)", scene);
    }

    /** The art chosen at arm time, re-applied by every re-cover path. */
    private static void cover(Activity activity) {
        if (sScene == SCENE_NIGHT) {
            // Past its five minutes the scene falls back to the switched-off screen on its own.
            KidsNightScene.show(activity, SystemClock.elapsedRealtime() - sNightStartMs);
        } else if (sScene == SCENE_MILE_MARKER) {
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
        sBedtimeLock = false; // a fresh arm of any scene starts without it
        sWrongPinAttempts = 0; // KIDS v1.13: a fresh gate costs fresh guesses
        sPinLockoutUntilMs = 0;
        KidsTimeUpKeyGate.resetSequence(); // a fresh time-out costs a fresh 10 presses
    }

    /**
     * KIDS v1.13: re-arm the gate from the PERSISTED quota after a process death, a reboot or a
     * crash — the in-memory {@code sLocked} is otherwise the only memory of it, so a restart
     * used to hand the child the playlist back with the daily quota already spent.
     *
     * Only a gate that would be due anyway is re-created: the hard stop (force stop ON) or the
     * bedtime window (which holds even with force stop OFF — KIDS v1.11). The calm exit never
     * invents a gate. Everything else bails out and leaves the app unlocked, so the "never
     * trap" invariant holds: Kids Mode or the PIN off (no base), no watch limit, a quota with
     * time left, a pause that already elapsed (the controller owns that rollover), or a parent
     * gate session still running (a restart must not re-lock the parent who just typed the PIN).
     *
     * The art is chosen exactly like the controller's force-stop session: bedtime →
     * night scene, otherwise the mile marker if the parent picked it. {@link #armQuotaGate}
     * schedules the rollover one-shot, whose boundary is the persisted {@code windowStartMs}
     * (KIDS v1.15: the car then stays parked at lamp 10 until an escape press; the night art
     * additionally survives an elapsed pause and is released at the 06:00 boundary).
     *
     * KIDS v1.15: an unreleased night gate outlives the pause. Its raw persisted tuple
     * ({@code inCooldown} set, {@code used >= limit}) is the lock — a normal release writes
     * {@link KidsModeData#resetWindow(long)}, clearing both — so a restart at 02:00, hours after
     * the reset boundary, still restores the cat instead of handing over the playlist.
     *
     * KIDS v1.13: the check runs ONCE PER PROCESS ({@link #sRestoreChecked}). The first resume
     * after a process start is the only one that may invent a gate; later resumes only re-apply
     * an existing one ({@link #applyOnResume}). Without that, a mid-clip resume at bedtime with
     * "stop immediately" OFF would re-arm the night gate and cut a clip the parent let finish.
     */
    public static void restoreIfDue(Activity activity) {
        if (sRestoreChecked) {
            return; // the persisted quota was already judged on this process's first resume
        }

        if (sLocked || activity == null) {
            return;
        }

        sRestoreChecked = true;

        Context context = activity.getApplicationContext();

        if (!isKidsGateBase(context)) {
            return; // Kids Mode or PIN off: no gate may exist at all
        }

        if (isGateUnlocked()) {
            return; // the parent just unlocked a gate: a restart must not re-lock them
        }

        KidsModeData data = KidsModeData.instance(context);

        if (data.getTimerMinutes() <= 0) {
            return; // no watch limit: nothing to re-arm
        }

        // KIDS v1.15: the night art is released only by the mash + PIN or the morning boundary, so an
        // unreleased gate must survive a restart. Its persisted shape is `inCooldown` + `used >= limit`,
        // but that is ALSO the shape of a daytime pause, so the pause anchor must fall inside the night
        // window (`anchorCooldown` stores the stop/exhaustion instant in `windowStartMs`). A normal
        // release writes resetWindow, clearing both fields.
        boolean nightHeld = data.getWindowStartMs() > 0
                && data.isInCooldown()
                && data.getUsedMs() >= KidsQuotaWindow.limitMs(data.getTimerMinutes(), data.getBonusMs())
                && KidsBedtime.isNight(data.getBedtimeMinutes(),
                        KidsBedtime.minutesOfDay(data.getWindowStartMs()));

        if (!isQuotaExhausted(context) && !nightHeld) {
            return; // time left in the window
        }

        if (isResetWindowDone(context) && !nightHeld) {
            return; // the pause is over — the controller rolls the window over
        }

        // Exactly the controller's gate-due predicate (isForceStopActive || isBedtimeLockDue):
        // the night window holds even with "stop immediately" OFF (KIDS v1.11), so a restart at
        // 20:00 must restore the night scene instead of handing over the playlist.
        boolean bedtime = KidsBedtime.isNight(data.getBedtimeMinutes(),
                KidsBedtime.minutesOfDay(System.currentTimeMillis()));

        // KIDS v1.15: a held night gate is over once the clock is past 06:00 — the morning release
        // simply never ran because the box was off. Replenish and stay out instead of flashing the
        // cat for a moment on this launch.
        if (nightHeld && !bedtime) {
            data.resetWindow(System.currentTimeMillis());
            Log.d(TAG, "Night gate not restored: the morning boundary already passed");
            return;
        }

        if (!isForceStopActive(context) && !bedtime) {
            return; // the calm exit must never invent a gate
        }

        // KIDS v1.13: the reset boundary needs a real pause anchor.
        // scheduleRolloverCheck() bails out unless used >= limit AND inCooldown, and a cold
        // browse-only launch has no controller tick to write that flag yet (the controllers are
        // built from PlaybackPresenter.onViewInitialized, not from instance()). The persisted
        // boundary is the LAST WATCHED instant (v1.12) — never the moment the quota was crossed,
        // which with "stop immediately" OFF can be hours earlier and would credit the overrun as
        // pause. A legacy blob carries none, and the only honest boundary there is "now" — same
        // as the controller's own fallback. KIDS v1.15: the boundary no longer opens anything by
        // itself, but without the flag the gate would read as "no pause ever started".
        if (!data.isInCooldown()) {
            long now = System.currentTimeMillis();
            long lastWatchMs = data.getLastWatchMs();
            long boundary = lastWatchMs > 0 ? Math.max(lastWatchMs, data.getWindowStartMs()) : now;

            if (KidsQuotaWindow.isPauseElapsed(boundary, now, data.getResetIntervalMinutes())) {
                data.resetWindow(now); // the pause already ran out: fresh quota, no gate
                Log.d(TAG, "Time-up gate not restored: the pause already elapsed (boundary=%s)", boundary);
                return;
            }

            data.anchorCooldown(boundary);
            Log.d(TAG, "Time-up gate: pause boundary normalised (boundary=%s, lastWatch=%s)", boundary, lastWatchMs);
        }

        if (bedtime) { // nightHeld without bedtime already returned above
            armQuotaGate(activity, SCENE_NIGHT);
        } else {
            armQuotaGate(activity, data.isUseMileMarker() ? SCENE_MILE_MARKER : SCENE_PLAIN);
        }

        Log.d(TAG, "Time-up gate restored from the persisted quota (scene=%s)", sScene);
    }

    /**
     * Called from MotherActivity.onResume: re-apply the overlay after the playback
     * activity went away, so the child never lands on a visible playlist.
     */
    public static void applyOnResume(Activity activity) {
        // KIDS v1.13: a fresh process starts unlocked — re-derive the gate from the persisted
        // quota before the in-memory guard below (a restart, a reboot or a process death must
        // not hand the child the playlist back while the quota is spent).
        restoreIfDue(activity);

        if (!sLocked || activity == null) {
            return;
        }

        // KIDS v1.9.4: re-anchor the rollover one-shot on every resume — a TV that slept
        // past the boundary wakes up here; a quota gate armed without an activity gets its
        // first schedule. scheduleRolloverCheck removes any previous posting, no stacking.
        if (sQuotaGate) {
            scheduleRolloverCheck(activity);
        }

        if (sQuotaGate && sScene == SCENE_NIGHT) {
            scheduleMorningCheck(activity);
        }

        // The art chosen at arm time, re-hosted on the new activity (every show is idempotent
        // and guarded by its decor tag). The night scene continues its own 60 s run.
        cover(activity);
        Log.d(TAG, "Time-up lock re-applied on %s", activity.getClass().getSimpleName());

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

        // KIDS v1.15: the reset pause is over. The car stays parked at lamp 10 and the classic black
        // gate stays black until the child presses an escape key — then it opens with no PIN. The NIGHT
        // art is exempt: it keeps the mash+PIN and is released by the morning boundary.
        if (sQuotaGate && isResetWindowDone(activity)) {
            if (sScene != SCENE_NIGHT) {
                if (isEscapePress(event)) {
                    openResetGate(activity, activity);
                }
                return;
            }
        }

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
        // KIDS v1.13: and only the CLASSIC black screen does. The switch means "the ten
        // presses leave the plain time-up screen"; the car (mile marker) and the night
        // scene are the hard lock — they are never released without the PIN, so a parent
        // who has the opt-out on still cannot hand a child a key-combo exit from them.
        if (sQuotaGate && sScene == SCENE_PLAIN && !data.isPinRequiredAfterMash()) {
            // KIDS v1.9.8: the switch now wins over kiosk mode too. Kiosk keeps HOME/RECENTS
            // blocked anyway and no parent session is granted here, so the child gains nothing.
            // Deliberately NO KidsPinGate.unlock() here: that opens a 10-minute parent
            // session which skips the PIN for the Kids Mode settings dialog, so ten
            // mashed BACK presses would let a child switch Kids Mode / the timer / the
            // PIN off. The presses only drop the time-up screen, nothing else.
            release(activity);
            Log.d(TAG, "Mash complete and PIN not required: time-up gate released");
            return;
        }

        if (activity.isFinishing() || activity.isDestroyed()) {
            // No window to host the dialog: leave the gate armed (the mash is not lost) and
            // do NOT burn the throttle — the next usable activity consumes it (applyOnResume).
            return;
        }

        // KIDS v1.11: one prompt at a time. The throttle below expires after 30 s, so a kiosk-swallowed
        // HOME/RECENTS mash could stack a second dialog and replace the tracked reference; cancelling
        // that one would then null the reference while the first prompt is still on screen, and the
        // night scene would darken the panel under it.
        if (isPinDialogOpen()) {
            return; // stays armed, the prompt already owns the screen
        }

        // KIDS v1.13: too many wrong guesses — no prompt at all for the lockout. The screen stays
        // covered and the sequence is reset here: presses made while locked out must not leave
        // the mash armed, or one press right after the pause would reopen the prompt for free.
        long now = System.currentTimeMillis();

        if (sPinLockoutUntilMs != 0 && now >= sPinLockoutUntilMs) {
            // The pause is over: the parent gets a fresh five guesses. Cancelling the dialog
            // does NOT refill them, otherwise a cancel/re-mash loop would defeat the limit.
            sPinLockoutUntilMs = 0;
            sWrongPinAttempts = 0;
        }

        if (now < sPinLockoutUntilMs) {
            KidsTimeUpKeyGate.resetSequence();
            cover(activity);
            return;
        }

        if (System.currentTimeMillis() - sPinDialogShownAtMs < PIN_DIALOG_THROTTLE_MS) {
            return; // a failed show attempt is still fresh; stays armed
        }

        sPinDialogShownAtMs = System.currentTimeMillis();

        // The dialog is about to own the key input: a second sequence must be earned
        // again, so a cancel/BACK on the dialog does not pop it back up for free.
        KidsTimeUpKeyGate.resetSequence();

        // KIDS v1.11: after the night scene the window brightness is 0 (a switched-off TV). Restore
        // the configured level before the dialog owns the screen, otherwise the parent is asked for
        // the PIN on a panel that is still dark. A cancel re-covers and dims it again (cover()).
        KidsScreenHelper.applyBrightness(activity);

        AlertDialog pinDialog = SimpleEditDialog.showPasswordSticky(
                activity,
                activity.getString(R.string.kids_enter_pin),
                activity.getString(R.string.kids_time_up_pin_hint),
                null,
                newValue -> {
                    if (Utils.passwordMatch(data.getPin(), newValue)) {
                        // One unlock for the whole parent session, exactly like KidsPinGate:
                        // opening Settings right after must not ask again.
                        sWrongPinAttempts = 0;
                        sPinLockoutUntilMs = 0;
                        KidsPinGate.unlock();
                        unlockGateSession(activity);
                        release(activity);
                        return true;
                    }

                    // KIDS v1.13: every miss counts. At the limit the prompt closes itself —
                    // the dialog may already be gone (cancel/timeout), so the reference is
                    // read null-safe and a missing dialog just leaves the lockout armed.
                    if (++sWrongPinAttempts >= MAX_PIN_ATTEMPTS) {
                        sPinLockoutUntilMs = System.currentTimeMillis() + PIN_LOCKOUT_MS;
                        KidsTimeUpKeyGate.resetSequence();
                        AlertDialog dialog = sPinDialog != null ? sPinDialog.get() : null;
                        if (dialog != null) {
                            dialog.dismiss();
                        }
                        Log.d(TAG, "PIN attempts exhausted: prompt locked for %s min", PIN_LOCKOUT_MS / 60_000L);
                    }

                    MessageHelpers.showMessage(activity, R.string.kids_wrong_pin);
                    return false; // keep the dialog open, the screen stays black
                },
                () -> {
                    // Cancel/BACK: still locked, still black. The throttle is cleared so the
                    // gate can arm again — but a fresh 10 escapes are required, the PIN does
                    // not come back for free.
                    // NOTE: this also fires after a SUCCESSFUL unlock (dismiss), hence the
                    // sLocked guard — re-covering there would trap the parent who just typed
                    // the correct PIN.
                    sPinDialog = null;
                    sPinDialogShownAtMs = 0;
                    // KIDS v1.11: the reset at dialog-open only covers presses made BEFORE it.
                    // A kiosk-swallowed HOME/RECENTS mash that landed while the prompt was open
                    // leaves the sequence armed; without this, one BACK right after a cancel
                    // would pop the PIN again instead of costing ten fresh presses.
                    KidsTimeUpKeyGate.resetSequence();

                    if (sLocked) {
                        cover(activity);
                    }
                });

        sPinDialog = pinDialog != null ? new WeakReference<>(pinDialog) : null;
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
    public static void onGuardEscapeKey(Context context, KeyEvent event) {
        if (!sLocked || !isEscapePress(event)) {
            return;
        }

        // KIDS v1.15: with kiosk on, HOME/RECENTS die in the guard — once the reset pause is over,
        // one of them releases the car/classic gate with no PIN. The night art is a clock lock: it
        // falls through to the mash counter and only the 06:00 boundary (or the PIN) opens it.
        if (sQuotaGate && sScene != SCENE_NIGHT && isResetWindowDone(context)) {
            openResetGate(context, MotherActivity.getResumedActivity());
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
        // KIDS v1.15: the gate prompt is non-cancelable — it must never outlive its gate.
        dismissGatePrompt();
        sPinDialogShownAtMs = 0;
        sWrongPinAttempts = 0;
        sPinLockoutUntilMs = 0;
        sScene = SCENE_PLAIN;
        sNightStartMs = 0;
        sQuotaGate = false;
        sBedtimeLock = false;
        sRolloverContext = null;
        Utils.removeCallbacks(sRolloverTask);
        Utils.removeCallbacks(sMorningTask);
        sNightContext = null;
        KidsTimeUpKeyGate.resetSequence();

        if (activity != null) {
            KidsScreenHelper.hideBlackScreen(activity);
            MileMarkerOverlay.hide(activity);
            KidsNightScene.clearFor(activity);
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
        // KIDS v1.15: the gate prompt is non-cancelable — it must never outlive its gate.
        dismissGatePrompt();
        sPinDialogShownAtMs = 0;
        sWrongPinAttempts = 0;
        sPinLockoutUntilMs = 0;
        sScene = SCENE_PLAIN;
        sNightStartMs = 0;
        sQuotaGate = false;
        sBedtimeLock = false;
        sRolloverContext = null;
        Utils.removeCallbacks(sRolloverTask);
        Utils.removeCallbacks(sMorningTask);
        sNightContext = null;
        invalidateGateAuthorization();
        KidsTimeUpKeyGate.resetSequence();

        // KIDS v1.9.1: clear mile marker overlay if still on screen after lock release
        Activity activity = MotherActivity.getResumedActivity();
        if (activity != null && MileMarkerOverlay.isShown(activity)) {
            MileMarkerOverlay.clearFor(activity);
        }
        // KIDS v1.10: the night scene has its own decor tag too; a released lock must not leave it.
        KidsNightScene.clearFor(activity);
    }

    /**
     * KIDS v1.9.8: has the pause that started at exhaustion run its full interval? Same
     * predicate the controller uses to roll the window over, so screen and gate cannot drift.
     */
    private static boolean isResetWindowDone(Context context) {
        if (context == null) {
            return false;
        }

        KidsModeData data = KidsModeData.instance(context);

        if (data.getTimerMinutes() <= 0) {
            return false; // no watch limit: no pause, nothing to open
        }

        long limitMs = KidsQuotaWindow.limitMs(data.getTimerMinutes(), data.getBonusMs());

        if (data.getUsedMs() < limitMs) {
            // No pause ever started: isWindowExpired answers true for a 0 anchor regardless of
            // `used`, and a corrupted blob must not hand a locked child a free window.
            return false;
        }

        return KidsQuotaWindow.isWindowExpired(data.getWindowStartMs(), System.currentTimeMillis(),
                data.getResetIntervalMinutes(), data.getUsedMs(), limitMs, data.isInCooldown(),
                data.getLastWatchMs());
    }

    /**
     * KIDS v1.9.8 / v1.15: write the fresh quota and drop the gate — no mash, no PIN, kiosk
     * included. Since v1.15 this is the ESCAPE release of the car and classic gates (the night
     * art never uses it: it leaves by the mash + PIN or the 06:00 boundary), so the gate stays
     * on screen until the child actually presses a key.
     */
    private static void openResetGate(Context context, Activity host) {
        Activity target = host != null ? host : MotherActivity.getResumedActivity();

        if (context == null || target == null) {
            // KIDS v1.15: no window to detach the art from — stay locked. Releasing here would
            // write the fresh quota and leave the car/black view attached with nothing owning it
            // (the next real key press or resume re-covers and releases it then).
            Log.d(TAG, "Reset gate release skipped: no host activity");
            return;
        }

        KidsModeData.instance(context).resetWindow(System.currentTimeMillis());

        // Prefer the activity the caller already has: the static can be null (or stale) exactly
        // when the overlay has to be detached from a known host.
        release(target);

        Log.d(TAG, "Reset pause over: gate opened without mash/PIN");
    }

    /**
     * KIDS v1.15: (re)arm the 06:00 one-shot for the night art. removeCallbacks first, so the
     * arm path and every resume cannot stack chains.
     */
    private static void scheduleMorningCheck(Context context) {
        if (context == null) {
            return;
        }

        sNightContext = new WeakReference<>(context.getApplicationContext());

        long now = System.currentTimeMillis();
        KidsModeData data = KidsModeData.instance(context);
        // KIDS v1.15: a resume past 06:00 must release at once — msUntilMorning would point at
        // tomorrow and the cat would sit on screen for a whole day.
        boolean night = KidsBedtime.isNight(data.getBedtimeMinutes(), KidsBedtime.minutesOfDay(now));
        long delayMs = night ? Math.max(MORNING_MIN_MS, KidsBedtime.msUntilMorning(now)) : MORNING_MIN_MS;

        Utils.removeCallbacks(sMorningTask);
        Utils.postDelayed(sMorningTask, delayMs);
    }

    /** KIDS v1.15: 06:00 — the good-night screen opens by itself in the morning. */
    private static void onMorningCheck() {
        if (!sLocked || !sQuotaGate || sScene != SCENE_NIGHT) {
            return;
        }

        Context context = sNightContext != null ? sNightContext.get() : null;

        if (context == null) {
            Activity resumed = MotherActivity.getResumedActivity();
            if (resumed != null) {
                scheduleMorningCheck(resumed);
            }
            return;
        }

        KidsModeData data = KidsModeData.instance(context);

        if (KidsBedtime.isNight(data.getBedtimeMinutes(), KidsBedtime.minutesOfDay(System.currentTimeMillis()))) {
            scheduleMorningCheck(context); // the clock moved back into the window
            return;
        }

        Activity host = MotherActivity.getResumedActivity();

        if (host == null) {
            // KIDS v1.15: the TV is asleep at 06:00 (onPause cleared the resumed activity).
            // Releasing now would write the fresh window and leave the cat/black view attached
            // with no lock behind it — retry; a resume re-schedules this within a second.
            Utils.postDelayed(sMorningTask, MORNING_RETRY_MS);
            return;
        }

        // A new day: the window is replenished, otherwise the first morning clip would re-lock.
        data.resetWindow(System.currentTimeMillis());
        release(host);

        Log.d(TAG, "Night gate released at the morning boundary");
    }

    /**
     * KIDS v1.9.4–1.9.5: (re)arm the one-shot that fires when the cooldown pause ends.
     * removeCallbacks first, so arm + every resume cannot stack chains.
     *
     * KIDS v1.9.5: the boundary is the STORED anchor + interval. Here that anchor is always the
     * exhaustion anchor written by {@link KidsModeData#anchorCooldown(long)} (the early return
     * in the body below requires an exhausted quota WITH the flag), so the one-shot fires
     * exactly one interval after the quota ran out. A hand-edited or corrupted blob with a 0
     * anchor still recovers: the ROLLOVER_MIN_MS clamp fires the one-shot and
     * {@link #onRolloverCheck()} treats anchor 0 as expired. KIDS v1.15: the one-shot only
     * records that the pause is over — the gate keeps its art until an escape press.
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

        if (isResetWindowDone(context)) {
            Log.d(TAG, "Rollover check skipped: the pause already elapsed (the gate stays until an escape press)");
            return;
        }

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
     * KIDS v1.9.4: the reset window has moved on. If the boundary shifted meanwhile (interval
     * change, backwards clock jump) the window is not expired yet: re-schedule instead.
     *
     * KIDS v1.15: the boundary OPENS NOTHING. The car stays parked at lamp 10 and the classic
     * black gate stays black until an escape press writes the fresh quota
     * ({@link openResetGate}) — the night art is not touched at all, it leaves by the mash +
     * PIN or the 06:00 boundary ({@link #onMorningCheck}).
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

        if (!isResetWindowDone(context)) {
            scheduleRolloverCheck(context); // boundary moved: wait for the real one
            return;
        }

        Log.d(TAG, "Reset pause over: gate keeps the art until an escape press (scene=%s)", sScene);
    }
}
