package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.lang.ref.WeakReference;

/**
 * KIDS v1.9.4: a silent night-road view of the real quota reset window.
 * Main-thread only. Progress is read from preferences, never advanced by the animation.
 *
 * Continuous car: the car moves continuously from start to finish over the interval duration.
 * Progressive lamps: each lamp lights up (medium brightness) when the car passes it,
 * and all lamps reach maximum brightness when the car reaches finish.
 * Same aligned-grid authority as the rollover one-shot in KidsTimeUpLock.
 */
public class MileMarkerOverlay {
    private static final long TICK_MS = 1_000L;
    private static final int TOTAL_MARKERS = 10;
    private static final String OVERLAY_TAG = "mile_marker_overlay";
    private static final int MARKER_LIGHT_UP_DURATION = 500;
    private static final long FLASH_MS = 100L;
    private static final int GLOW_GREEN = Color.parseColor("#00E676");
    private static final int POLE_DARK = Color.parseColor("#1a1a1a");
    private static final int POLE_LIT = Color.parseColor("#FFD700");
    private static final int ROAD_COLOR = Color.parseColor("#333333");
    private static final String[] MARKER_LABELS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10"};

    // Car visual constants
    private static final int CAR_BODY_COLOR = Color.parseColor("#FFE53935");
    private static final int CAR_CABIN_COLOR = Color.parseColor("#FFC62828");
    private static final int CAR_WINDOW_COLOR = Color.parseColor("#FF90CAF9");
    private static final int CAR_WHEEL_COLOR = Color.parseColor("#FF212121");
    private static final int CAR_HEADLIGHT_COLOR = Color.parseColor("#FFFFCC80");
    private static final int CAR_HEADLIGHT_GLOW = Color.parseColor("#FFFFF176");

    // Road layout: 5% margin on each side, 10 lamps evenly distributed between them
    private static final float ROAD_MARGIN_FRAC = 0.05f;
    private static final float CAR_START_MARGIN_FRAC = 0.03f;
    private static final float CAR_END_MARGIN_FRAC = 0.03f;
    private static final float CAR_WIDTH_FRAC = 0.04f;
    private static final float CAR_HEIGHT_FRAC = 0.035f;
    private static final float LAMP_RADIUS_FRAC = 0.012f;
    private static final float LAMP_GLOW_RADIUS_FRAC = 0.04f;
    private static final float LAMP_POST_WIDTH_FRAC = 0.002f;
    private static final float LAMP_POST_HEIGHT_FRAC = 0.13f;
    private static final int LAMP_GLOW_ALPHA_GLOW = 42;
    private static final int LAMP_GLOW_ALPHA_MED = 22;
    private static final int LAMP_GLOW_ALPHA_MAX = 85;
    private static final int LAMP_BRIGHTNESS_MED = 50;

    private static final Runnable sTick = () -> tick();

    private static WeakReference<Activity> sActivity;
    private static float sLastProgress = -1f; // continuous progress 0.0..1.0
    private static long sProgressChangedAtMs;
    private static boolean sCelebrated;
    private static long sCelebrationAtMs;

    private MileMarkerOverlay() {
    }

