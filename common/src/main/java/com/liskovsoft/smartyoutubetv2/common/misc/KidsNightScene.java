package com.liskovsoft.smartyoutubetv2.common.misc;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.lang.ref.WeakReference;

/**
 * KIDS v1.10: the night scene shown when the quota expires inside the bedtime window.
 *
 * A silent canvas scene (star sky, moon, a curled sleeping animal) holds for
 * {@link #SCENE_MS}, then fades over {@link #FADE_MS} into the plain black screen — the child
 * is left on a pitch-black, key-swallowing gate exactly like the classic one. The gate itself
 * (10-press mash, PIN, auto-open at the reset) lives in KidsTimeUpLock and is untouched.
 *
 * Same host contract as MileMarkerOverlay: decor child under {@link #TAG}, MATCH_PARENT,
 * clickable so touches are swallowed, never focusable. The 60 s run is anchored in uptime in
 * KidsTimeUpLock, so a re-cover on another activity continues the same scene instead of
 * restarting it (and past the 60 s it goes straight to the black screen).
 *
 * Main thread only.
 */
public class KidsNightScene {
    public static final String TAG = "kids_night_scene";
    private static final String LOG_TAG = KidsNightScene.class.getSimpleName();
    // The fade runs on an opaque black cover ABOVE the artwork: fading the scene view itself
    // would make its own black canvas transparent and reveal the playlist underneath.
    private static final String FADE_TAG = "kids_night_scene_fade";
    private static final long SCENE_MS = 60_000L;
    private static final long FADE_MS = 2_000L;
    private static final long TICK_MS = 1_000L;

    private static final Runnable sTick = () -> tick();

    private static WeakReference<Activity> sActivity;
    private static long sStartMs;
    private static ValueAnimator sFade;

    private KidsNightScene() {
    }

    /**
     * Attach the scene (or only the black screen when its {@link #SCENE_MS} already ran out).
     *
     * @param elapsedMs time since the gate armed the scene, not since this call
     */
    public static void show(Activity activity, long elapsedMs) {
        if (!canHost(activity)) {
            return;
        }

        if (elapsedMs >= SCENE_MS) {
            // The scene is over: the gate wants the plain black screen, not a replayed night.
            clearFor(activity);
            KidsScreenHelper.showBlackScreen(activity);
            return;
        }

        Activity previous = getActivity();

        if (previous != null && previous != activity) {
            detach(previous, TAG);
            detach(previous, FADE_TAG);
        }

        sActivity = new WeakReference<>(activity);
        sStartMs = SystemClock.elapsedRealtime() - Math.max(0, elapsedMs);

        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();

        if (decor.findViewWithTag(TAG) == null) {
            View scene = new NightView(activity);
            scene.setTag(TAG);
            scene.setClickable(true); // swallow touches without taking focus from the gate
            scene.setFocusable(false);
            decor.addView(scene,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Scheduled once per attach: cover() also runs on every key press, and re-posting
            // the tick there would let a mashing child push the 60 s expiry out forever. The
            // anchor (sStartMs), not the tick, owns the countdown.
            Utils.postDelayed(sTick, TICK_MS);
        }

        // The time-up fade dims the real backlight; restore the configured level for the scene.
        KidsScreenHelper.applyBrightness(activity);
    }

    /** Remove the scene immediately and stop its tick/fade. */
    public static void hide(Activity activity) {
        clearFor(activity);
    }

    /**
     * Drop the view and stop every handle, without touching the gate state: used by the lock on
     * release (and by {@link #show} once the scene is over). Null-safe, idempotent.
     */
    public static void clearFor(Activity activity) {
        Utils.removeCallbacks(sTick);

        boolean wasFading = sFade != null;

        cancelFade();

        Activity previous = getActivity();
        detach(previous, TAG);
        detach(previous, FADE_TAG);

        if (activity != previous) {
            detach(activity, TAG);
            detach(activity, FADE_TAG);
        }

        if (wasFading) {
            // The fade dimmed the backlight; whoever released the lock restores it normally,
            // but a scene dropped mid-fade must not leave the screen dark.
            KidsScreenHelper.applyBrightness(activity != null ? activity : previous);
        }

        sActivity = null;
        sStartMs = 0;
    }

    /** True while the scene is attached to the given activity's decor (badge suppression). */
    public static boolean isShown(Activity activity) {
        if (activity == null) {
            return false;
        }

        return activity.getWindow().getDecorView().findViewWithTag(TAG) != null;
    }

    private static boolean canHost(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static Activity getActivity() {
        return sActivity != null ? sActivity.get() : null;
    }

    private static void detach(Activity activity, String tag) {
        if (activity == null) {
            return;
        }

        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        View view = decor.findViewWithTag(tag);

        if (view != null) {
            decor.removeView(view);
        }
    }

    private static void tick() {
        Activity activity = getActivity();

        if (!canHost(activity) || !isShown(activity)) {
            clearFor(activity);
            return;
        }

        if (SystemClock.elapsedRealtime() - sStartMs >= SCENE_MS) {
            startFade(activity);
            return;
        }

        Utils.postDelayed(sTick, TICK_MS);
    }

    private static void startFade(Activity activity) {
        Utils.removeCallbacks(sTick);

        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();

        if (decor.findViewWithTag(TAG) == null) {
            KidsScreenHelper.showBlackScreen(activity);
            return;
        }

        cancelFade();

        View cover = decor.findViewWithTag(FADE_TAG);

        if (cover == null) {
            cover = new View(activity);
            cover.setTag(FADE_TAG);
            cover.setBackgroundColor(0xFF000000);
            cover.setAlpha(0f); // attach transparent: the first animator frame must not flash black
            cover.setClickable(true); // swallow touches while the screen goes dark
            decor.addView(cover,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        final View fadeCover = cover;
        final float fromBrightness = currentBrightness(activity);

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(FADE_MS);
        animator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();

            fadeCover.setAlpha(fraction);
            setBrightness(activity, fromBrightness + (KidsScreenHelper.MIN_BRIGHTNESS - fromBrightness) * fraction);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation != sFade) {
                    return; // cancelled: the scene is being dropped, not finished
                }

                sFade = null;
                detach(activity, TAG);
                detach(activity, FADE_TAG);
                KidsScreenHelper.showBlackScreen(activity);
                Log.d(LOG_TAG, "Night scene over: black screen");
            }
        });

        sFade = animator;
        animator.start();
    }

