package com.liskovsoft.smartyoutubetv2.common.misc;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

public class RemoteControlReceiver extends BroadcastReceiver {
    private static final String TAG = RemoteControlReceiver.class.getSimpleName();

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Initializing remote control listener...");

        // Fix unload from the memory on some devices?
        // NOTE: Starting from Android 12 (api 31) foreground service with type 'connectedDevice' not supported
        // Use 'mediaPlayback' type instead
        try {
            Utils.updateRemoteControlService(context);
        } catch (Exception e) {
            // ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to mAllowStartForeground false (Android 12)
            e.printStackTrace();
        }

        // KIDS v1.7: this receiver is the app's boot entry point, so it is also where
        // the kiosk "re-lock after a reboot" option is honoured (default OFF: a
        // PIN-approved exit survives the power cycle until the app is opened again).
        try {
            if (isBootAction(intent != null ? intent.getAction() : null)) {
                KioskModeManager.onDeviceBooted(context);
            }
        } catch (Throwable e) {
            Log.e(TAG, e); // a boot broadcast must never crash the app
        }
    }

    // Genuine boot broadcasts only. Literals on purpose: Intent
    // .ACTION_LOCKED_BOOT_COMPLETED is API 24 and referencing it trips lint NewApi
    // against minSdk 17 (same rule as the kiosk keycode literals), and
    // QUICKBOOT_POWERON is a vendor action that is not in the SDK at all.
    private static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    private static final String ACTION_BOOT_COMPLETED_ALT = "android.intent.action.ACTION_BOOT_COMPLETED";
    private static final String ACTION_LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED";
    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_QUICKBOOT_POWERON_HTC = "com.htc.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_REBOOT = "android.intent.action.REBOOT";

    /**
     * KIDS v1.7: TRUE only for a real reboot, never for the other actions this
     * receiver also listens to (SCREEN_ON, TIME_SET, TIMEZONE_CHANGED,
     * POWER_CONNECTED). Waking from standby must not re-arm kiosk, or the app would be
     * dragged back on screen seconds after the parent left with the PIN.
     */
    private static boolean isBootAction(String action) {
        return ACTION_BOOT_COMPLETED.equals(action)
                || ACTION_BOOT_COMPLETED_ALT.equals(action)
                || ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)
                || ACTION_QUICKBOOT_POWERON_HTC.equals(action)
                || ACTION_REBOOT.equals(action);
    }
}
