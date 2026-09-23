package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import com.liskovsoft.sharedutils.helpers.Helpers;

/**
 * KIDS: Persistent storage for Kids Mode settings + daily watch-time counters.
 * Pattern follows existing prefs classes (SponsorBlockData, SearchData).
 */
public class KidsModeData {
    private static final String KIDS_MODE_DATA = "kids_mode_data";

    @SuppressLint("StaticFieldLeak")
    private static KidsModeData sInstance;

    private final AppPrefs mAppPrefs;

    private boolean mIsEnabled;
    private String mPin;                   // null = no PIN set
    private boolean mIsPinEnabled;         // KIDS: PIN protection on/off (independent toggle)
    private int mTimerMinutes;             // 0 = no limit; >0 = daily watch limit
    private boolean mBlockShorts;
    private boolean mBlockRecommendations; // no "next video" at end + no suggestion rows
    private boolean mCalmExit;             // show warnings before limit expires

    // Daily counters (persisted so they survive app restarts)
    private String mDailyDate;             // yyyy-MM-dd of the counters
    private long mDailyUsedMs;             // watch time accumulated today
    private long mDailyBonusMs;            // extra minutes granted by parent today

    private KidsModeData(Context context) {
        mAppPrefs = AppPrefs.instance(context);
        restoreData();
    }

    public static KidsModeData instance(Context context) {
        if (sInstance == null) {
            sInstance = new KidsModeData(context.getApplicationContext());
        }
        return sInstance;
    }

    // --- Enabled ---

    public boolean isEnabled() {
        return mIsEnabled;
    }

    public void setEnabled(boolean enabled) {
        mIsEnabled = enabled;
        persistData();
    }

    // --- PIN ---

    public String getPin() {
        return mPin;
    }

    public void setPin(String pin) {
        mPin = pin;
        persistData();
    }

    public boolean hasPin() {
        return mPin != null && !mPin.isEmpty();
    }

    /**
     * KIDS: PIN protection toggle. When disabled, no PIN is asked anywhere,
     * even if a PIN value is still stored.
     */
    public boolean isPinEnabled() {
        return mIsPinEnabled && hasPin();
    }

    public void setPinEnabled(boolean enabled) {
        mIsPinEnabled = enabled;
        persistData();
    }

    // --- Timer ---

    public int getTimerMinutes() {
        return mTimerMinutes;
    }

    public void setTimerMinutes(int minutes) {
        mTimerMinutes = minutes;
        persistData();
    }

    // --- Block Shorts ---

    public boolean isBlockShortsActive() {
        return mIsEnabled && mBlockShorts;
    }

    public boolean isBlockShorts() {
        return mBlockShorts;
    }

    public void setBlockShorts(boolean block) {
        mBlockShorts = block;
        persistData();
    }

    // --- Block Recommendations (auto-next + suggestion rows) ---

    public boolean isAutoNextBlocked() {
        return mIsEnabled && mBlockRecommendations;
    }

    public boolean isBlockRecommendations() {
        return mBlockRecommendations;
    }

    public void setBlockRecommendations(boolean block) {
        mBlockRecommendations = block;
        persistData();
    }

    // --- Calm Exit ---

    public boolean isCalmExit() {
        return mCalmExit;
    }

    public void setCalmExit(boolean calmExit) {
        mCalmExit = calmExit;
        persistData();
    }

    // --- Daily counters ---

    public String getDailyDate() {
        return mDailyDate;
    }

    public void setDailyDate(String date) {
        mDailyDate = date;
        persistData();
    }

    public long getDailyUsedMs() {
        return mDailyUsedMs;
    }

    public void setDailyUsedMs(long ms) {
        mDailyUsedMs = ms;
        persistData();
    }

    public void addDailyUsedMs(long deltaMs) {
        mDailyUsedMs += deltaMs;
        persistData();
    }

    public long getDailyBonusMs() {
        return mDailyBonusMs;
    }

    public void setDailyBonusMs(long ms) {
        mDailyBonusMs = ms;
        persistData();
    }

    // --- Persistence (same pattern as SearchData) ---

    private void restoreData() {
        String data = mAppPrefs.getData(KIDS_MODE_DATA);
        String[] split = Helpers.splitData(data);

        mIsEnabled            = Helpers.parseBoolean(split, 0, false);
        mPin                  = Helpers.parseStr(split, 1);
        mTimerMinutes         = Helpers.parseInt(split, 2, 0);
        mBlockShorts          = Helpers.parseBoolean(split, 3, true);  // default ON
        mBlockRecommendations = Helpers.parseBoolean(split, 4, true);  // default ON
        mCalmExit             = Helpers.parseBoolean(split, 5, true);  // default ON
        mDailyDate            = Helpers.parseStr(split, 6);
        mDailyUsedMs          = Helpers.parseLong(split, 7, 0);
        mDailyBonusMs         = Helpers.parseLong(split, 8, 0);
        // Migration from v1.0: if a PIN exists but the flag was never stored, keep protection on
        mIsPinEnabled         = Helpers.parseBoolean(split, 9, mPin != null && !mPin.isEmpty());
    }

    private void persistData() {
        mAppPrefs.setData(KIDS_MODE_DATA,
                Helpers.mergeData(mIsEnabled, mPin, mTimerMinutes,
                        mBlockShorts, mBlockRecommendations, mCalmExit,
                        mDailyDate, mDailyUsedMs, mDailyBonusMs, mIsPinEnabled));
    }
}
