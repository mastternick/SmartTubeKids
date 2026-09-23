package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers;

import android.content.Context;

import androidx.annotation.NonNull;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter.VideoMenuCallback;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.KidsModeSettingsPresenter;

/**
 * KIDS: "Kids Mode" item inside the video context menu (browse / suggestions long-OK).
 *
 * Provides quick controls:
 *  - Screen brightness (%) with live preview
 *  - Enable/disable Kids Mode (disabling asks for the PIN when one is set)
 *  - Link to the full Kids Mode settings (PIN-protected)
 */
public class KidsModeMenuProvider extends ContextMenuProvider {
    public KidsModeMenuProvider(@NonNull Context context, int idx) {
        super(context, idx);
    }

    @Override
    public int getTitleResId() {
        return R.string.kids_mode;
    }

    @Override
    public void onClicked(Video item, VideoMenuCallback callback) {
        AppDialogPresenter dialogPresenter = AppDialogPresenter.instance(getContext());
        KidsModeSettingsPresenter presenter = KidsModeSettingsPresenter.instance(getContext());

        // Brightness % + Kids Mode enable/disable (PIN asked when disabling)
        presenter.appendQuickControls(dialogPresenter);

        // Full settings (PIN-protected inside)
        dialogPresenter.appendSingleButton(
                com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem.from(
                        getContext().getString(R.string.kids_mode_settings),
                        option -> {
                            dialogPresenter.closeDialog();
                            presenter.show();
                        }));

        dialogPresenter.showDialog(getContext().getString(R.string.kids_mode));
    }

    @Override
    public boolean isEnabled(Video item) {
        // Always available (the parent may need it at any time)
        return true;
    }

    @Override
    public int getMenuType() {
        return MENU_TYPE_VIDEO;
    }
}
