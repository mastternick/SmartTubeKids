package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.admin.DeviceAdminReceiver;

/**
 * KIDS: minimal device admin receiver required for the FULL kiosk mode.
 *
 * The app becomes device owner through ADB (see KIOSK.md), right after a fresh
 * install and BEFORE the app is opened for the first time:
 *
 *   adb shell dpm set-device-owner app.smarttubekids/com.liskovsoft.smartyoutubetv2.common.misc.KioskDeviceAdminReceiver
 *
 * As device owner, {@link KioskModeManager} can allowlist the package for Lock
 * Task (no confirmation prompts, HOME/RECENTS blocked) and register the app as
 * the persistent HOME (auto-launch after boot). No device policies (password
 * rules, wipe, etc.) are used — the receiver exists only to enable kiosk mode.
 */
public class KioskDeviceAdminReceiver extends DeviceAdminReceiver {
}
