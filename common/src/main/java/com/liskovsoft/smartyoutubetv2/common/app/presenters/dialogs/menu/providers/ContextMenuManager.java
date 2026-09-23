package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.channelgroup.RemoveGroupMenuProvider;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.channelgroup.RenameGroupMenuProvider;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.channelgroup.ChannelGroupMenuProvider;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.KidsModeMenuProvider; // KIDS
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData; // KIDS
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData; // KIDS

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ContextMenuManager {
    private final ArrayList<ContextMenuProvider> mProviders;

    public ContextMenuManager(Context context) {
        mProviders = new ArrayList<>();
        // NOTE: don't change idx after release
        mProviders.add(new ChannelGroupMenuProvider(context, 0));
        mProviders.add(new RemoveGroupMenuProvider(context, 1));
        mProviders.add(new RenameGroupMenuProvider(context, 2));
        // KIDS: Kids Mode item in the player menu (idx 3, never reuse for something else)
        KidsModeMenuProvider kidsProvider = new KidsModeMenuProvider(context, 3);
        mProviders.add(kidsProvider);

        // KIDS: enable our menu item once (users can still hide it via Main UI settings)
        KidsModeData kidsData = KidsModeData.instance(context);
        if (!kidsData.isMenuProviderRegistered()) {
            MainUIData.instance(context).setMenuItemEnabled(kidsProvider.getId());
            kidsData.setMenuProviderRegistered(true);
        }
    }

    public List<ContextMenuProvider> getProviders() {
        return Collections.unmodifiableList(mProviders);
    }
}
