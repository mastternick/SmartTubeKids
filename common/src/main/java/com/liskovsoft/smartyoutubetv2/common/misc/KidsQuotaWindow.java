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
     * Must the counters roll over? True when no window is stored yet (fresh install, or a blob
     * written by a build that keyed the window by calendar date — that value parses as 0) and
     * true once a full interval has elapsed since the stored anchor.
     *
     * KIDS v1.9.5: the window is [anchor, anchor + interval) counted FROM the anchor, no clock
     * grid. The anchor is re-written at rollover AND at quota exhaustion (cooldown), so an
     * exhausted quota is a real pause: it lasts until anchor + interval, measured from expiry.
     */
    public static boolean isWindowExpired(long storedWindowStartMs, long nowMs, int intervalMinutes) {
        return storedWindowStartMs <= 0 || nowMs - storedWindowStartMs >= intervalMs(intervalMinutes);
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
}