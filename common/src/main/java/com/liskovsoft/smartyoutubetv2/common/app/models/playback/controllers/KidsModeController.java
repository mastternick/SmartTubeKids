package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.TickleManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * KIDS: Kids Mode controller — implements the Calm Exit concept.
 *
 * Responsibilities:
 *  - Count real watch minutes per day (daily limit).
 *  - Calm exit warnings at -5 / -2 / -1 minutes before limit.
 *  - Hard stop: current video always finishes, then playback closes (no cliffhanger mid-video).
 *  - Block Shorts at open time when block-shorts is active.
 *
 * Design note: all persistent state lives in KidsModeData; this controller only
 * tracks in-memory play markers and warning flags.
 */
public class KidsModeController extends BasePlayerController implements TickleManager.TickleListener {
    private static final String TAG = KidsModeController.class.getSimpleName();

    private KidsModeData mKidsData;
    private long mLastPlayStartMs; // 0 = not playing
    private boolean mWarned5;
    private boolean mWarned2;
    private boolean mWarned1;

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
        BrowsePresenter.instance(getContext()).enableSection(MediaGroup.TYPE_SHORTS, !blockShorts);
    }

    @Override
    public void onFinish() {
        TickleManager.instance().removeListener(this);
        accumulatePlayTime();
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
            showTimeUpMessage();
            Utils.post(() -> PlaybackPresenter.instance(getContext()).forceFinish());
        }
    }

    @Override
    public void onPlay() {
        if (isActive()) {
            mLastPlayStartMs = System.currentTimeMillis();
        }
    }

    @Override
    public void onPause() {
        accumulatePlayTime();
    }

    @Override
    public void onPlayEnd() {
        accumulatePlayTime();
    }

    @Override
    public void onEngineReleased() {
        accumulatePlayTime();
    }

    /**
     * Called from VideoLoaderController.onPlayEnd (KIDS hook) via getController().
     * @return true if playback must NOT continue to the next video.
     */
    public boolean shouldStopAfterVideo() {
        if (mKidsData == null || !mKidsData.isEnabled()) {
            return false;
        }

        return mKidsData.isAutoNextBlocked() || isTimeExpired();
    }

    /**
     * Called when the current video has ended and the session must stop.
     * Shows a calm goodbye message (Calm Exit), then closes the player.
     */
    public void onVideoSessionEnd() {
        accumulatePlayTime();

        if (isTimeExpired()) {
            showTimeUpMessage();
        }

        if (getPlayer() != null) {
            getPlayer().finishReally();
        }
    }

    // --- Tickle: runs about once a minute while the app is open ---

    @Override
    public void onTickle() {
        if (!isActive()) {
            return;
        }

        accumulatePlayTime();
        checkWarnings();
    }

    // --- Internals ---

    private boolean isActive() {
        return mKidsData != null && mKidsData.isEnabled() && mKidsData.getTimerMinutes() > 0;
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

    private void accumulatePlayTime() {
        if (mLastPlayStartMs == 0 || !isActive()) {
            mLastPlayStartMs = 0;
            return;
        }

        long now = System.currentTimeMillis();
        long delta = now - mLastPlayStartMs;
        mLastPlayStartMs = now; // keep counting if still playing

        if (delta > 0 && delta < 12 * 60 * 60 * 1000L) { // sanity: ignore sleep/hibernate gaps
            resetDailyIfNeeded();
            mKidsData.addDailyUsedMs(delta);
        }
    }

    private void resetDailyIfNeeded() {
        String today = todayKey();

        if (!today.equals(mKidsData.getDailyDate())) {
            mKidsData.setDailyDate(today);
            mKidsData.setDailyUsedMs(0);
            mKidsData.setDailyBonusMs(0);
            resetWarnings();
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
        MessageHelpers.showMessage(getContext(), getContext().getString(R.string.kids_time_left, minutes));

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
    }

    private String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}
