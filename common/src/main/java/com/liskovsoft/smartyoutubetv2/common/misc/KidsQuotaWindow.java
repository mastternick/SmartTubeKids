package com.liskovsoft.smartyoutubetv2.common.misc;

import java.util.Locale;

/**
 * KIDS v1.8: pure math for the Kids Mode watch quota — the reset window, the limit, the
 * remaining time, the countdown badge decision and its label.
 *
 * Deliberately free of android imports (java.util only), so the whole rule is provable on a
 * plain JVM with javac/java — no SDK, no gradle; same reason as KidsTimeUpKeyGate
 * (see BUILD-POLICY.md). KidsModeController and KidsCountdownBadge both call into this class,
 * so the number the child sees and the number the hard stop enforces cannot drift apart.
 */
public final class KidsQuotaWindow {
    public static final long MINUTE_MS = 60_000L;
    public static final long HOUR_MS = 60 * MINUTE_MS;

    private KidsQuotaWindow() {
    }

    /**
     * Length of the reset window, in minutes. A non-positive choice falls back to 24 h, so a
     * corrupted or hand-edited preference can never make the quota reset on every tick.
     */
    public static long intervalMs(int intervalMinutes) {
        return intervalMinutes > 0 ? intervalMinutes * MINUTE_MS : 24 * HOUR_MS;
    }

    public static long limitMs(int timerMinutes, long bonusMs) {
        return (timerMinutes * MINUTE_MS) + bonusMs;
    }

    public static long remainingMs(long limitMs, long usedMs) {
        return Math.max(0, limitMs - usedMs);
    }

    /**
     * Must the counters roll over? True when there is nothing to roll over (no quota set, or no
     * anchor stored yet: fresh install, or a blob written by a build that keyed the window by
     * calendar date — that value parses as 0).
     *
     * KIDS v1.9.7: the reset interval is a PAUSE, not a rolling window. The pause
     * [anchor, anchor + interval) only exists AFTER the quota has been exhausted: an anchor with
     * usedMs < limitMs is meaningless, because no pause is running and there is no boundary to
     * wait for. So while the quota is not yet reached the answer is always false — the watch
     * budget is spent down across sessions until it is used up instead of being replenished by
     * the clock. The anchor is re-written at exhaustion by KidsModeController (once per
     * exhaustion), so the pause runs exactly one interval measured from that instant and the
     * rollover (used = 0, bonus = 0, fresh anchor) happens at anchor + interval.
     *
     * KIDS v1.9.7: the stored anchor is only a pause boundary while the {@code inCooldown} flag
     * says the pause was anchored AT exhaustion. Elapsed time alone proves nothing: an anchor
     * without the flag — the parent lowered the timer below the already-counted usedMs, or the
     * process died between the counter save and the anchor save in
     * KidsModeController.accumulatePlayTime — is not a boundary, so no rollover happens and the
     * false "fresh quota" never appears. KidsModeController.anchorCooldownIfNeeded() writes a
     * proper pause anchor on the next check instead.
     */
    public static boolean isWindowExpired(long storedWindowStartMs, long nowMs, int intervalMinutes,
                                          long usedMs, long limitMs, boolean inCooldown) {
        if (limitMs <= 0 || storedWindowStartMs <= 0) {
            return true; // no quota / no anchor: nothing to wait for, start fresh
        }

        if (!inCooldown || usedMs < limitMs) {
            return false; // no pause anchored at exhaustion — never replenish
        }

        return isPauseElapsed(storedWindowStartMs, nowMs, intervalMinutes);
    }

    /**
     * True once one interval has elapsed since the given pause anchor — the raw clock test,
     * without the "is a pause running" conditions of {@link #isWindowExpired}. Anchor 0 (nothing
     * stored) is never a boundary.
     */
    public static boolean isPauseElapsed(long pauseAnchorMs, long nowMs, int intervalMinutes) {
        return pauseAnchorMs > 0 && nowMs - pauseAnchorMs >= intervalMs(intervalMinutes);
    }

    /**
     * Countdown badge: visible on every screen while there is time left. warnMinutes 0 = off.
     *
     * KIDS v1.8.1: the v1.8.0 rule (only within the last warnMinutes) made the badge invisible
     * for almost the whole session, so the parent never saw a timer and reported it broken.
     * The badge now shows the remaining time from the start; {@link #isCountdownWarning}
     * decides when it turns red.
     */
    public static boolean shouldShowCountdown(long remainingMs, int warnMinutes) {
        return warnMinutes > 0 && remainingMs > 0;
    }

    /**
     * Countdown badge alert state: the last warnMinutes of the quota (the v1.8.0 show rule).
     */
    public static boolean isCountdownWarning(long remainingMs, int warnMinutes) {
        return warnMinutes > 0 && remainingMs > 0 && remainingMs <= warnMinutes * MINUTE_MS;
    }

    /**
     * mm:ss label for the countdown. Seconds are rounded UP, so the badge reads 0:01 for the
     * whole last second and only reaches 0:00 when the quota is actually gone. Never negative.
     */
    public static String formatCountdown(long remainingMs) {
        long seconds = (Math.max(0, remainingMs) + 999) / 1000;

        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * KIDS v1.9.8: label for the time left until the reset pause ends — the Mile Marker car's
     * remaining distance to lamp 10. Seconds are rounded UP, so the last second reads 0:01 and
     * 0:00 only appears when the pause is actually over. Hours appear only when there are any,
     * so a 5-min pause reads 5:00 and a 24 h one 24:00:00.
     */
    public static String formatRemaining(long remainingMs) {
        long seconds = (Math.max(0, remainingMs) + 999) / 1000;
        long hours = seconds / 3600;

        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, (seconds % 3600) / 60, seconds % 60);
        }

        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}