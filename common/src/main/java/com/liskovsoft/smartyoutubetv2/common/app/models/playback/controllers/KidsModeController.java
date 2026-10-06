package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.app.Activity;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.KidsScreenHelper;
import com.liskovsoft.smartyoutubetv2.common.misc.KidsTimeUpLock;
import com.liskovsoft.smartyoutubetv2.common.misc.TickleManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * KIDS: Kids Mode controller — implements the Calm Exit concept.
 *
 * Responsibilities:
 *  - Count real watch minutes per day (daily limit).
 *  - Calm exit warnings at -5 / -2 / -1 minutes before limit.
 *  - Hard stop: current video always finishes, then playback closes (no cliffhanger mid-video).
 *  - KIDS force stop (optional): the clip is cut the instant the limit expires and the
 *    screen stays black behind a PIN gate (see KidsTimeUpLock).
 *  - Block Shorts at open time when block-shorts is active.
 *
 * Design note: all persistent state lives in KidsModeData; this controller only
 * tracks in-memory play markers and warning flags.
 */
public class KidsModeController extends BasePlayerController implements TickleManager.TickleListener {
    private static final String TAG = KidsModeController.class.getSimpleName();

    // KIDS FIX: while playing, TickleManager fires every ~60s, so a real watched
    // delta never exceeds ~1 min. Anything much bigger means the process was frozen
    // (TV standby/doze), not the child watching — such gaps must never be counted.
    // The old 12h guard let an entire overnight standby gap (< 12h) pass through and
    // credited it to the NEW day right after the midnight reset — that is why the
    // daily counter appeared to "not reset at 00:00" and the limit looked exhausted
    // all day.
    private static final long MAX_REAL_PLAY_GAP_MS = 3 * 60_000L;

    private KidsModeData mKidsData;
    private long mLastPlayStartMs; // 0 = not playing
    private boolean mWarned5;
    private boolean mWarned2;
    private boolean mWarned1;
    private boolean mIsCalmExitInProgress; // KIDS: guard against double fade (onPlayEnd may fire twice)
    // KIDS: exact hard-stop watchdog. Tickle granularity is one minute, which is too
    // coarse for "stop immediately when the timer expires", so while the clip plays we
    // also post a task for the exact remaining time (re-synced on every tickle/play).
    private final Runnable mExpireTask = this::onTimerExpired;

    @Override
    public void onInit() {
        mKidsData = KidsModeData.instance(getContext());
        TickleManager.instance().addListener(this);
        applyRestrictions(); // KIDS: keep global content filters in sync with kids settings
    }

    /**
     * KIDS: apply/remove global content restrictions driven by Kids Mode.
     * Uses the same mechanisms as the built-in "hide shorts" settings.
     */
    public void applyRestrictions() {
        if (mKidsData == null || getContext() == null) {
            return;
        }

        boolean blockShorts = mKidsData.isBlockShortsActive();
        MediaServiceData.instance().setContentHidden(MediaServiceData.CONTENT_SHORTS_ALL, blockShorts);

        // KIDS AC3: hide the Shorts tab only while Kids Mode is ON (the block-shorts switch
        // itself just filters content). With Kids OFF the controller no longer touches the tab,
        // so the user's manual sidebar setup is kept (the old line force-enabled Shorts).
        if (mKidsData.isEnabled()) {
            BrowsePresenter.instance(getContext()).enableSection(MediaGroup.TYPE_SHORTS, false);
        }
    }

    @Override
    public void onFinish() {
        TickleManager.instance().removeListener(this);
        stopCounting();
        Utils.removeCallbacks(mExpireTask);
    }

    @Override
    public void onNewVideo(Video item) {
        if (item == null || mKidsData == null || !mKidsData.isEnabled()) {
            return;
        }

        // KIDS: block shorts
        if (mKidsData.isBlockShortsActive() && item.isShorts) {
            MessageHelpers.showMessage(getContext(), R.string.kids_shorts_blocked);
            Utils.post(() -> PlaybackPresenter.instance(getContext()).forceFinish());
            return;
        }

        // KIDS: daily limit already reached — don't start new videos
        if (isTimeExpired()) {
            if (isForceStopActive()) {
                // KIDS: hard stop — never even start the clip, lock the screen instead
                forceStopSession();
                return;
            }

            showTimeUpMessage();
            // KIDS: calm fade to black, then back to the previous screen (stays black)
            final Activity activity = getActivity();
            final PlaybackPresenter presenter = PlaybackPresenter.instance(getContext());
            if (activity != null) {
                KidsScreenHelper.fadeToBlack(activity, () -> {
                    KidsScreenHelper.setPendingScreenOff();
                    presenter.forceFinish();
                });
            } else {
                Utils.post(presenter::forceFinish);
            }
        }
    }

