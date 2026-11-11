package com.liskovsoft.smartyoutubetv2.common.misc;

import java.util.Calendar;
import java.util.Locale;

/**
 * KIDS v1.10: the "night scene after" hour. When the watch quota expires inside the window
 * [bedtime, 06:00) the time-up screen shows the night scene instead of the mile marker.
 *
 * Pure java.util on purpose, like KidsQuotaWindow: the window logic is provable on a plain JVM
 * (javac/java, no SDK, no gradle — see BUILD-POLICY.md).
 *
 * The window crosses midnight and has NO wake-up hour and no effect on the gate itself: the
 * reset pause still opens the screen by itself, whatever the clock says.
 */
public final class KidsBedtime {
    public static final int OFF = -1;
    /** 06:00 — the only line to change if the morning boundary ever moves. */
    public static final int NIGHT_END_MINUTES = 360;
    public static final int[] HOURS = {18, 19, 20, 21, 22, 23};

    private KidsBedtime() {
    }

    /** Selectable values for the settings radio group, "off" first. */
    public static int[] allowedMinutes() {
        int[] minutes = new int[HOURS.length + 1];

        minutes[0] = OFF;

        for (int i = 0; i < HOURS.length; i++) {
            minutes[i + 1] = HOURS[i] * 60;
        }

        return minutes;
    }

    public static boolean isSet(int bedtimeMinutes) {
        for (int hour : HOURS) {
            if (bedtimeMinutes == hour * 60) {
                return true;
            }
        }

        return false; // includes OFF and every corrupted value
    }

    /**
     * Is the given minute of day inside the night window? A value outside {@link #HOURS} is
     * treated as OFF (a corrupted blob must never turn the mile marker into a night scene).
     */
    public static boolean isNight(int bedtimeMinutes, int nowMinutes) {
        if (!isSet(bedtimeMinutes)) {
            return false;
        }

        return nowMinutes >= bedtimeMinutes || nowMinutes < NIGHT_END_MINUTES;
    }

    public static int minutesOfDay(long nowMs) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(nowMs);

        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
    }

    public static String formatHour(int minutes) {
        return String.format(Locale.US, "%02d:00", minutes / 60);
    }

    public static int sanitize(int raw) {
        return isSet(raw) ? raw : OFF;
    }
}
