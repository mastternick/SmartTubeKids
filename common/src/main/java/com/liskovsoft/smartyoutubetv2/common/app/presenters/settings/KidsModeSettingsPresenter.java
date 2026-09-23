package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.KidsModeController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData; // KIDS
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * KIDS: Kids Mode settings dialog. PIN-protected.
 * Pattern follows SponsorBlockSettingsPresenter.
 */
public class KidsModeSettingsPresenter extends BasePresenter<Void> {
    private static final int[] TIMER_OPTIONS_MIN = {0, 15, 30, 45, 60, 90, 120};
    private static final int[] EXTEND_OPTIONS_MIN = {5, 10, 15, 30};

    private final KidsModeData mKidsData;

    public KidsModeSettingsPresenter(Context context) {
        super(context);
        mKidsData = KidsModeData.instance(context);
    }

    public static KidsModeSettingsPresenter instance(Context context) {
        return new KidsModeSettingsPresenter(context);
    }

    /**
     * Shows the Kids Mode dialog, asking for PIN first if one is set.
     */
    public void show(Runnable onFinish) {
        if (mKidsData.hasPin()) {
            SimpleEditDialog.showPassword(
                    getContext(),
                    getContext().getString(R.string.kids_enter_pin),
                    null,
                    newValue -> {
                        if (Utils.passwordMatch(mKidsData.getPin(), newValue)) {
                            showDialog(onFinish);
                            return true;
                        }
                        return false;
                    },
                    onFinish);
        } else {
            showDialog(onFinish);
        }
    }

    public void show() {
        show(null);
    }

    private void showDialog(Runnable onFinish) {
        AppDialogPresenter settingsPresenter = AppDialogPresenter.instance(getContext());

        appendEnableSwitch(settingsPresenter);
        appendBlockShortsSwitch(settingsPresenter);
        appendBlockRecommendationsSwitch(settingsPresenter);
        appendTimerCategory(settingsPresenter);
        appendCalmExitSwitch(settingsPresenter);
        appendExtendTimeCategory(settingsPresenter);
        appendPinButton(settingsPresenter);

        settingsPresenter.showDialog(getContext().getString(R.string.kids_mode), onFinish);
    }

    private void appendEnableSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.kids_mode_enable),
                getContext().getString(R.string.kids_mode_desc),
                option -> {
                    if (option.isSelected() && !mKidsData.hasPin()) {
                        // Force PIN creation before enabling
                        settingsPresenter.closeDialog();
                        showSetPinDialog(() -> {
                            enableKidsMode(true);
                        });
                    } else {
                        enableKidsMode(option.isSelected());
                    }
                },
                mKidsData.isEnabled()));
    }

    private void appendBlockShortsSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.kids_block_shorts),
                option -> {
                    mKidsData.setBlockShorts(option.isSelected());
                    refreshRestrictions();
                },
                mKidsData.isBlockShorts()));
    }

    private void appendBlockRecommendationsSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.kids_block_recommendations),
                getContext().getString(R.string.kids_block_recommendations_desc),
                option -> mKidsData.setBlockRecommendations(option.isSelected()),
                mKidsData.isBlockRecommendations()));
    }

    private void appendTimerCategory(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        for (int minutes : TIMER_OPTIONS_MIN) {
            options.add(UiOptionItem.from(
                    minutes == 0 ? getContext().getString(R.string.kids_timer_off) : minutes + " min",
                    option -> mKidsData.setTimerMinutes(minutes),
                    mKidsData.getTimerMinutes() == minutes));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.kids_timer), options);
    }

    private void appendCalmExitSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.kids_calm_exit),
                getContext().getString(R.string.kids_calm_exit_desc),
                option -> mKidsData.setCalmExit(option.isSelected()),
                mKidsData.isCalmExit()));
    }

    private void appendExtendTimeCategory(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        for (int minutes : EXTEND_OPTIONS_MIN) {
            options.add(UiOptionItem.from("+" + minutes + " min",
                    option -> {
                        mKidsData.setDailyBonusMs(mKidsData.getDailyBonusMs() + minutes * 60_000L);
                        AppDialogPresenter.instance(getContext()).closeDialog();
                    }));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.kids_extend_time), options);
    }

    private void appendPinButton(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.kids_pin),
                option -> {
                    settingsPresenter.closeDialog();
                    showSetPinDialog(null);
                }));
    }

    private void showSetPinDialog(Runnable onSuccess) {
        SimpleEditDialog.showPassword(
                getContext(),
                getContext().getString(R.string.kids_set_pin),
                null,
                newValue -> {
                    if (newValue != null && !newValue.isEmpty()) {
                        String oldPin = mKidsData.getPin();
                        mKidsData.setPin(newValue);

                        // Keep the settings password in sync with the Kids PIN
                        GeneralData generalData = GeneralData.instance(getContext());
                        if (mKidsData.isEnabled()) {
                            if (oldPin != null && oldPin.equals(generalData.getSettingsPassword())) {
                                generalData.setSettingsPassword(newValue);
                            }
                        }

                        if (onSuccess != null) {
                            onSuccess.run();
                        }
                        return true;
                    }
                    return false;
                });
    }

    /**
     * KIDS: enable/disable Kids Mode. When enabled, the PIN also protects
     * the whole Settings section (existing GeneralData settings password mechanism),
     * so the child can't change any other setting to bypass Kids Mode.
     */
    private void enableKidsMode(boolean enable) {
        mKidsData.setEnabled(enable);

        GeneralData generalData = GeneralData.instance(getContext());

        if (enable && mKidsData.hasPin()) {
            generalData.setSettingsPassword(mKidsData.getPin());
        } else if (!enable) {
            // Only clear if it's our PIN (don't touch an unrelated settings password)
            if (mKidsData.hasPin() && mKidsData.getPin().equals(generalData.getSettingsPassword())) {
                generalData.setSettingsPassword(null);
            }
        }

        refreshRestrictions();
    }

    /**
     * KIDS: re-apply global content filters after any Kids Mode setting change.
     */
    private void refreshRestrictions() {
        PlaybackPresenter playbackPresenter = PlaybackPresenter.instance(getContext());
        KidsModeController controller = playbackPresenter != null ? playbackPresenter.getController(KidsModeController.class) : null;
        if (controller != null) {
            controller.applyRestrictions();
        }
    }
}
