package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;

import java.util.ArrayList;
import java.util.List;

/**
 * KIDS: manages the sidebar (left menu) while Kids Mode is active.
 *
 * Rules (confirmed with parent):
 *  - Kids Mode ON: hide ALL tabs except the ones checked in Kids settings
 *    (default: My videos + Playlists). Settings tab is ALWAYS visible
 *    (anti-lockout) and placed LAST.
 *  - AC3: the Shorts tab is NEVER visible while Kids Mode is ON — the
 *    "Block shorts" switch only filters content, not the tab.
 *  - Selected "kids playlists" are pinned at the TOP of the sidebar. Empty
 *    selection = nothing pinned; the Playlists tab (default ON) then shows
 *    all account playlists.
 *  - F4=a: parent-pinned playlists/channels are HIDDEN while Kids Mode is on
 *    (snapshot in KidsModeData, index 16) and restored when Kids Mode goes
 *    OFF. Only the pins selected for the child remain visible.
 *  - Search top button follows the "Allow search" switch (default ON).
 *  - Kids Mode OFF: restore all sections, unpin kids playlists, restore the
 *    parent's hidden pins.
 *
 * IMPORTANT (lesson from the v1.2.0 black screen): this class must NEVER be called
 * from a singleton constructor / restoreState path. Only from:
 *  - BrowsePresenter.onViewInitialized (view-ready, singletons constructed)
 *  - Kids settings dialog callbacks (user actions)
 * All methods are idempotent — safe to call repeatedly.
 */
public class KidsSidebarManager {
    private static final String TAG = KidsSidebarManager.class.getSimpleName();

    /** Sidebar tabs controllable from Kids settings (SHORTS is always hidden while Kids is on; SETTINGS always shown). */
    public static final int[] KIDS_SECTIONS = {
            MediaGroup.TYPE_HOME,
            MediaGroup.TYPE_TRENDING,
            MediaGroup.TYPE_KIDS_HOME,
            MediaGroup.TYPE_SPORTS,
            MediaGroup.TYPE_LIVE,
            MediaGroup.TYPE_GAMING,
            MediaGroup.TYPE_NEWS,
            MediaGroup.TYPE_MUSIC,
            MediaGroup.TYPE_SUBSCRIPTIONS,
            MediaGroup.TYPE_HISTORY,
            MediaGroup.TYPE_USER_PLAYLISTS,
            MediaGroup.TYPE_MY_VIDEOS,
            MediaGroup.TYPE_CHANNEL_UPLOADS,
            MediaGroup.TYPE_NOTIFICATIONS,
            MediaGroup.TYPE_BLOCKED_CHANNELS
    };

    private KidsSidebarManager() {
    }

    // --- Sections mask helpers (AC1) ---

    /** Default kids sidebar: My videos + Playlists (Settings is force-shown separately). */
    public static long defaultSectionsMask() {
        return (1L << MediaGroup.TYPE_MY_VIDEOS) | (1L << MediaGroup.TYPE_USER_PLAYLISTS);
    }

    public static long currentSectionsMask(KidsModeData kids) {
        long mask = kids.getVisibleSections();
        return mask == -1 ? defaultSectionsMask() : mask;
    }

    /** Checkbox state for the Kids settings UI (renders the default before it is ever persisted). */
    public static boolean isSectionCheckedForUI(KidsModeData kids, int sectionId) {
        return (currentSectionsMask(kids) & (1L << sectionId)) != 0;
    }

    /** MUST run before setSectionVisible: writing into raw -1 would make EVERYTHING visible. */
    public static void ensureConfigured(KidsModeData kids) {
        if (kids.getVisibleSections() == -1) {
            kids.setVisibleSections(defaultSectionsMask());
        }
    }

    /**
     * Idempotent sync for view-init paths. When Kids Mode is OFF it only cleans up
     * leftover pinned playlists and restores leftover hidden parent pins — it never
     * touches the user's own section setup.
     */
    public static void applyKidsState(Context context) {
        applyState(context, false);
    }

    /**
     * Full apply/restore. Call from Kids settings toggles (enable/disable Kids Mode etc).
     */
    public static void forceApply(Context context) {
        applyState(context, true);
    }

