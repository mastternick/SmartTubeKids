package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;

import java.util.List;

/**
 * KIDS: manages the sidebar (left menu) while Kids Mode is active.
 *
 * Rules (confirmed with parent):
 *  - Kids Mode ON: hide ALL tabs except the ones checked in Kids settings.
 *    Settings tab is ALWAYS visible (anti-lockout) and placed LAST.
 *  - Selected "kids playlists" are pinned at the TOP of the sidebar.
 *  - No playlists selected: the Playlists tab is force-enabled, so all account
 *    playlists are reachable (decision A); if the account has none, nothing shows
 *    until one is created.
 *  - Search top button follows the "Allow search" switch (default ON, decision C).
 *  - Kids Mode OFF: restore all sections and unpin kids playlists.
 *
 * IMPORTANT (lesson from the v1.2.0 black screen): this class must NEVER be called
 * from a singleton constructor / restoreState path. Only from:
 *  - BrowsePresenter.onViewInitialized (view-ready, singletons constructed)
 *  - Kids settings dialog callbacks (user actions)
 * All methods are idempotent — safe to call repeatedly.
 */
public class KidsSidebarManager {
    private static final String TAG = KidsSidebarManager.class.getSimpleName();

    /** Sidebar tabs controllable from Kids settings (SHORTS is managed by the block-shorts switch). */
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

    /**
     * Idempotent sync for view-init paths. When Kids Mode is OFF it only cleans up
     * leftover pinned playlists — it never touches the user's own section setup.
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
        long mask = kids.getVisibleSections();
        if (mask == -1) {
            mask = 0; // not configured yet -> hide everything (settings stays)
        }

        // 1) Hide/show tabs according to the parent's selection
        for (int sectionId : KIDS_SECTIONS) {
            boolean visible = (mask & (1L << sectionId)) != 0;
            browse.enableSection(sectionId, visible);
        }

        // 2) Kids playlists pinned on top (decision D)
        List<String> playlists = kids.getKidsPlaylists();

        if (playlists.isEmpty()) {
            // Decision A: nothing selected -> show the Playlists tab with ALL account playlists
            browse.enableSection(MediaGroup.TYPE_USER_PLAYLISTS, true);
        } else {
            SidebarService sidebar = SidebarService.instance(context);

            // Pin in reverse, moving each to top -> final order matches selection order
            for (int i = playlists.size() - 1; i >= 0; i--) {
                Video playlist = kidsPlaylistVideo(playlists.get(i));

                if (!browse.isItemPinned(playlist)) {
                    browse.pinItem(playlist);
                }
                sidebar.movePinnedItemToTop(playlist.getId());
            }
        }

        // 3) Settings always visible and LAST (anti-lockout, decision D)
        browse.enableSection(MediaGroup.TYPE_SETTINGS, true);
        SidebarService.instance(context).movePinnedItemToBottom(MediaGroup.TYPE_SETTINGS);

        // 4) Search top button (decision C: switch, default ON)
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
