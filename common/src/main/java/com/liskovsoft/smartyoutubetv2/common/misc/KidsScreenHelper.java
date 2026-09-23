package com.liskovsoft.smartyoutubetv2.common.misc;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;

/**
 * KIDS: brightness control + calm fade-to-black transition + cross-activity black screen.
 *
 * - applyBrightness: sets the window brightness override from KidsModeData (percent),
 *   or follows the system setting when percent is AUTO.
 * - fadeToBlack: animates a black overlay (alpha 0 -> 1) plus the window brightness
 *   (current -> minimum) over ~2 seconds, then runs a callback.
 * - pendingScreenOff: cross-activity flag so the next resumed activity (browse/playlist)
 *   shows a full black screen until the user presses a key.
 * - showBlackScreen/hideBlackScreen: own 100%-black overlay (independent from
 *   ScreensaverManager so it doesn't depend on the user's dimming percents).
 */
public class KidsScreenHelper {
    private static final String TAG = KidsScreenHelper.class.getSimpleName();
    public static final int BRIGHTNESS_AUTO = -1;
    private static final long DEFAULT_FADE_MS = 2_000;
    private static final float MIN_BRIGHTNESS = 0.01f; // avoid 0 (some devices blank the display)
    private static final String OVERLAY_TAG = "kids_screen_off_overlay";

    private static boolean sPendingScreenOff;

    private KidsScreenHelper() {
    }

    // --- Cross-activity black screen state ---

    public static void setPendingScreenOff() {
        sPendingScreenOff = true;
    }

    /**
     * @return true once per pending request (consumes the flag).
     */
    public static boolean consumePendingScreenOff() {
        boolean result = sPendingScreenOff;
        sPendingScreenOff = false;
        return result;
    }

    public static void clearPendingScreenOff() {
        sPendingScreenOff = false;
    }

    // --- Brightness ---

    /**
     * Apply the stored brightness override to an activity window.
     * 100 (or AUTO) = follow the system brightness.
     */
    public static void applyBrightness(Activity activity) {
        if (activity == null) {
            return;
        }

        int percent = KidsModeData.instance(activity).getBrightnessPercent();

        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();

        if (percent == BRIGHTNESS_AUTO || percent >= 100) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        } else {
            // Map 10..99% onto 0.05..0.99 (never full 0 to avoid 'display off' semantics)
            lp.screenBrightness = Math.max(0.05f, percent / 100f);
        }

        activity.getWindow().setAttributes(lp);
    }

    /**
     * Store a new brightness percent (10..100 or AUTO) and apply it immediately.
     */
    public static void setBrightness(Activity activity, int percent) {
        KidsModeData.instance(activity).setBrightnessPercent(percent);
        applyBrightness(activity);
    }

    // --- Fade to black (Calm Exit transition) ---

    /**
     * KIDS: Calm Exit transition. Fades a black overlay in and dims the window
     * brightness to the minimum over ~2 seconds, then runs onFaded (usually:
     * return to the previous screen with a pending black screen).
     */
    public static void fadeToBlack(Activity activity, Runnable onFaded) {
        fadeToBlack(activity, DEFAULT_FADE_MS, onFaded);
    }

    public static void fadeToBlack(Activity activity, long durationMs, Runnable onFaded) {
        if (activity == null || activity.isFinishing()) {
            if (onFaded != null) {
                onFaded.run();
            }
            return;
        }

        final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();

        if (decor.findViewWithTag(OVERLAY_TAG) != null) {
            // KIDS: fade already running - don't stack overlays
            if (onFaded != null) {
                onFaded.run();
            }
            return;
        }

        final View blackOverlay = new View(activity);
        blackOverlay.setTag(OVERLAY_TAG); // KIDS: same tag so a key press can clear it (embedded player case)
        blackOverlay.setBackgroundColor(0xFF000000);
        blackOverlay.setAlpha(0f);
        blackOverlay.setClickable(true); // swallow touches during the fade

        decor.addView(blackOverlay,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final float startBrightness = activity.getWindow().getAttributes().screenBrightness > 0
                ? activity.getWindow().getAttributes().screenBrightness : 1f;

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(durationMs);
        animator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();

            blackOverlay.setAlpha(fraction);

            // Also lower the real backlight for a true 'lights out' feel
            WindowManager.LayoutParams current = activity.getWindow().getAttributes();
            current.screenBrightness = Math.max(MIN_BRIGHTNESS, startBrightness * (1f - fraction));
            activity.getWindow().setAttributes(current);
        });

        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mDone;

            @Override
            public void onAnimationEnd(Animator animation) {
                runOnce();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                runOnce();
            }

            private void runOnce() {
                if (!mDone) {
                    mDone = true;
                    if (onFaded != null) {
                        onFaded.run();
                    }
                }
            }
        });

        Log.d(TAG, "Starting calm fade-to-black (%s ms)", durationMs);
        animator.start();
    }

    // --- Static black screen (after calm exit, until a key press) ---

    /**
     * Show a full-black overlay on the activity and drop the backlight to the minimum.
     * Idempotent. Independent from ScreensaverManager (always 100% black).
     */
    public static void showBlackScreen(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();

        if (decor.findViewWithTag(OVERLAY_TAG) != null) {
            return; // already shown
        }

        View overlay = new View(activity);
        overlay.setTag(OVERLAY_TAG);
        overlay.setBackgroundColor(0xFF000000);
        overlay.setClickable(true); // swallow touches while 'off'

        decor.addView(overlay,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        lp.screenBrightness = MIN_BRIGHTNESS;
        activity.getWindow().setAttributes(lp);

        Log.d(TAG, "Black screen shown until first key press");
    }

    /**
     * Remove the black overlay (if any) and restore the configured brightness.
     * @return true when an overlay was actually removed.
     */
    public static boolean hideBlackScreen(Activity activity) {
        if (activity == null) {
            return false;
        }

        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        View overlay = decor.findViewWithTag(OVERLAY_TAG);

        if (overlay == null) {
            return false;
        }

        decor.removeView(overlay);
        applyBrightness(activity);

        Log.d(TAG, "Black screen removed, brightness restored");
        return true;
    }

    public static boolean isBlackScreenShown(Activity activity) {
        if (activity == null) {
            return false;
        }

        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        return decor.findViewWithTag(OVERLAY_TAG) != null;
    }
}
