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
 */
public class KidsMigration {
    private static final String TAG = KidsMigration.class.getSimpleName();
    private static final String FLAG_KEY = "kids_m…_v124";

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