    private static void applyState(Context context, boolean force) {
        if (context == null) {
            return;
        }

        try {
            KidsModeData kids = KidsModeData.instance(context);
            BrowsePresenter browse = BrowsePresenter.instance(context);

            if (kids.isEnabled()) {
                applyEnabled(context, kids, browse);
            } else {
                unpinKidsPlaylists(context, kids, browse);
                restoreParentPins(context, kids, browse); // F4=a: give the parent's pins back
                if (force) {
                    restoreAll(browse, context);
                }
            }
        } catch (Throwable e) {
            // KIDS: never let sidebar management break app startup
            Log.e(TAG, "applyState failed: %s", e);
        }
    }

    private static void applyEnabled(Context context, KidsModeData kids, BrowsePresenter browse) {
        ensureConfigured(kids); // AC1: persist the default instead of raw -1
        long mask = kids.getVisibleSections();
        SidebarService sidebar = SidebarService.instance(context);

        // 1) Show/hide tabs per the parent's selection
        //    (skip no-ops: enableSection rebuilds the whole sidebar on every call)
        for (int sectionId : KIDS_SECTIONS) {
            boolean visible = (mask & (1L << sectionId)) != 0;

            if (sidebar != null && sidebar.isSectionPinned(sectionId) == visible) {
                continue;
            }

            browse.enableSection(sectionId, visible);
        }

        // 2) AC3: Shorts never visible while Kids is ON
        if (sidebar == null || sidebar.isSectionPinned(MediaGroup.TYPE_SHORTS)) {
            browse.enableSection(MediaGroup.TYPE_SHORTS, false);
        }

        // 3) Kids playlists pinned on top; empty list = nothing pinned,
        //    the Playlists tab (default ON) shows all account playlists
        List<String> playlists = kids.getKidsPlaylists();

        if (sidebar != null) {
            // Pin in reverse, moving each to top -> final order matches selection order
            for (int i = playlists.size() - 1; i >= 0; i--) {
                Video playlist = kidsPlaylistVideo(playlists.get(i));

                if (!browse.isItemPinned(playlist)) {
                    browse.pinItem(playlist);
                }

                sidebar.movePinnedItemToTop(playlist.getId());
            }
        }

        // 4) F4=a: hide the parent's pins — the child sees only what was picked for them
        hideParentPins(context, kids, browse);

        // 5) Settings always visible and LAST (anti-lockout)
        browse.enableSection(MediaGroup.TYPE_SETTINGS, true);

        if (sidebar != null) {
            sidebar.movePinnedItemToBottom(MediaGroup.TYPE_SETTINGS);
        }

        // 6) Search top button (decision C: switch, default ON)
        MainUIData mainUI = MainUIData.instance(context);

        if (kids.isSearchEnabled()) {
            mainUI.setTopButtonEnabled(MainUIData.TOP_BUTTON_SEARCH);
        } else {
            mainUI.setTopButtonDisabled(MainUIData.TOP_BUTTON_SEARCH);
        }

        browse.updateSections();
    }

    private static void restoreAll(BrowsePresenter browse, Context context) {
        browse.enableAllSections(true);
        // KIDS: enableAllSections omits default-on tabs — without these they would be
        // lost for good after Kids OFF (Notifications/Queue/Blocked stay hidden by design)
        browse.enableSection(MediaGroup.TYPE_KIDS_HOME, true);
        browse.enableSection(MediaGroup.TYPE_SPORTS, true);
        browse.enableSection(MediaGroup.TYPE_LIVE, true);
        browse.enableSection(MediaGroup.TYPE_MY_VIDEOS, true);
        browse.enableSection(MediaGroup.TYPE_SETTINGS, true);
        // Restore the search button to the app default when leaving Kids Mode
        MainUIData.instance(context).setTopButtonEnabled(MainUIData.TOP_BUTTON_SEARCH);
        browse.updateSections();
    }

    private static void unpinKidsPlaylists(Context context, KidsModeData kids, BrowsePresenter browse) {
        boolean changed = false;

        for (String entry : kids.getKidsPlaylists()) {
            Video playlist = kidsPlaylistVideo(entry);

            if (browse.isItemPinned(playlist)) {
                browse.unpinItem(playlist);
                changed = true;
            }
        }

        if (changed) {
            browse.updateSections();
        }
    }

