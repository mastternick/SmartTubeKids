package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build.VERSION;

import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * Starts the app after boot when kiosk mode is enabled.<br/>
 * <br/>
 * With Device Owner this is only a safety net: the persistent preferred HOME activity
 * already makes the system start the app on boot. Without Device Owner (screen pinning
 * fallback) this receiver brings the app to the foreground so it can re-pin itself
 * via {@link KioskManager#applyOnResume}.<br/>
 * <br/>
 * NOTE: BOOT_COMPLETED receivers are exempt from background activity start restrictions.
 */
public class KioskBootReceiver extends BroadcastReceiver {
    private static final String TAG = KioskBootReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        KioskManager kioskManager = KioskManager.instance(context);

        if (!kioskManager.isKioskEnabled()) {
            return;
        }

        Log.d(TAG, "Boot completed. Starting kiosk app...");

        try {
            Intent launchIntent = null;

            if (VERSION.SDK_INT >= 21) {
                launchIntent = context.getPackageManager().getLeanbackLaunchIntentForPackage(context.getPackageName());
            }

            if (launchIntent == null) {
                launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            }

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(launchIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "onReceive error: " + e.getMessage());
        }
    }
}
