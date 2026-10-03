package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

/**
 * KIDS v1.2.4: one-time migration that clears the "poisoned" persisted dim/screen-off state
 * which caused the permanent black screen (user toggled screen-off dimming while testing
 * v1.2.0 brightness controls; the state survived updates and, with the 32.56-based
 * ScreensaverManager, put the app under an unremovable black overlay).
 *
 * Must run from MainApplication.onCreate — BEFORE any activity/ScreensaverManager reads prefs.
 * Idempotent via a stored flag; never clobbers legitimate user settings on later launches.
 *
 * KIDS (daily reset fix): also clears once the poisoned daily watch counters left behind
 * by builds that credited overnight standby gaps to the new day (see KidsModeController).
 */
public class KidsMigration {
    private static final String TAG = KidsMigration.class.getSimpleName();
    private static final String FLAG_KEY = "kids_migration_done_v124";
    private static final String FLAG_KEY_DAILY_RESET_FIX = "kids_migration_done_daily_reset_fix";

    private static volatile boolean sFirstLaunchAfterMigration;

    private KidsMigration() {
    }

    public static void migrateIfNeeded(Context context) {
        try {
            AppPrefs prefs = AppPrefs.instance(context);

            if (!"1".equals(prefs.getData(FLAG_KEY))) {
                PlayerTweaksData tweaks = PlayerTweaksData.instance(context);

                // Clear every persisted state that can render the screen black at startup
                tweaks.setBootScreenOffEnabled(false);
                tweaks.setScreenOffTimeoutEnabled(false);
                tweaks.setScreenOffDimmingPercents(100); // 100 = no partial dimming overlay

                // Reset our own brightness override to auto (follow system)
                KidsModeData.instance(context).setBrightnessPercent(KidsScreenHelper.BRIGHTNESS_AUTO);

                prefs.setData(FLAG_KEY, "1");
                sFirstLaunchAfterMigration = true;

                Log.d(TAG, "v1.2.4 migration: cleared boot screen-off / dimming / brightness state");
            }

            // KIDS FIX (daily reset bug): older builds credited overnight standby gaps
            // (anything < 12h) to the NEW day right after the midnight reset, persisting
            // a phantom used-time under today's date. That poisoned counter kept the
            // child in "time is up" state for the whole day, surviving app restarts.
            // Clear the daily counters once so an updated install starts fresh; the
            // fixed KidsModeController (midnight clamp + 3-min gap cap) prevents
            // re-poisoning from now on.
            if (!"1".equals(prefs.getData(FLAG_KEY_DAILY_RESET_FIX))) {
                KidsModeData kids = KidsModeData.instance(context);

                kids.setDailyUsedMs(0);
                kids.setDailyBonusMs(0);

                prefs.setData(FLAG_KEY_DAILY_RESET_FIX, "1");

                Log.d(TAG, "daily-reset-fix migration: cleared poisoned daily watch counters");
            }
        } catch (Throwable e) {
            Log.e(TAG, "Migration failed: " + e);
        }
    }

    /**
     * @return true once, on the first launch after the migration ran.
     * The caller uses it to force-disable any LIVE system screensaver (clearing prefs
     * alone doesn't dismiss an already-active screensaver session).
     */
    public static boolean consumeFirstLaunch() {
        boolean result = sFirstLaunchAfterMigration;
        sFirstLaunchAfterMigration = false;
        return result;
    }
}
