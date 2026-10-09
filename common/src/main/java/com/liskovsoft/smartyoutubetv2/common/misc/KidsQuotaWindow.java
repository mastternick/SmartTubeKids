package com.liskovsoft.smartyoutubetv2.common.misc;

import java.util.Calendar;
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
     * Length of the reset window. A non-positive choice falls back to 24 h, so a corrupted
     * or hand-edited preference can never make the quota reset on every tick.
     */
    public static long intervalMs(int intervalHours) {
        return intervalHours > 0 ? intervalHours * HOUR_MS : 24 * HOUR_MS;
    }

    /**
     * Start of the reset window containing nowMs, ALIGNED to the interval instead of counted
     * from the first watched minute:
     *  - 24 h (the default) → local midnight, i.e. exactly the reset every earlier build had;
     *  - 6 h → 00:00 / 06:00 / 12:00 / 18:00;
     *  - 1 h → the current hour.
     * Aligning keeps the reset predictable for the parent and independent of when the child
     * happened to start watching.
     */
    public static long windowStartMs(long nowMs, int intervalHours) {
        long startOfDay = startOfDayMs(nowMs);

        return startOfDay + ((nowMs - startOfDay) / intervalMs(intervalHours)) * intervalMs(intervalHours);
    }

    /** Local midnight of the day containing nowMs. */
    public static long startOfDayMs(long nowMs) {
        Calendar cal = Calendar.getInstance(); // device local timezone
        cal.setTimeInMillis(nowMs);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        return cal.getTimeInMillis();
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
     * true whenever the stored boundary is not the one nowMs falls into, which also covers a
     * clock that jumped backwards.
     */
    public static boolean isWindowExpired(long storedWindowStartMs, long nowMs, int intervalHours) {
        return storedWindowStartMs <= 0 || windowStartMs(nowMs, intervalHours) != storedWindowStartMs;
    }

    /**
     * Countdown badge: only while time is left AND no more than warnMinutes of it. 0 = off.
     */
    public static boolean shouldShowCountdown(long remainingMs, int warnMinutes) {
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