    /** Attach once and immediately draw progress from the persisted reset window. */
    public static void show(Activity activity) {
        if (!shouldShow(activity)) {
            hide(activity);
            return;
        }

        Activity previous = getActivity();

        if (previous != null && previous != activity) {
            detach(previous);
        }

        if (sActivity == null || previous != activity) {
            sActivity = new WeakReference<>(activity);
        }
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();

        if (decor.findViewWithTag(OVERLAY_TAG) == null) {
            View overlay = new RoadView(activity);
            overlay.setTag(OVERLAY_TAG);
            overlay.setClickable(true); // swallow touches without taking focus from the gate
            overlay.setFocusable(false);
            decor.addView(overlay,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // The time-up fade dims the real backlight; restore the configured level for the scene.
        KidsScreenHelper.applyBrightness(activity);

        refresh(activity);
        // show() can run twice on the same host (arm + applyOnResume); without this
        // each call stacks another tick chain.
        Utils.removeCallbacks(sTick);
        Utils.postDelayed(sTick, TICK_MS);
    }

    /** Remove immediately and stop refreshing; the gate owns lock release and brightness. */
    public static void hide(Activity activity) {
        Utils.removeCallbacks(sTick);
        Activity previous = getActivity();
        detach(previous);

        if (activity != previous) {
            detach(activity);
        }

        sActivity = null;
        sLastProgress = -1f;
        sCelebrated = false;
        sProgressChangedAtMs = 0;
        sCelebrationAtMs = 0;
    }

    /**
     * Drop only the view (no state reset): used by the gate when the lock is released
     * through a path that touches no view (releaseStateOnly). Idempotent, null-safe.
     */
    public static void clearFor(Activity activity) {
        detach(activity);
    }

    private static Activity getActivity() {
        return sActivity != null ? sActivity.get() : null;
    }

    /** True while the mile scene is attached to the given activity's decor (badge suppression). */
    public static boolean isShown(Activity activity) {
        if (activity == null) {
            return false;
        }

        return activity.getWindow().getDecorView().findViewWithTag(OVERLAY_TAG) != null;
    }

    private static boolean shouldShow(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && KidsTimeUpLock.isLocked()
                && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode());
    }

    private static void detach(Activity activity) {
        if (activity == null) {
            return;
        }

        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        View overlay = decor.findViewWithTag(OVERLAY_TAG);

        if (overlay != null) {
            decor.removeView(overlay);
        }
    }

    private static void tick() {
        Activity activity = getActivity();

        if (!shouldShow(activity)) {
            hide(activity);
            return;
        }

        refresh(activity);
        Utils.postDelayed(sTick, TICK_MS);
    }

    private static void refresh(Activity activity) {
        KidsModeData data = KidsModeData.instance(activity);
        long now = System.currentTimeMillis();
        long startMs = data.getWindowStartMs();
        int intervalMinutes = data.getResetIntervalMinutes();
        long intervalMs = KidsQuotaWindow.intervalMs(intervalMinutes);
        // KIDS v1.9.5: the stored window start IS the cooldown start (written at the expiry
        // instant). The car traverses the road over exactly one interval from there.
        long anchor = startMs > 0 ? startMs : now;
        long elapsedMs = Math.max(0, now - anchor);
        float progress = intervalMs > 0 ? Math.min(1.0f, (float) elapsedMs / (float) intervalMs) : 0f;
        long animationNow = SystemClock.uptimeMillis();

        if (progress != sLastProgress) {
            sProgressChangedAtMs = animationNow;
        }

        if (progress < 1.0f) {
            sCelebrated = false;
            sCelebrationAtMs = 0;
        } else if (!sCelebrated) {
            // Opening an already-finished window is not a new completion event.
            sCelebrationAtMs = sLastProgress >= 0 ? animationNow : 0;
            sCelebrated = true;
        }

        sLastProgress = progress;

        View overlay = activity.getWindow().getDecorView().findViewWithTag(OVERLAY_TAG);

        if (overlay != null) {
            overlay.invalidate();
        }
    }

    private static class RoadView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GradientDrawable mSky = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFF0D1117, 0xFF162130, 0xFF0D1117});

        public RoadView(Context context) {
            super(context);
            mPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            mSky.setBounds(0, 0, width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            mSky.draw(canvas);

            float width = getWidth();
            float height = getHeight();
            float left = width * 0.06f;
            float right = width * 0.94f;
            float roadWidth = right - left;
            float centerY = height * 0.5f;
            float roadTop = height * 0.42f;
            float roadBottom = height * 0.58f;
            long now = SystemClock.uptimeMillis();
            long completionMs = sCelebrationAtMs > 0 ? now - sCelebrationAtMs : -1;
            long flashElapsedMs = completionMs - MARKER_LIGHT_UP_DURATION;
            boolean glowing = completionMs >= 0 && flashElapsedMs >= TOTAL_MARKERS * FLASH_MS;

            // Road surface
            mPaint.setColor(ROAD_COLOR);
            canvas.drawRect(left, roadTop, right, roadBottom, mPaint);
            mPaint.setColor(0xFF89919B);
            canvas.drawRect(left, roadTop, right, roadTop + height * 0.003f, mPaint);
            canvas.drawRect(left, roadBottom - height * 0.003f, right, roadBottom, mPaint);

            // Road layout: 5% margin on each side, 10 lamps evenly distributed between them
            float roadLeft = left + roadWidth * ROAD_MARGIN_FRAC;
            float roadRight = right - roadWidth * ROAD_MARGIN_FRAC;
            float roadLength = roadRight - roadLeft;
            float lampSpacing = roadLength / (TOTAL_MARKERS - 1); // 9 intervals for 10 lamps
            float[] lampX = new float[TOTAL_MARKERS];
            for (int i = 0; i < TOTAL_MARKERS; i++) {
                lampX[i] = roadLeft + i * lampSpacing;
            }

            // Car position: continuous progress from carStartX to carEndX
            float carStartX = roadLeft - roadWidth * CAR_START_MARGIN_FRAC;
            float carEndX = roadRight + roadWidth * CAR_END_MARGIN_FRAC;
            float carX = carStartX + sLastProgress * (carEndX - carStartX);
            float carY = height * 0.465f;
            float carWidth = roadWidth * CAR_WIDTH_FRAC;
            float carHeight = height * CAR_HEIGHT_FRAC;

            // Draw lamps with progressive brightness
            float lampRadius = Math.min(lampSpacing * LAMP_RADIUS_FRAC, height * LAMP_RADIUS_FRAC);
            float lampPostWidth = roadWidth * LAMP_POST_WIDTH_FRAC;
            float lampPostHeight = height * LAMP_POST_HEIGHT_FRAC;

            for (int i = 0; i < TOTAL_MARKERS; i++) {
                float x = lampX[i];

                // Lamp number and marking
                mPaint.setColor(Color.WHITE);
                canvas.drawRect(x - lampSpacing * 0.21f, centerY - height * 0.004f,
                        x + lampSpacing * 0.21f, centerY + height * 0.004f, mPaint);
                mPaint.setTextSize(height * 0.027f);
                canvas.drawText(MARKER_LABELS[i], x, centerY + height * 0.05f, mPaint);

                // KIDS v1.9.5: lamps light up behind the car as it passes and stay lit.
                // Distance-based ramp (immune to the 1 s tick): dark ahead, medium behind,
                // maximum once the car reaches the end of the road.
                float brightness;
                if (carX >= roadRight) {
                    brightness = 1.0f; // finish: every lamp at maximum
                } else if (carX <= x) {
                    brightness = 0f;   // still ahead of the car
                } else {
                    float passed = (carX - x) / lampSpacing;
                    brightness = 0.5f * Math.min(1f, passed * 3f);
                }

                // Lamp color: dark when off, amber when on, green when celebrating
                int color = blend(POLE_DARK, POLE_LIT, brightness);
                boolean green = glowing || (flashElapsedMs >= 0 && flashElapsedMs / FLASH_MS == i);

                if (green) {
                    color = GLOW_GREEN;
                }

                // Draw lamp post and bulb (top)
                drawPole(canvas, x, roadTop - height * 0.025f, roadTop - lampPostHeight,
                        lampRadius, color, brightness, green);
                // Draw lamp post and bulb (bottom)
                drawPole(canvas, x, roadBottom + height * 0.025f, roadBottom + lampPostHeight,
                        lampRadius, color, brightness, green);
            }

            // Draw car (only if progress > 0)
            if (sLastProgress > 0) {
                // Car body
                mPaint.setColor(CAR_BODY_COLOR);
                mPaint.setStyle(Paint.Style.FILL);
                canvas.drawRect(carX - carWidth / 2f, carY, carX + carWidth / 2f,
                        carY + carHeight, mPaint);

                // Car cabin
                mPaint.setColor(CAR_CABIN_COLOR);
                canvas.drawRect(carX - carWidth * 0.22f, carY - carHeight * 0.7f,
                        carX + carWidth * 0.22f, carY + carHeight * 0.1f, mPaint);

                // Car window
                mPaint.setColor(CAR_WINDOW_COLOR);
                canvas.drawRect(carX - carWidth * 0.14f, carY - carHeight * 0.55f,
                        carX + carWidth * 0.14f, carY - carHeight * 0.05f, mPaint);

                // Car wheels
                mPaint.setColor(CAR_WHEEL_COLOR);
                canvas.drawCircle(carX - carWidth * 0.28f, carY + carHeight,
                        carHeight * 0.32f, mPaint);
                canvas.drawCircle(carX + carWidth * 0.28f, carY + carHeight,
                        carHeight * 0.32f, mPaint);

                // Headlight
                mPaint.setColor(glowing ? CAR_HEADLIGHT_GLOW : CAR_HEADLIGHT_COLOR);
                canvas.drawCircle(carX + carWidth / 2f, carY + carHeight * 0.35f,
                        carHeight * 0.2f, mPaint);

                // Headlight beam when celebrating
                if (glowing) {
                    mPaint.setAlpha(60);
                    canvas.drawRect(carX + carWidth / 2f, carY + carHeight * 0.1f,
                            carX + carWidth * 0.95f, carY + carHeight * 0.6f, mPaint);
                    mPaint.setAlpha(255);
                }
            }

            // "Reset complete!" text when finished
            if (glowing) {
                mPaint.setColor(Color.WHITE);
                mPaint.setTextSize(height * 0.035f);
                canvas.drawText(getContext().getString(R.string.kids_mile_marker_done),
                        width * 0.5f, height * 0.78f, mPaint);
            }

            // Animate only a newly reached lamp and the one-shot completion sweep
            if ((sProgressChangedAtMs > 0 && now - sProgressChangedAtMs < MARKER_LIGHT_UP_DURATION)
                    || (completionMs >= 0 && !glowing)) {
                postInvalidateOnAnimation();
            }
        }

        private void drawPole(Canvas canvas, float x, float baseY, float lightY, float radius,
                              int color, float brightness, boolean green) {
            mPaint.setColor(color);
            mPaint.setStrokeWidth(radius * 0.5f);
            canvas.drawLine(x - radius * 1.5f, baseY, x - radius * 1.5f, lightY, mPaint);
            canvas.drawLine(x - radius * 1.5f, lightY, x, lightY, mPaint);

            if (brightness > 0f) {
                int outerAlpha = green ? LAMP_GLOW_ALPHA_GLOW : LAMP_GLOW_ALPHA_MED;
                int innerAlpha = green ? LAMP_GLOW_ALPHA_MAX : LAMP_BRIGHTNESS_MED;
                mPaint.setAlpha((int) (brightness * outerAlpha));
                canvas.drawCircle(x, lightY, radius * 4f, mPaint);
                mPaint.setAlpha((int) (brightness * innerAlpha));
                canvas.drawCircle(x, lightY, radius * 2.5f, mPaint);
            }

            mPaint.setColor(color);
            canvas.drawCircle(x, lightY, radius, mPaint);
        }

        private static int blend(int from, int to, float fraction) {
            int red = Color.red(from) + (int) ((Color.red(to) - Color.red(from)) * fraction);
            int green = Color.green(from) + (int) ((Color.green(to) - Color.green(from)) * fraction);
            int blue = Color.blue(from) + (int) ((Color.blue(to) - Color.blue(from)) * fraction);
            return Color.rgb(red, green, blue);
        }
    }
}
