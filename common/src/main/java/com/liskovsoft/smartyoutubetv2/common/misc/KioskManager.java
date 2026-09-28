package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build.VERSION;

import com.liskovsoft.sharedutils.mylogger.Log;

import java.lang.ref.WeakReference;

/**
 * Kiosk (Lock Task) mode manager.<br/>
 * <br/>
 * Two levels are supported:<br/>
 * <br/>
 * <b>Level 1 — Device Owner (full kiosk, recommended):</b> after a one-time shell command
 * (<code>adb shell dpm set-device-owner &lt;applicationId&gt;/com.liskovsoft.smartyoutubetv2.common.misc.KioskDeviceAdminReceiver</code>)
 * the app becomes a real kiosk: HOME/RECENTS are blocked, notifications are hidden, no confirmation
 * dialogs are shown, and after a reboot the app starts directly and re-locks itself
 * (persistent preferred HOME activity + {@link KioskBootReceiver} fallback).<br/>
 * <br/>
 * <b>Level 2 — no Device Owner (screen pinning fallback, works without any shell command):</b>
 * the app pins itself via {@link Activity#startLockTask()}. The system shows a confirmation
 * message and the user can unpin by holding BACK (plus system "ask for PIN on unpin" applies
 * if configured). The pinning is re-applied on every activity resume and after reboot.<br/>
 * <br/>
 * The only exit is the PIN protected switch in General settings.
 */
public class KioskManager {
    private static final String TAG = KioskManager.class.getSimpleName();
    private static final String KIOSK_PREFS = "kiosk_prefs";
    private static final String KIOSK_ENABLED = "kiosk_enabled";
    // Public equivalent of the hidden DevicePolicyManager.LOCK_TASK_FEATURE_ALL: enable all lock task features.
    // setLockTaskFeatures takes a mask of ENABLED features (see AOSP DevicePolicyManager).
    private static final int LOCK_TASK_FEATURE_ALL = ~0;
    // HOME alias declared in the app manifest (smarttubetv module). Disabled by default.
    // It's activated together with Device Owner policies so the system treats the app as HOME.
    private static final String HOME_ALIAS = "com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivityHome";

    private static KioskManager sInstance;
    private final Context mContext;
    private final SharedPreferences mPrefs;
    private WeakReference<Activity> mCurrentActivity;

    private KioskManager(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(KIOSK_PREFS, Context.MODE_PRIVATE);
    }