    @Override
    public void onVideoLoaded(Video item) {
        // KIDS: a locked time-up screen must survive any load attempt — releasing it
        // here would hand the child back the playlist without the PIN.
        if (KidsTimeUpLock.isLocked()) {
            // Keep it silent and get rid of the player (posted: the view may still init)
            if (getPlayer() != null && getPlayer().isEngineInitialized()) {
                getPlayer().setPlayWhenReady(false);
            }

            Utils.post(() -> PlaybackPresenter.instance(getContext()).forceFinish());
            return;
        }

        // KIDS: a new video woke us up - clear any calm-exit black screen state
        mIsCalmExitInProgress = false;
        Activity activity = getActivity();
        if (activity != null) {
            KidsScreenHelper.clearPendingScreenOff();
            KidsScreenHelper.hideBlackScreen(activity);
        }

        // KIDS: apply user brightness override while the player is visible
        KidsScreenHelper.applyBrightness(activity);
    }

    @Override
    public void onPlay() {
        if (isActive()) {
            mLastPlayStartMs = System.currentTimeMillis();
            scheduleForceStop(); // KIDS: paused time isn't counted, so (re)arm on every play
        }
    }

    @Override
    public void onPause() {
        // KIDS FIX: paused time is not watched time — flush what was played and
        // clear the marker, so a later wake-from-standby tickle can't credit the
        // whole pause/standby period to the child (onPlay restarts counting).
        stopCounting();
        cancelForceStop(); // KIDS: nothing is being watched, so nothing can expire
    }

    @Override
    public void onPlayEnd() {
        stopCounting();
        cancelForceStop();
    }

    @Override
    public void onEngineReleased() {
        stopCounting();
        cancelForceStop();
    }

    /**
     * Called from VideoLoaderController.onPlayEnd (KIDS hook) via getController().
     * @return true if playback must NOT continue to the next video.
     *
     * Rule: explicit playlists chosen by the parent keep playing (a compilation
     * shouldn't stop after every clip). Blocked recommendations only kill the
     * auto-next from suggestions. Expired timer always stops.
     */
    public boolean shouldStopAfterVideo() {
        if (mKidsData == null || !mKidsData.isEnabled()) {
            return false;
        }

        // KIDS v1.7.5: the end-of-video lock mode never plays the next clip — the screen
        // goes black behind the PIN gate instead (see onVideoSessionEnd).
        if (KidsTimeUpLock.isLockAtEndActive(getContext())) {
            return true;
        }

        if (isTimeExpired()) {
            return true;
        }

        Video video = getVideo();

        return mKidsData.isAutoNextBlocked() && (video == null || !video.hasPlaylist());
    }

    /**
     * Called when the current video has ended and the session must stop.
     * KIDS Calm Exit: fade to black over ~2s, then return to the previous screen
     * (playlist/browse) which stays black until the first key press.
     */
    public void onVideoSessionEnd() {
        if (KidsTimeUpLock.isLocked()) {
            return; // KIDS: force stop already ended the session, the screen is PIN-locked
        }

        if (mIsCalmExitInProgress) {
            return; // fade already running
        }

        mIsCalmExitInProgress = true;

        accumulatePlayTime();

        // KIDS v1.7.5: "Lock at end" playback mode — the clip reached its end, so stop on
        // the PIN-locked black screen exactly like the daily-limit hard stop does. Same
        // gate, same 10 escape presses, same PIN; only the trigger differs.
        if (KidsTimeUpLock.isLockAtEndActive(getContext())) {
            lockScreenAtVideoEnd();
            return;
        }

        // KIDS: the clip ran to its natural end with the quota full and the hard stop
        // armed (e.g. the switch was turned on mid-clip) → lock the screen exactly like
        // the watchdog does, instead of the calm fade back to the playlist.
        if (isTimeExpired() && isForceStopActive()) {
            forceStopSession();
            return;
        }

        if (isTimeExpired()) {
            showTimeUpMessage();
        }

        final Activity activity = getActivity();
        final PlaybackPresenter presenter = PlaybackPresenter.instance(getContext());

        Runnable returnToBrowse = () -> {
            KidsScreenHelper.setPendingScreenOff();

            // Go back to the previous view (browse/playlist) instead of exiting the app
            presenter.forceFinish();
        };

        if (activity != null) {
            KidsScreenHelper.fadeToBlack(activity, returnToBrowse);
        } else {
            returnToBrowse.run();
        }
    }

    // --- Tickle: runs about once a minute while the app is open ---

