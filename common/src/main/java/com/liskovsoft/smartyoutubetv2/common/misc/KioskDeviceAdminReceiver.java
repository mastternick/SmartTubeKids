package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * Device Admin receiver that enables full Kiosk (Lock Task) mode.<br/>
 * <br/>
 * Activate ONCE from a computer (or any shell) with:<br/>
 * <code>adb shell dpm set-device-owner &lt;applicationId&gt;/com.liskovsoft.smartyoutubetv2.common.misc.KioskDeviceAdminReceiver</code><br/>
 * <br/>
 * Notes:<br/>
 * - Works only when no accounts are added to the device and the app isn't Device Owner yet.<br/>
 * - Without Device Owner the app falls back to screen pinning (see {@link KioskManager}).<br/>
 * - Deactivate with: <code>adb shell dpm remove-active-admin &lt;applicationId&gt;/com.liskovsoft.smartyoutubetv2.common.misc.KioskDeviceAdminReceiver</code>
 */
public class KioskDeviceAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = KioskDeviceAdminReceiver.class.getSimpleName();

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);

        Log.d(TAG, "Device Owner enabled. Applying kiosk policies...");

        KioskManager.instance(context).onDeviceOwnerEnabled();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);

        Log.d(TAG, "Device Owner disabled. Cleaning up kiosk state...");

        // System resets lock task whitelists and persistent preferred activities automatically.
        // Only local state and the HOME alias need to be cleaned up here.
        KioskManager.instance(context).onDeviceOwnerDisabled();
    }
}