    // --- F4=a: hide/restore the parent's pins ---

    /**
     * Hides (unpins) every parent-pinned playlist/channel that was not selected for the
     * child, snapshotting it into KidsModeData so Kids OFF can put it back. Items that
     * cannot be represented faithfully in the snapshot (channel groups, legacy
     * reloadPageKey-only pins) are left visible rather than risking data loss.
     * Idempotent: the snapshot is merged, never dropped, and only written on change.
     */
    private static void hideParentPins(Context context, KidsModeData kids, BrowsePresenter browse) {
        SidebarService sidebar = SidebarService.instance(context);

        if (sidebar == null) {
            return;
        }

        List<String> hidden = new ArrayList<>(kids.getHiddenPins());
        boolean changed = false;

        // Copy: unpinItem mutates the live pinned list
        for (Video item : new ArrayList<>(sidebar.getPinnedItems())) {
            if (item == null || item.sectionId != -1) { // sections are governed by the mask, not pins
                continue;
            }

            if (isKidsItem(item, kids)) {
                continue;
            }

            String id = item.playlistId != null && !item.playlistId.isEmpty() ? item.playlistId : item.channelId;

            if (id == null || id.isEmpty()) { // channel group / legacy pin: can't snapshot -> leave it
                continue;
            }

            String entry = id + "|" + (item.getTitle() != null ? item.getTitle() : "");

            if (!hidden.contains(entry)) {
                hidden.add(entry);
            }

            browse.unpinItem(item);
            changed = true;
        }

        if (changed) {
            kids.setHiddenPins(hidden);
        }
    }

    /**
     * Re-pins the snapshotted parent pins (Kids Mode OFF). Entries that cannot be pinned
     * (e.g. the browse view is not ready) stay in the snapshot and retry on the next apply.
     */
    private static void restoreParentPins(Context context, KidsModeData kids, BrowsePresenter browse) {
        List<String> hidden = kids.getHiddenPins();

        if (hidden.isEmpty()) {
            return;
        }

        List<String> remaining = new ArrayList<>();
        boolean changed = false;

        for (String entry : hidden) {
            Video item = hiddenPinVideo(entry);

            if (item == null) { // malformed entry: drop it
                continue;
            }

            if (!browse.isItemPinned(item)) {
                browse.pinItem(item);
            }

            if (browse.isItemPinned(item)) {
                changed = true;
            } else {
                remaining.add(entry); // view not ready etc -> retry on the next apply
            }
        }

        kids.setHiddenPins(remaining);

        if (changed) {
            browse.updateSections();
        }
    }

    /** True when the pinned item was selected for the child (kids playlists picker). */
    public static boolean isKidsItem(Video item, KidsModeData kids) {
        if (item == null) {
            return false;
        }

        for (String entry : kids.getKidsPlaylists()) {
            int sep = entry.indexOf('|');
            String id = sep == -1 ? entry : entry.substring(0, sep);

            if (!id.isEmpty() && (id.equals(item.playlistId) || id.equals(item.channelId))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Rebuilds a snapshotted parent pin ("id|title"). YouTube channel ids always start
     * with "UC" and playlist ids never do — that is how the two are told apart here.
     */
    private static Video hiddenPinVideo(String entry) {
        if (entry == null) {
            return null;
        }

        int sep = entry.indexOf('|');
        String id = sep == -1 ? entry : entry.substring(0, sep);
        String title = sep == -1 ? "" : entry.substring(sep + 1);

        if (id.isEmpty()) {
            return null;
        }

        Video video = new Video();

        if (id.startsWith("UC")) {
            video.channelId = id;
        } else {
            video.playlistId = id;
        }

        video.title = title;

        return video;
    }

    /**
     * Builds the deterministic Video used for pinning/unpinning a kids playlist.
     * Only playlistId + title are set, so hashCode is stable in both directions
     * (same recipe as upstream BaseMenuPresenter.createPinnedPlaylist).
     */
    public static Video kidsPlaylistVideo(String entry) {
        int sep = entry.indexOf('|');
        String id = sep == -1 ? entry : entry.substring(0, sep);
        String title = sep == -1 ? entry : entry.substring(sep + 1);

        Video video = new Video();
        video.playlistId = id;
        video.title = title;
        return video;
    }
}