    public static KioskManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new KioskManager(context);
        }

        return sInstance;
    }

    /**
     * Whether this app holds the Device Owner role (granted once via <code>dpm set-device-owner</code>).
     */
    public boolean isDeviceOwner() {
        if (VERSION.SDK_INT < 21) {
            return false;
        }

        DevicePolicyManager dpm = getDevicePolicyManager();

        try {
            return dpm != null && dpm.isDeviceOwnerApp(mContext.getPackageName());
        } catch (Exception e) {
            // Some buggy OEM builds throw here
            Log.e(TAG, "isDeviceOwnerApp error: " + e.getMessage());
        }

        return false;
    }

    /**
     * Whether kiosk mode is switched on (PIN protected switch in General settings).
     */
    public boolean isKioskEnabled() {
        return mPrefs.getBoolean(KIOSK_ENABLED, false);
    }

    /**
     * Turn kiosk mode on. Applies Device Owner policies when available,
     * otherwise falls back to screen pinning.
     */
    public void enable() {
        Log.d(TAG, "Enabling kiosk mode. Device owner: " + isDeviceOwner());

        mPrefs.edit().putBoolean(KIOSK_ENABLED, true).apply();

        setHomeAliasEnabled(true);
        applyDeviceOwnerPolicies();

        // Lock task itself is started from the next Activity.onResume (see applyOnResume)
    }

    /**
     * Turn kiosk mode off. Called only from the PIN protected switch in General settings.
     */
    public void disable() {
        Log.d(TAG, "Disabling kiosk mode");

        mPrefs.edit().putBoolean(KIOSK_ENABLED, false).apply();

        stopLockTaskIfNeeded();
        clearDeviceOwnerPolicies();
        setHomeAliasEnabled(false);
    }

    /**
     * Hook for activities (called from MotherActivity.onResume).
     * Re-applies the lock each time an activity of the app comes to the foreground.
     */
    public void applyOnResume(Activity activity) {
        mCurrentActivity = new WeakReference<>(activity);

        if (!isKioskEnabled()) {
            return;
        }

        startLockTaskIfNeeded(activity);
    }

    /**
     * Called from {@link KioskDeviceAdminReceiver#onEnabled} right after <code>dpm set-device-owner</code>.
     * Upgrades an already enabled screen pinning kiosk to a full Device Owner kiosk.
     */
    public void onDeviceOwnerEnabled() {
        applyDeviceOwnerPolicies();

        if (isKioskEnabled()) {
            setHomeAliasEnabled(true);
        }
    }

    /**
     * Called from {@link KioskDeviceAdminReceiver#onDisabled}
     * (e.g. after <code>dpm remove-active-admin</code> or factory policy reset).
     */
    public void onDeviceOwnerDisabled() {
        // Lock task whitelists and persistent preferred activities are reset by the system.
        // Keep the kiosk flag: screen pinning fallback will continue to work.
        setHomeAliasEnabled(false);
    }

    private void applyDeviceOwnerPolicies() {
        if (VERSION.SDK_INT < 21 || !isDeviceOwner()) {
            return;
        }

        DevicePolicyManager dpm = getDevicePolicyManager();
        ComponentName admin = getAdminComponent();

        try {
            // Allow lock task for this package only (startLockTask won't show confirmations)
            dpm.setLockTaskPackages(admin, new String[]{mContext.getPackageName()});

            // Block HOME, RECENTS, NOTIFICATIONS, GLOBAL_ACTIONS etc inside lock task (API 28+)
            if (VERSION.SDK_INT >= 28) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            }

            // Make the app the preferred HOME: starts on boot, HOME key returns to the app
            ComponentName launcher = getLauncherComponent();

            if (launcher != null) {
                IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
                homeFilter.addCategory(Intent.CATEGORY_HOME);
                homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
                dpm.addPersistentPreferredActivity(admin, homeFilter, launcher);
            }
        } catch (Exception e) {
            Log.e(TAG, "applyDeviceOwnerPolicies error: " + e.getMessage());
        }
    }

    private void clearDeviceOwnerPolicies() {
        if (VERSION.SDK_INT < 21 || !isDeviceOwner()) {
            return;
        }

        DevicePolicyManager dpm = getDevicePolicyManager();
        ComponentName admin = getAdminComponent();

        try {
            dpm.clearPackagePersistentPreferredActivities(admin, mContext.getPackageName());
            dpm.setLockTaskPackages(admin, new String[]{});

            if (VERSION.SDK_INT >= 28) {
                dpm.setLockTaskFeatures(admin, LOCK_TASK_FEATURE_ALL);
            }
        } catch (Exception e) {
            Log.e(TAG, "clearDeviceOwnerPolicies error: " + e.getMessage());
        }
    }

    private void startLockTaskIfNeeded(Activity activity) {
        if (VERSION.SDK_INT < 21) {
            return;
        }

        try {
            if (isLockTaskRunning()) {
                return;
            }

            // Device Owner: silent lock. Otherwise: system screen pinning (with confirmation).
            activity.startLockTask();
        } catch (Exception e) {
            // IllegalStateException on some devices when the activity state isn't valid
            Log.e(TAG, "startLockTask error: " + e.getMessage());
        }
    }

    private void stopLockTaskIfNeeded() {
        if (VERSION.SDK_INT < 21) {
            return;
        }

        Activity activity = mCurrentActivity != null ? mCurrentActivity.get() : null;

        try {
            if (activity != null) {
                activity.stopLockTask();
            }
            // NOTE: DevicePolicyManager.stopLockTask() is a SystemApi and can't be used here.
            // The activity reference is always set in practice (disable is called from the settings dialog activity).
        } catch (Exception e) {
            // IllegalStateException: not in lock task — safe to ignore
            Log.e(TAG, "stopLockTask error: " + e.getMessage());
        }
    }

    private boolean isLockTaskRunning() {
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);

        if (am == null) {
            return false;
        }

        if (VERSION.SDK_INT < 23) {
            // ActivityManager.isLockTaskModeRunning() isn't available in the modern SDK stubs.
            // Returning false is safe: startLockTask() is a no-op when the task is already pinned.
            return false;
        }

        return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    private void setHomeAliasEnabled(boolean enabled) {
        try {
            ComponentName alias = new ComponentName(mContext.getPackageName(), HOME_ALIAS);

            mContext.getPackageManager().setComponentEnabledSetting(
                    alias,
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            // Alias isn't declared in some modules (e.g. leanbackassistant) — ignore
            Log.e(TAG, "setHomeAliasEnabled error: " + e.getMessage());
        }
    }

    /**
     * Resolves the app's own launcher activity (LEANBACK_LAUNCHER first, LAUNCHER as fallback).
     */
    private ComponentName getLauncherComponent() {
        ComponentName result = resolveLauncher(Intent.CATEGORY_LEANBACK_LAUNCHER);

        if (result == null) {
            result = resolveLauncher(Intent.CATEGORY_LAUNCHER);
        }

        return result;
    }

    private ComponentName resolveLauncher(String category) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(category);
            intent.setPackage(mContext.getPackageName());

            ResolveInfo info = mContext.getPackageManager().resolveActivity(intent, 0);

            if (info != null && info.activityInfo != null) {
                return new ComponentName(mContext.getPackageName(), info.activityInfo.name);
            }
        } catch (Exception e) {
            Log.e(TAG, "resolveLauncher error: " + e.getMessage());
        }

        return null;
    }

    private DevicePolicyManager getDevicePolicyManager() {
        return (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private ComponentName getAdminComponent() {
        return new ComponentName(mContext, KioskDeviceAdminReceiver.class);
    }
}