    @Override
    public void onTickle() {
        if (!isActive()) {
            return;
        }

        // KIDS FIX: roll the day over even when nothing is playing, so warnings and
        // the time-up message use the fresh daily quota right after local midnight.
        resetDailyIfNeeded();
        accumulatePlayTime();
        checkWarnings();

        // KIDS: keep the exact hard-stop watchdog aligned with the freshly counted time
        scheduleForceStop();
    }

    // --- Internals ---

    private boolean isActive() {
        return mKidsData != null && mKidsData.isEnabled() && mKidsData.getTimerMinutes() > 0;
    }

    /**
     * KIDS: hard stop armed? Kids Mode on + "stop immediately" on + a PIN to unlock
     * the black screen with (without a PIN the gate would trap everybody).
     */
    private boolean isForceStopActive() {
        return KidsTimeUpLock.isForceStopActive(getContext());
    }

    /**
     * KIDS: post the hard stop for the exact moment today's quota runs out.
     * Re-armed on every play/tickle because paused time is not counted.
     */
    private void scheduleForceStop() {
        Utils.removeCallbacks(mExpireTask);

        // Only while a clip is actually playing: paused/standby time isn't counted, so
        // there is no deadline to watch. No limit (timer = 0) = nothing can expire.
        if (!isForceStopActive() || !isActive() || mLastPlayStartMs == 0) {
            return;
        }

        long remainingMs = getRemainingMs();

        if (remainingMs <= 0) {
            onTimerExpired();
        } else {
            Utils.postDelayed(mExpireTask, remainingMs);
            Log.d(TAG, "Hard stop scheduled in %s ms", remainingMs);
        }
    }

    private void cancelForceStop() {
        Utils.removeCallbacks(mExpireTask);
    }

    private void onTimerExpired() {
        if (!isActive()) {
            return;
        }

        // Flush the played delta but KEEP counting: stopCounting() would drop the marker
        // and let the child watch on for free if the check below says "not expired yet".
        accumulatePlayTime();

        if (!isTimeExpired()) { // rounding / parent bonus added meanwhile
            scheduleForceStop();
            return;
        }

        forceStopSession();
    }

    /**
     * KIDS force stop: cut the clip NOW (no "let the video finish"), go black and stay
     * black behind the PIN gate — no return to the playlist, no next video.
     */
    private void forceStopSession() {
        cancelForceStop();

        final PlaybackPresenter presenter = PlaybackPresenter.instance(getContext());

        if (KidsTimeUpLock.isLocked()) {
            // Already cut and locked (e.g. another video was requested): keep the player down
            presenter.forceFinish();
            return;
        }

        stopCounting();
        mIsCalmExitInProgress = true; // the calm-exit path must not fade a second time

        // Arm the gate BEFORE the transition: a video that finishes loading during the
        // ~2s fade (queued auto-next) must not clear the screen behind our back.
        KidsTimeUpLock.armState();

        showTimeUpMessage();

        // Silence + freeze the picture before the fade, otherwise the clip keeps
        // playing (audible) for the ~2s of the transition.
        if (getPlayer() != null && getPlayer().isEngineInitialized()) {
            getPlayer().setPlayWhenReady(false);
        }

        final Activity activity = getActivity();

        Runnable lockAndFinish = () -> {
            // Cover the screen, then leave the player: the resumed browse activity comes
            // up covered too (KidsTimeUpLock.applyOnResume in MotherActivity), so the
            // playlist is never visible and no key press escapes the gate.
            KidsTimeUpLock.arm(activity);
            presenter.forceFinish();
        };

        if (activity != null) {
            KidsScreenHelper.fadeToBlack(activity, lockAndFinish);
        } else {
            lockAndFinish.run();
        }
    }

    /**
     * KIDS v1.7.5: the "Lock at end" playback mode — the clip ended, so go black behind the
     * PIN gate and end the playback session.
     *
     * Same shape as {@link #forceStopSession()} minus the timer-specific bits: no time-up
     * message (the quota did not expire) and nothing to silence (the clip already ended).
     * The gate is armed BEFORE the fade so a queued next clip cannot clear the screen
     * behind our back, and the overlay survives the player activity
     * ({@code KidsTimeUpLock.applyOnResume} in MotherActivity), exactly like the hard stop.
     * Unlocking needs the Kids Mode PIN after the usual 10 escape presses.
     */
    private void lockScreenAtVideoEnd() {
        cancelForceStop();
        stopCounting();

        final PlaybackPresenter presenter = PlaybackPresenter.instance(getContext());

        KidsTimeUpLock.armState();

        final Activity activity = getActivity();

        Runnable lockAndFinish = () -> {
            KidsTimeUpLock.arm(activity);
            presenter.forceFinish();
        };

        if (activity != null) {
            KidsScreenHelper.fadeToBlack(activity, lockAndFinish);
        } else {
            lockAndFinish.run();
        }

        Log.d(TAG, "KIDS: end-of-video lock armed, screen stays black behind the PIN");
    }

