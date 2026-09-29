package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import com.liskovsoft.sharedutils.helpers.Helpers;

import java.util.ArrayList;
import java.util.List;

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
    private int mBrightnessPercent;        // KIDS: -1 = auto/system, 10..100 = override
    private boolean mIsMenuProviderRegistered; // KIDS: one-time menu item activation
    private long mVisibleSections;         // KIDS: bitmask of sidebar sections visible in Kids Mode (-1 = not configured yet)
    private boolean mIsSearchEnabled;      // KIDS: allow search in Kids Mode (default true)
    private String mKidsPlaylists;         // KIDS: selected account playlists, "playlistId|title" entries, list-delim separated
    private List<String> mKidsPlaylistList = new ArrayList<>(); // KIDS: parsed view of mKidsPlaylists
    private boolean mIsKioskEnabled;       // KIDS: kiosk mode (Lock Task) - child cannot leave the app
    private String mHiddenPins;            // KIDS F4: snapshot of parent pins hidden while Kids Mode is on ("id|title" entries)
    private List<String> mHiddenPinsList = new ArrayList<>(); // KIDS F4: parsed view of mHiddenPins

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

    // --- Brightness (KIDS) ---

    /**
     * @return -1 for auto (follow system), or 10..100 percent override.
     */
    public int getBrightnessPercent() {
        return mBrightnessPercent;
    }

    public void setBrightnessPercent(int percent) {
        mBrightnessPercent = percent;
        persistData();
    }

    // --- Menu provider init flag (KIDS) ---

    public boolean isMenuProviderRegistered() {
        return mIsMenuProviderRegistered;
    }

    public void setMenuProviderRegistered(boolean registered) {
        mIsMenuProviderRegistered = registered;
        persistData();
    }

    // --- Sidebar sections (KIDS) ---

    /**
     * @return bitmask of section ids (MediaGroup.TYPE_*) visible in Kids Mode;
     *         -1 means "not configured yet" (first activation decides).
     */
    public long getVisibleSections() {
        return mVisibleSections;
    }

    public void setVisibleSections(long sectionsMask) {
        mVisibleSections = sectionsMask;
        persistData();
    }

    public boolean isSectionVisible(int sectionId) {
        return (mVisibleSections & (1L << sectionId)) != 0;
    }

    public void setSectionVisible(int sectionId, boolean visible) {
        if (visible) {
            mVisibleSections |= (1L << sectionId);
        } else {
            mVisibleSections &= ~(1L << sectionId);
        }
        persistData();
    }

    // --- Search (KIDS) ---

    public boolean isSearchEnabled() {
        return mIsSearchEnabled;
    }

    public void setSearchEnabled(boolean enabled) {
        mIsSearchEnabled = enabled;
        persistData();
    }

    // --- Kids playlists (KIDS) ---

    /**
     * Playlists selected by the parent for the child.
     * Each entry: "playlistId|title".
     * Empty list = show ALL account playlists (per decision A); the Playlists
     * tab itself is controlled by the sections mask.
     */
    public List<String> getKidsPlaylists() {
        return mKidsPlaylistList;
    }

    public void setKidsPlaylists(List<String> playlists) {
        mKidsPlaylistList = playlists != null ? new ArrayList<>(playlists) : new ArrayList<>();
        mKidsPlaylists = Helpers.mergeList(mKidsPlaylistList);
        persistData();
    }

    public boolean isPlaylistSelected(String playlistId) {
        for (String entry : mKidsPlaylistList) {
            if (entry.startsWith(playlistId + "|")) {
                return true;
            }
        }
        return false;
    }

    public void addPlaylist(String playlistId, String title) {
        if (!isPlaylistSelected(playlistId)) {
            mKidsPlaylistList.add(playlistId + "|" + title);
            mKidsPlaylists = Helpers.mergeList(mKidsPlaylistList);
            persistData();
        }
    }

    public void removePlaylist(String playlistId) {
        Helpers.removeIf(mKidsPlaylistList, entry -> entry.startsWith(playlistId + "|"));
        mKidsPlaylists = Helpers.mergeList(mKidsPlaylistList);
        persistData();
    }

    // --- Kiosk mode (KIDS) ---

    /**
     * KIDS: kiosk mode (Android Lock Task). When ON the app re-locks itself on
     * every activity resume and BACK never exits the app. See KioskModeManager.
     */
    public boolean isKioskEnabled() {
        return mIsKioskEnabled;
    }

    public void setKioskEnabled(boolean enabled) {
        mIsKioskEnabled = enabled;
        persistData();
    }

    // --- Hidden parent pins (KIDS F4) ---

    /**
     * KIDS F4: snapshot of the parent's pinned sidebar items (playlists/channels)
     * hidden while Kids Mode is on. Entries: "id|title" (id = playlistId OR channelId).
     * Items are re-pinned and the snapshot cleared when Kids Mode goes off.
     */
    public List<String> getHiddenPins() {
        return mHiddenPinsList;
    }

    public void setHiddenPins(List<String> pins) {
        mHiddenPinsList = pins != null ? new ArrayList<>(pins) : new ArrayList<>();
        mHiddenPins = Helpers.mergeList(mHiddenPinsList);
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
        mBrightnessPercent    = Helpers.parseInt(split, 10, -1); // -1 = auto
        mIsMenuProviderRegistered = Helpers.parseBoolean(split, 11, false);
        mVisibleSections      = Helpers.parseLong(split, 12, -1); // -1 = not configured yet
        mIsSearchEnabled      = Helpers.parseBoolean(split, 13, true); // KIDS: search default ON per parent decision
        mKidsPlaylists        = Helpers.parseStr(split, 14);
        mKidsPlaylistList     = new ArrayList<>(Helpers.parseStrList(split, 14));
        mIsKioskEnabled       = Helpers.parseBoolean(split, 15, false); // KIDS: kiosk default OFF
        mHiddenPins           = Helpers.parseStr(split, 16); // KIDS F4: hidden parent pins snapshot
        mHiddenPinsList       = new ArrayList<>(Helpers.parseStrList(split, 16));
    }

    private void persistData() {
        mAppPrefs.setData(KIDS_MODE_DATA,
                Helpers.mergeData(mIsEnabled, mPin, mTimerMinutes,
                        mBlockShorts, mBlockRecommendations, mCalmExit,
                        mDailyDate, mDailyUsedMs, mDailyBonusMs, mIsPinEnabled, mBrightnessPercent,
                        mIsMenuProviderRegistered, mVisibleSections, mIsSearchEnabled, mKidsPlaylists,
                        mIsKioskEnabled, mHiddenPins));
    }
}
