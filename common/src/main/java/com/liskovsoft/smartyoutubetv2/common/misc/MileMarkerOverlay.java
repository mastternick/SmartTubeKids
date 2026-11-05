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
 * KIDS: a silent night-road view of the real quota reset window.
 * Main-thread only. Progress is read from preferences, never advanced by the animation.
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

    private static final Runnable sTick = () -> tick();

    private static WeakReference<Activity> sActivity;
    private static int sLastLit = -1;
    private static boolean sCelebrated;
    private static long sMarkerLitAtMs;
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
        sLastLit = -1;
        sCelebrated = false;
        sMarkerLitAtMs = 0;
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
        long intervalMs = KidsQuotaWindow.intervalMs(data.getResetIntervalMinutes());
        long elapsedMs = startMs > 0 && startMs <= now ? now - startMs : 0;
        int lit = elapsedMs >= intervalMs ? TOTAL_MARKERS : (int) (elapsedMs * TOTAL_MARKERS / intervalMs);
        long animationNow = SystemClock.uptimeMillis();

        if (lit != sLastLit) {
            sMarkerLitAtMs = sLastLit >= 0 && lit > sLastLit ? animationNow : 0;
        }

        if (lit < TOTAL_MARKERS) {
            sCelebrated = false;
            sCelebrationAtMs = 0;
        } else if (!sCelebrated) {
            // Opening an already-finished window is not a new completion event.
            sCelebrationAtMs = sLastLit >= 0 ? animationNow : 0;
            sCelebrated = true;
        }

        sLastLit = lit;

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
            float centerY = height * 0.5f;
            float roadTop = height * 0.42f;
            float roadBottom = height * 0.58f;
            float spacing = (right - left) / TOTAL_MARKERS;
            float radius = Math.min(spacing * 0.075f, height * 0.012f);
            long now = SystemClock.uptimeMillis();
            long completionMs = sCelebrationAtMs > 0 ? now - sCelebrationAtMs : -1;
            long flashElapsedMs = completionMs - MARKER_LIGHT_UP_DURATION;
            boolean glowing = completionMs >= 0 && flashElapsedMs >= TOTAL_MARKERS * FLASH_MS;

            mPaint.setColor(ROAD_COLOR);
            canvas.drawRect(left, roadTop, right, roadBottom, mPaint);
            mPaint.setColor(0xFF89919B);
            canvas.drawRect(left, roadTop, right, roadTop + height * 0.003f, mPaint);
            canvas.drawRect(left, roadBottom - height * 0.003f, right, roadBottom, mPaint);

            for (int i = 0; i < TOTAL_MARKERS; i++) {
                float x = left + spacing * (i + 0.5f);
                mPaint.setColor(Color.WHITE);
                canvas.drawRect(x - spacing * 0.21f, centerY - height * 0.004f,
                        x + spacing * 0.21f, centerY + height * 0.004f, mPaint);
                mPaint.setTextSize(height * 0.027f);
                canvas.drawText(MARKER_LABELS[i], x, centerY + height * 0.05f, mPaint);

                float brightness = i < sLastLit ? 1f : 0f;

                if (i == sLastLit - 1 && sMarkerLitAtMs > 0) {
                    brightness = Math.min(1f, (now - sMarkerLitAtMs) / (float) MARKER_LIGHT_UP_DURATION);
                }

                int color = blend(POLE_DARK, POLE_LIT, brightness);
                boolean green = glowing || (flashElapsedMs >= 0 && flashElapsedMs / FLASH_MS == i);

                if (green) {
                    color = GLOW_GREEN;
                }

                drawPole(canvas, x, roadTop - height * 0.025f, roadTop - height * 0.13f,
                        radius, color, brightness, green);
                drawPole(canvas, x, roadBottom + height * 0.025f, roadBottom + height * 0.13f,
                        radius, color, brightness, green);
            }

            // KIDS v1.9.1: the car sits ON the last lit lamp. Lamp i is lit when i < sLastLit
            // and its centre is left + spacing * (i + 0.5), so the reached lamp centre is
            // left + spacing * (sLastLit - 0.5): waiting at the road start before lamp 1, and
            // parked at lamp 10 when the window is over. Same lit count as the lamps, so car
            // and quota can never disagree; the clamp keeps the body inside the road.
            if (sLastLit >= 0) {
                float lamp = sLastLit > 0 ? sLastLit - 0.5f : 0f;
                float carX = left + spacing * Math.min(Math.max(lamp, 0.3f), TOTAL_MARKERS - 0.3f);
                float carY = height * 0.465f;
                float carWidth = spacing * 0.55f;
                float carHeight = height * 0.035f;

                mPaint.setColor(0xFFE53935); // body
                mPaint.setStyle(Paint.Style.FILL);
                canvas.drawRect(carX - carWidth / 2f, carY, carX + carWidth / 2f, carY + carHeight, mPaint);

                mPaint.setColor(0xFFC62828); // cabin
                canvas.drawRect(carX - carWidth * 0.22f, carY - carHeight * 0.7f,
                        carX + carWidth * 0.22f, carY + carHeight * 0.1f, mPaint);

                mPaint.setColor(0xFF90CAF9); // window
                canvas.drawRect(carX - carWidth * 0.14f, carY - carHeight * 0.55f,
                        carX + carWidth * 0.14f, carY - carHeight * 0.05f, mPaint);

                mPaint.setColor(0xFF212121); // wheels
                canvas.drawCircle(carX - carWidth * 0.28f, carY + carHeight, carHeight * 0.32f, mPaint);
                canvas.drawCircle(carX + carWidth * 0.28f, carY + carHeight, carHeight * 0.32f, mPaint);

                // Headlight: off on the way, a beam once the reset is complete.
                mPaint.setColor(glowing ? 0xFFFFF176 : 0xFFFFCC80);
                canvas.drawCircle(carX + carWidth / 2f, carY + carHeight * 0.35f, carHeight * 0.2f, mPaint);

                if (glowing) {
                    mPaint.setAlpha(60);
                    canvas.drawRect(carX + carWidth / 2f, carY + carHeight * 0.1f,
                            carX + carWidth * 0.95f, carY + carHeight * 0.6f, mPaint);
                    mPaint.setAlpha(255);
                }
            }
            if (glowing) {
                mPaint.setColor(Color.WHITE);
                mPaint.setTextSize(height * 0.035f);
                canvas.drawText(getContext().getString(R.string.kids_mile_marker_done), width * 0.5f, height * 0.78f, mPaint);
            }

            // Animate only a newly lit marker and the one-shot completion sweep.
            if ((sMarkerLitAtMs > 0 && now - sMarkerLitAtMs < MARKER_LIGHT_UP_DURATION)
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
                mPaint.setAlpha((int) (brightness * (green ? 42 : 22)));
                canvas.drawCircle(x, lightY, radius * 4f, mPaint);
                mPaint.setAlpha((int) (brightness * (green ? 85 : 50)));
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