    /**
     * True when today's watch limit (plus any parent bonus) is reached.
     */
    public boolean isTimeExpired() {
        if (!isActive()) {
            return false;
        }

        resetDailyIfNeeded();

        return mKidsData.getDailyUsedMs() >= getLimitMs();
    }

    private long getLimitMs() {
        return (mKidsData.getTimerMinutes() * 60_000L) + mKidsData.getDailyBonusMs();
    }

    private long getRemainingMs() {
        return Math.max(0, getLimitMs() - mKidsData.getDailyUsedMs());
    }

    private void stopCounting() {
        accumulatePlayTime();
        mLastPlayStartMs = 0; // KIDS FIX: never carry a stale marker across a pause/standby
    }

    private void accumulatePlayTime() {
        if (mLastPlayStartMs == 0 || !isActive()) {
            mLastPlayStartMs = 0;
            return;
        }

        long now = System.currentTimeMillis();

        // KIDS FIX: roll the day over first, then only count the part of the gap that
        // falls inside today (local time). A session that crosses 00:00 must not push
        // yesterday's minutes onto the new day's fresh counter.
        resetDailyIfNeeded();
        long start = Math.max(mLastPlayStartMs, getStartOfTodayMs(now));
        long delta = now - start;
        mLastPlayStartMs = now; // keep counting if still playing

        if (delta > 0 && delta < MAX_REAL_PLAY_GAP_MS) {
            mKidsData.addDailyUsedMs(delta);
        } else if (delta >= MAX_REAL_PLAY_GAP_MS) {
            Log.d(TAG, "Ignoring non-play gap of %s ms (standby/frozen process)", delta);
        }
    }

    /**
     * KIDS FIX: epoch millis of local midnight (00:00) of the day containing nowMs.
     */
    private long getStartOfTodayMs(long nowMs) {
        Calendar cal = Calendar.getInstance(); // device local timezone
        cal.setTimeInMillis(nowMs);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private void resetDailyIfNeeded() {
        String today = todayKey();

        if (!today.equals(mKidsData.getDailyDate())) {
            mKidsData.setDailyDate(today);
            mKidsData.setDailyUsedMs(0);
            mKidsData.setDailyBonusMs(0);
            resetWarnings();
            // KIDS: a new day means a fresh quota — never keep the child locked past midnight
            KidsTimeUpLock.releaseStateOnly();
        }
    }

    private void checkWarnings() {
        if (!mKidsData.isCalmExit()) {
            return;
        }

        long remainingMs = getRemainingMs();

        if (remainingMs > 0 && remainingMs <= 60_000L && !mWarned1) {
            mWarned1 = true;
            showTimeLeftMessage(1);
        } else if (remainingMs > 60_000L && remainingMs <= 2 * 60_000L && !mWarned2) {
            mWarned2 = true;
            showTimeLeftMessage(2);
        } else if (remainingMs > 2 * 60_000L && remainingMs <= 5 * 60_000L && !mWarned5) {
            mWarned5 = true;
            showTimeLeftMessage(5);
        } else if (remainingMs == 0) {
            showTimeUpMessage();
        }
    }

    private void showTimeLeftMessage(int minutes) {
        if (getContext() == null) {
            return;
        }

        // Show warning as a toast; also as player title overlay when playing
        MessageHelpers.showMessage(getContext(), getContext().getString(R.string.kids_time_left, String.valueOf(minutes)));

        if (getPlayer() != null && getPlayer().isEngineInitialized()) {
            getPlayer().showOverlay(true);
        }

        Log.d(TAG, "Calm exit warning: %s minutes left", minutes);
    }

    private void showTimeUpMessage() {
        if (getContext() == null) {
            return;
        }

        MessageHelpers.showMessage(getContext(), R.string.kids_time_up);

        if (getPlayer() != null && getPlayer().isEngineInitialized()) {
            getPlayer().setTitle(getContext().getString(R.string.kids_time_up));
            getPlayer().showOverlay(true);
        }
    }

    private void resetWarnings() {
        mWarned5 = false;
        mWarned2 = false;
        mWarned1 = false;
    }

    /**
     * Reset warning flags after parent extends the session.
     */
    public void onSessionExtended() {
        resetWarnings();
        // KIDS: the parent granted more time — drop the time-up gate and its overlay
        KidsTimeUpLock.releaseStateOnly();
        scheduleForceStop();
    }

    private String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}
