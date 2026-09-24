package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.channelgroup.RemoveGroupMenuProvider;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.channelgroup.RenameGroupMenuProvider;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.channelgroup.ChannelGroupMenuProvider;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.KidsModeMenuProvider; // KIDS
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData; // KIDS
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData; // KIDS
import com.liskovsoft.smartyoutubetv2.common.utils.Utils; // KIDS

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ContextMenuManager {
    private final ArrayList<ContextMenuProvider> mProviders;
    private static volatile boolean sKidsItemRegistered; // KIDS

    public ContextMenuManager(Context context) {
        mProviders = new ArrayList<>();
        // NOTE: don't change idx after release
        mProviders.add(new ChannelGroupMenuProvider(context, 0));
        mProviders.add(new RemoveGroupMenuProvider(context, 1));
        mProviders.add(new RenameGroupMenuProvider(context, 2));
        // KIDS: Kids Mode item in the player menu (idx 3, never reuse for something else)
        final KidsModeMenuProvider kidsProvider = new KidsModeMenuProvider(context, 3);
        mProviders.add(kidsProvider);

        // KIDS v1.2.5 CRITICAL FIX: DO NOT call MainUIData.instance() here!
        // This constructor runs INSIDE MainUIData.restoreState() (line ~473 of MainUIData),
        // i.e. while the MainUIData singleton is still being constructed (sInstance not yet
        // assigned). Calling MainUIData.instance() here re-enters the constructor ->
        // infinite recursion -> StackOverflowError -> app dies at startup -> BLACK SCREEN.
        // (Root cause of the v1.2.0-v1.2.4 black screen.)
        //
        // Instead: post the one-time registration to the main handler, so it runs AFTER
        // the MainUIData constructor has completed and sInstance is assigned.
        if (!sKidsItemRegistered) {
            sKidsItemRegistered = true; // claim immediately (multi-instance safety)
            final long providerId = kidsProvider.getId();
            final Context appContext = context.getApplicationContext();
            Utils.post(() -> {
                try {
                    KidsModeData kidsData = KidsModeData.instance(appContext);
                    if (!kidsData.isMenuProviderRegistered()) {
                        MainUIData.instance(appContext).setMenuItemEnabled(providerId);
                        kidsData.setMenuProviderRegistered(true);
                    }
                } catch (Throwable ignored) {
                    // never let registration break the app
                }
            });
        }
    }

    public List<ContextMenuProvider> getProviders() {
        return Collections.unmodifiableList(mProviders);
    }
}
