package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.KidsModeController;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * KIDS v1.8: the remaining-time countdown in the top-right corner.
 *
 * Purely informational, and shown only while there is something to warn about: Kids Mode on,
 * a watch limit set, a parent-configured lead time (KidsModeData.getWarnBeforeMinutes) and
 * less than that much time left. It never gates keys, never blocks focus and never replaces
 * the calm-exit warnings — the PIN gate stays exactly as it was.
 *
 * The number is not a timer of its own: it is computed from the same persisted quota the
 * hard stop enforces (KidsQuotaWindow + KidsModeData), plus the play time the counter has not
 * flushed to prefs yet ({@link KidsModeController#getUnflushedPlayMs()}). Without that last
 * part the badge would freeze for a minute at a time and then jump, because the counter is
 * only flushed on the ~1 min tickle, on pause and at the end of a clip.
 *
 * The view lives on the activity's decor, exactly like the calm-exit black screen
 * (KidsScreenHelper), and is tagged so a re-attach can never stack two badges. It is attached
 * from LeanbackActivity.onResume and dropped in onPause, so a transient dialog task
 * (AppDialogActivity is not a LeanbackActivity) never carries it.
 *
 * Suppressed while the screen must stay clean or tiny: the PIN-locked gate, any black screen
 * overlay (the badge is added after it and would draw on top), and picture-in-picture.
 */
public class KidsCountdownBadge {
    private static final String TAG = KidsCountdownBadge.class.getSimpleName();
    private static final String BADGE_TAG = "kids_countdown_badge";
    private static final long REFRESH_MS = 1_000L;
    private static final int BACKGROUND_COLOR = 0xCC000000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final float TEXT_SIZE_SP = 16f;
    private static final int PADDING_DP = 12;
    private static final int MARGIN_DP = 24;
    private static final int CORNER_RADIUS_DP = 8;

    private static final Runnable sRefresh = () -> refresh(MotherActivity.getResumedActivity());

    // Resolved once: PlaybackPresenter is a process-wide singleton and its KidsModeController is
    // created with it (see getUnflushedPlayMs — resolving it per tick would re-run setContext).
    private static KidsModeController sController;

    private KidsCountdownBadge() {
    }

    /**
     * Called from LeanbackActivity.onResume: (re)attach the badge on the incoming screen and
     * keep the one-second refresh alive.
     */
    public static void applyOnResume(Activity activity) {
        if (activity == null) {
            return;
        }

        refresh(activity);
    }

    /**
     * Called from LeanbackActivity.onPause: the badge belongs to the activity that is leaving,
     * so drop it there and stop ticking until the next resume brings the next screen.
     */
    public static void onPause(Activity activity) {
        detach(activity);
        Utils.removeCallbacks(sRefresh);
    }

    /**
     * One tick: decide from scratch whether the badge belongs on the given screen. Everything is
     * re-read every second on purpose — the parent may change the limit or the lead time, the
     * remaining time crosses the threshold on its own, and the time-up lock must clear it. The
     * one-second loop resolves the resumed activity itself, so a screen switch needs no re-arm.
     */
    private static void refresh(Activity activity) {
        if (activity == null) {
            Utils.removeCallbacks(sRefresh); // nothing on screen; the next resume restarts the loop
            return;
        }

        KidsModeData data = KidsModeData.instance(activity);
        int warnMinutes = data.getWarnBeforeMinutes();
        boolean counting = data.isEnabled() && data.getTimerMinutes() > 0 && warnMinutes > 0;
        long remainingMs = -1;

        // The badge is a decor child added AFTER the black overlay (LeanbackActivity.onResume
        // runs MotherActivity's KidsTimeUpLock.applyOnResume first), so it would draw on top of
        // it. The gate's own state is not enough: the calm-exit / screen-off black screen is
        // shown without arming the lock, and this repo has a history of stray artifacts left on
        // that screen. Keep ticking while hidden so the badge returns by itself.
        if (counting
                && !KidsTimeUpLock.isLocked()
                && !KidsScreenHelper.isBlackScreenShown(activity)
                && !isInPipMode(activity)) {
            remainingMs = remainingMs(activity, data);
        }

        if (KidsQuotaWindow.shouldShowCountdown(remainingMs, warnMinutes)) {
            show(activity, KidsQuotaWindow.formatCountdown(remainingMs));
        } else {
            detach(activity);
        }

        if (counting) {
            Utils.postDelayed(sRefresh, REFRESH_MS); // keep ticking so it can appear on its own
        }
    }

    private static long remainingMs(Activity activity, KidsModeData data) {
        long now = System.currentTimeMillis();
        int intervalHours = data.getResetIntervalHours();

        // A window that already moved on means a fresh quota. The rollover itself is done by
        // KidsModeController on its next tick — this must not write preferences every second.
        long usedMs = KidsQuotaWindow.isWindowExpired(data.getWindowStartMs(), now, intervalHours)
                ? 0
                : data.getUsedMs();

        return KidsQuotaWindow.remainingMs(
                KidsQuotaWindow.limitMs(data.getTimerMinutes(), data.getBonusMs()),
                usedMs + getUnflushedPlayMs(activity));
    }

    /**
     * Play time the quota counter has not persisted yet (0 while paused/not counting).
     *
     * The controller instance is cached on purpose: PlaybackPresenter is a process-wide singleton
     * whose KidsModeController is created with it and never replaced, so resolving it once avoids
     * re-running PlaybackPresenter.setContext (a localized-context allocation) on every tick.
     * Before the playback view ever initialized the controller still answers 0, which is correct
     * because nothing can be playing then.
     */
    private static long getUnflushedPlayMs(Context context) {
        if (sController == null) {
            PlaybackPresenter presenter = PlaybackPresenter.instance(context);
            sController = presenter != null ? presenter.getController(KidsModeController.class) : null;
        }

        return sController != null ? sController.getUnflushedPlayMs() : 0;
    }

    /**
     * A picture-in-picture window is a few centimetres wide: a top-right decor overlay would cover
     * the whole picture, so the badge is skipped there. The platform flag is used directly (the
     * kiosk helper routes through PlaybackPresenter, which this ticker deliberately avoids);
     * isInPictureInPictureMode is API 24 and minSdk is 17.
     */
    private static boolean isInPipMode(Activity activity) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode();
    }

    private static void show(Activity activity, CharSequence text) {
        TextView badge = findBadge(activity);

        if (badge == null) {
            if (!(activity.getWindow().getDecorView() instanceof FrameLayout)) {
                return; // cannot honor the top-right gravity: better no badge than a wrong one
            }

            detachOther(activity);

            badge = createBadge(activity);
            ((ViewGroup) activity.getWindow().getDecorView()).addView(badge);

            Log.d(TAG, "Countdown badge shown (%s)", text);
        }

        badge.setText(text);
    }

    private static void detach(Activity activity) {
        if (activity == null) {
            return;
        }

        TextView badge = findBadge(activity);

        if (badge != null) {
            ((ViewGroup) activity.getWindow().getDecorView()).removeView(badge);
        }
    }

    /**
     * A badge must never survive on a screen that is no longer resumed (the view is per-activity),
     * so drop it from whichever activity held it before attaching to this one.
     */
    private static void detachOther(Activity activity) {
        Activity resumed = MotherActivity.getResumedActivity();

        if (resumed != null && resumed != activity) {
            detach(resumed);
        }
    }

    private static TextView findBadge(Activity activity) {
        View view = activity.getWindow().getDecorView().findViewWithTag(BADGE_TAG);

        return view instanceof TextView ? (TextView) view : null;
    }

    private static TextView createBadge(Activity activity) {
        float density = activity.getResources().getDisplayMetrics().density;

        TextView badge = new TextView(activity);
        badge.setTag(BADGE_TAG);
        badge.setTextColor(TEXT_COLOR);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setBackground(createBackground(density));

        int horizontal = dp(density, PADDING_DP);
        badge.setPadding(horizontal, horizontal / 2, horizontal, horizontal / 2);

        // TV focus and touches must be untouched by the badge: it is decoration over the UI,
        // and it must never swallow a key the child pressed (the kiosk guard and the time-up
        // mash gate own that input, see KidsTimeUpLock).
        badge.setFocusable(false);
        badge.setFocusableInTouchMode(false);
        badge.setClickable(false);
        badge.setLongClickable(false);

        // DecorView is a FrameLayout, so the gravity is honored without wrapping the badge.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        int margin = dp(density, MARGIN_DP);
        params.setMargins(margin, margin, margin, margin);
        badge.setLayoutParams(params);

        return badge;
    }

    private static GradientDrawable createBackground(float density) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(BACKGROUND_COLOR);
        background.setCornerRadius(dp(density, CORNER_RADIUS_DP));

        return background;
    }

    private static int dp(float density, int dp) {
        return Math.round(density * dp);
    }
}