    /** Current window brightness, with auto (-1) resolved to full so the fade always starts lit. */
    private static float currentBrightness(Activity activity) {
        float brightness = activity.getWindow().getAttributes().screenBrightness;

        return brightness >= 0 ? brightness : 1f;
    }

    private static void setBrightness(Activity activity, float brightness) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        lp.screenBrightness = Math.max(KidsScreenHelper.MIN_BRIGHTNESS, brightness);
        activity.getWindow().setAttributes(lp);
    }

    /** Null the handle BEFORE cancelling: the end listener then knows it was aborted. */
    private static void cancelFade() {
        ValueAnimator animator = sFade;
        sFade = null;

        if (animator != null) {
            animator.cancel();
        }
    }

    private static class NightView extends View {
        private static final int STAR_COUNT = 80;
        private static final long STAR_SEED = 20261111L;

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mRect = new RectF();
        private final float[] mStarX = new float[STAR_COUNT];
        private final float[] mStarY = new float[STAR_COUNT];
        private final float[] mStarSize = new float[STAR_COUNT];
        private final float[] mStarPhase = new float[STAR_COUNT];

        NightView(Context context) {
            super(context);

            // Deterministic sky: same stars on every frame, device and re-attach.
            long seed = STAR_SEED;

            for (int i = 0; i < STAR_COUNT; i++) {
                mStarX[i] = nextFloat(seed) * 0.98f + 0.01f;
                seed = advance(seed);
                // Keep the sky clear of the ground and of the moon corner.
                mStarY[i] = nextFloat(seed) * 0.66f + 0.02f;
                seed = advance(seed);
                mStarSize[i] = 0.4f + nextFloat(seed) * 1.1f;
                seed = advance(seed);
                mStarPhase[i] = nextFloat(seed) * 6.283f;
                seed = advance(seed);
            }

            mPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float width = getWidth();
            float height = getHeight();

            if (width <= 0 || height <= 0) {
                return;
            }

            canvas.drawColor(Color.BLACK);

            long now = SystemClock.uptimeMillis();

            drawStars(canvas, width, height, now);
            drawMoon(canvas, width, height);
            drawGround(canvas, width, height);
            drawAnimal(canvas, width, height, now);

            // Twinkle, breathing and the floating Z's are continuous: keep the frame loop alive
            // until the fade takes the view away.
            postInvalidateOnAnimation();
        }

        private void drawStars(Canvas canvas, float width, float height, long now) {
            float seconds = now / 1_000f;

            for (int i = 0; i < STAR_COUNT; i++) {
                float twinkle = 0.55f + 0.45f * (float) Math.sin(seconds * 0.9f + mStarPhase[i]);

                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(0xFFFFFFFF);
                mPaint.setAlpha((int) (255 * twinkle * 0.9f));
                canvas.drawCircle(width * mStarX[i], height * mStarY[i],
                        height * 0.0016f * mStarSize[i], mPaint);
            }

            mPaint.setAlpha(255);
        }

        private void drawMoon(Canvas canvas, float width, float height) {
            float radius = Math.min(width, height) * 0.085f;
            float cx = width * 0.76f;
            float cy = height * 0.20f;

            mPaint.setStyle(Paint.Style.FILL);

            // Soft halo: three widening rings with a low alpha.
            for (int i = 3; i >= 1; i--) {
                mPaint.setColor(0xFFFFF8E1);
                mPaint.setAlpha(10 * (4 - i));
                canvas.drawCircle(cx, cy, radius * (1f + i * 0.22f), mPaint);
            }

            mPaint.setAlpha(255);
            mPaint.setColor(0xFFFFF8E1);
            canvas.drawCircle(cx, cy, radius, mPaint);

            // Craters: fixed offsets so the moon never shimmers.
            mPaint.setColor(0xFFE6DCC0);
            canvas.drawCircle(cx - radius * 0.32f, cy - radius * 0.18f, radius * 0.20f, mPaint);
            canvas.drawCircle(cx + radius * 0.30f, cy + radius * 0.26f, radius * 0.14f, mPaint);
            canvas.drawCircle(cx + radius * 0.10f, cy - radius * 0.45f, radius * 0.10f, mPaint);
        }

        private void drawGround(Canvas canvas, float width, float height) {
            float groundY = height * 0.80f;

            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0xFF0B0E12);
            canvas.drawRect(0, groundY, width, height, mPaint);

            mPaint.setColor(0xFF1C222B);
            canvas.drawRect(0, groundY - height * 0.0025f, width, groundY, mPaint);
        }

        private void drawAnimal(Canvas canvas, float width, float height, long now) {
            float groundY = height * 0.80f;
            float breathing = 1f + 0.035f * (float) Math.sin(now / 700f);

            float bodyW = width * 0.17f * breathing;
            float bodyH = height * 0.11f * breathing;
            float cx = width * 0.30f;
            float bodyBottom = groundY - height * 0.005f;
            float headRadius = bodyH * 0.62f;
            float headX = cx + bodyW * 0.42f;
            float headY = bodyBottom - bodyH * 0.85f;

            int fur = 0xFF8D6E63;
            int furDark = 0xFF6D4C41;

            mPaint.setStyle(Paint.Style.FILL);

            // Tail: a thin arc behind the body.
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(bodyH * 0.16f);
            mPaint.setColor(furDark);
            mRect.set(cx - bodyW * 0.85f, bodyBottom - bodyH * 0.55f,
                    cx - bodyW * 0.30f, bodyBottom + bodyH * 0.05f);
            canvas.drawArc(mRect, 200f, 200f, false, mPaint);

            // Body and head.
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(fur);
            mRect.set(cx - bodyW / 2f, bodyBottom - bodyH, cx + bodyW / 2f, bodyBottom);
            canvas.drawOval(mRect, mPaint);
            canvas.drawCircle(headX, headY, headRadius, mPaint);

            // Ears: two triangles on the head.
            mPaint.setColor(furDark);
            float ear = headRadius * 0.62f;
            mPath.reset();
            mPath.moveTo(headX - ear * 1.1f, headY - headRadius * 0.55f);
            mPath.lineTo(headX - ear * 0.25f, headY - headRadius * 1.05f);
            mPath.lineTo(headX - ear * 0.05f, headY - headRadius * 0.25f);
            mPath.close();
            canvas.drawPath(mPath, mPaint);
            mPath.reset();
            mPath.moveTo(headX + ear * 0.2f, headY - headRadius * 0.45f);
            mPath.lineTo(headX + ear * 1.0f, headY - headRadius * 1.0f);
            mPath.lineTo(headX + ear * 1.15f, headY - headRadius * 0.15f);
            mPath.close();
            canvas.drawPath(mPath, mPaint);

            // Closed eyes: two shallow arcs, not dots.
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(1f, headRadius * 0.07f));
            mPaint.setColor(0xFF2B1D16);
            float eyeW = headRadius * 0.42f;
            float eyeY = headY - headRadius * 0.05f;
            mRect.set(headX - headRadius * 0.52f, eyeY, headX - headRadius * 0.52f + eyeW, eyeY + headRadius * 0.26f);
            canvas.drawArc(mRect, 20f, 140f, false, mPaint);
            mRect.set(headX + headRadius * 0.14f, eyeY, headX + headRadius * 0.14f + eyeW, eyeY + headRadius * 0.26f);
            canvas.drawArc(mRect, 20f, 140f, false, mPaint);

            drawSleepZ(canvas, width, height, headX + headRadius * 0.7f, headY - headRadius * 1.1f, now);
        }

        private void drawSleepZ(Canvas canvas, float width, float height, float x, float y, long now) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0xFFE0E0E0);

            for (int i = 0; i < 3; i++) {
                // Each Z loops over its own 3.6 s cycle, offset so they rise one after another.
                float progress = (now / 1_000f + i * 1.2f) % 3.6f / 3.6f;
                float alpha = (float) Math.sin(Math.PI * progress); // fade in, then out

                mPaint.setAlpha((int) (200 * alpha));
                mPaint.setTextSize(height * (0.022f + 0.018f * progress));
                canvas.drawText("Z", x + width * 0.03f * progress + i * width * 0.004f,
                        y - height * 0.16f * progress, mPaint);
            }

            mPaint.setAlpha(255);
        }

        private static long advance(long seed) {
            return seed * 6364136223846793005L + 1442695040888963407L;
        }

        private static float nextFloat(long seed) {
            // Top bits of the LCG: well mixed, deterministic, no Random overhead.
            return ((seed >>> 40) & 0xFFFF) / 65535f;
        }
    }
}
