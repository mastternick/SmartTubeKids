package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * KIDS v1.10: the calm-exit warning banner.
 *
 * Replaces the invisible toast at -5 / -2 / -1 minutes with a small banner at the bottom center
 * of the current activity, plus a short beep while a clip is playing. It is purely informational:
 * it never pauses, seeks or otherwise touches playback, and it never takes focus or touches — the
 * kiosk guard and the time-up mash gate own the input (see {@link KidsTimeUpLock}).
 *
 * The view lives on the activity's decor and is tagged so a repeated warning can never stack two
 * banners; it removes itself after {@link #HIDE_DELAY_MS}. Same shape as {@link KidsCountdownBadge},
 * without the per-second refresh: a warning is one event, not a timer.
 */
public class KidsWarningBanner {
    private static final String TAG = KidsWarningBanner.class.getSimpleName();
    private static final String BANNER_TAG = "kids_calm_warning";
    private static final long HIDE_DELAY_MS = 8_000L;
    private static final int BACKGROUND_COLOR = 0xCC000000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final float TEXT_SIZE_SP = 26f;
    private static final int PADDING_DP = 24;
    private static final int MARGIN_DP = 48;
    private static final int CORNER_RADIUS_DP = 12;
    private static final int BEEP_VOLUME = 80;
    private static final int BEEP_MS = 120;

    private static final Runnable sHide = KidsWarningBanner::hide;
    private static final Runnable sReleaseBeep = KidsWarningBanner::releaseBeep;

    private static Activity sHost;
    private static ToneGenerator sToneGenerator;

    private KidsWarningBanner() {
    }

    /**
     * Show (or refresh) the warning banner on the given screen for {@link #HIDE_DELAY_MS}.
     */
    public static void show(Activity activity, CharSequence text) {
        if (activity == null || text == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode()) {
            return; // a bottom banner would cover the tiny PIP window
        }

        if (!(activity.getWindow().getDecorView() instanceof FrameLayout)) {
            return; // cannot place a bottom-center banner in this decor
        }

        detachOther(activity);

        TextView banner = findBanner(activity);

        if (banner == null) {
            banner = createBanner(activity);
            ((ViewGroup) activity.getWindow().getDecorView()).addView(banner);

            Log.d(TAG, "Calm exit banner shown (%s)", text);
        }

        banner.setText(text);
        sHost = activity;

        Utils.removeCallbacks(sHide);
        Utils.postDelayed(sHide, HIDE_DELAY_MS);
    }

    /**
     * A short beep for the calm-exit warning. Called only while a clip is actually playing, so a
     * paused player or the browse screen stays silent.
     */
    public static void playBeep() {
        try {
            releaseBeep();
            sToneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME);
            sToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS);
            // The generator is a native resource: hand it back once the tone has played.
            Utils.postDelayed(sReleaseBeep, BEEP_MS + 200L);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to play calm exit beep", e);
            releaseBeep();
        }
    }

    private static void hide() {
        Activity activity = sHost;
        sHost = null;

        if (activity == null) {
            return;
        }

        TextView banner = findBanner(activity);

        if (banner != null) {
            ((ViewGroup) activity.getWindow().getDecorView()).removeView(banner);
        }
    }

    private static synchronized void releaseBeep() {
        if (sToneGenerator != null) {
            try {
                sToneGenerator.release();
            } catch (Throwable e) {
                Log.e(TAG, "Failed to release ToneGenerator", e);
            }
            sToneGenerator = null;
        }
    }

    /**
     * A banner must not survive on a screen that is no longer resumed (the view is per-activity),
     * so drop it from whichever activity held it before attaching to this one.
     */
    private static void detachOther(Activity activity) {
        Activity host = sHost;

        if (host == null || host == activity) {
            return;
        }

        TextView banner = findBanner(host);

        if (banner != null) {
            ((ViewGroup) host.getWindow().getDecorView()).removeView(banner);
        }
    }

    private static TextView findBanner(Activity activity) {
        View view = activity.getWindow().getDecorView().findViewWithTag(BANNER_TAG);

        return view instanceof TextView ? (TextView) view : null;
    }

    private static TextView createBanner(Activity activity) {
        float density = activity.getResources().getDisplayMetrics().density;

        TextView banner = new TextView(activity);
        banner.setTag(BANNER_TAG);
        banner.setTextColor(TEXT_COLOR);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP);
        banner.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable background = new GradientDrawable();
        background.setColor(BACKGROUND_COLOR);
        background.setCornerRadius(dp(density, CORNER_RADIUS_DP));
        banner.setBackground(background);

        int horizontal = dp(density, PADDING_DP);
        banner.setPadding(horizontal, horizontal / 2, horizontal, horizontal / 2);

        // Decoration over the UI: it must never take focus or swallow a key the child pressed.
        banner.setFocusable(false);
        banner.setFocusableInTouchMode(false);
        banner.setClickable(false);
        banner.setLongClickable(false);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int margin = dp(density, MARGIN_DP);
        params.setMargins(margin, margin, margin, margin);
        banner.setLayoutParams(params);

        return banner;
    }

    private static int dp(float density, int dp) {
        return Math.round(density * dp);
    }
}
