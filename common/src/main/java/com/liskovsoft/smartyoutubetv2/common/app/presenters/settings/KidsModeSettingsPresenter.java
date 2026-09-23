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
    /** KIDS: callback for PIN changes (null = PIN removed). Avoids java.util.function (API 24+). */
    private interface PinCallback {
        void onPinChanged(String newPin);
    }

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
     * Shows the Kids Mode dialog, asking for PIN first if PIN protection is active.
     */
    public void show(Runnable onFinish) {
        if (mKidsData.isPinEnabled()) {
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
        appendPinCategory(settingsPresenter);

        settingsPresenter.showDialog(getContext().getString(R.string.kids_mode), onFinish);
    }

    private void appendEnableSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.kids_mode_enable),
                getContext().getString(R.string.kids_mode_desc),
                option -> enableKidsMode(option.isSelected()),
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

    /**
     * KIDS: PIN category — independent enable/disable toggle + set/change PIN.
     *
     * Behavior:
     *  - Toggle ON: asks to set a PIN if none stored yet, then activates protection.
     *  - Toggle OFF: protection removed IMMEDIATELY (no dialogs), stored PIN is kept
     *    so re-enabling later doesn't require typing it again.
     *  - "Set/change PIN": blank input = remove stored PIN entirely (no password).
     */
    private void appendPinCategory(AppDialogPresenter settingsPresenter) {
        // PIN enable/disable switch (protection state)
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.kids_pin_enable),
                getContext().getString(R.string.kids_pin_enable_desc),
                option -> {
                    if (option.isSelected()) {
                        if (mKidsData.hasPin()) {
                            setPinEnabled(true);
                        } else {
                            // No PIN stored yet — must set one to enable protection
                            settingsPresenter.closeDialog();
                            showSetPinDialog(newValue -> {
                                if (newValue != null) {
                                    setPinEnabled(true);
                                }
                            });
                        }
                    } else {
                        // Disable immediately, no confirmation
                        setPinEnabled(false);
                    }
                },
                mKidsData.isPinEnabled()));

        // Set/change/remove PIN button
        settingsPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.kids_pin_set),
                getContext().getString(R.string.kids_pin_set_desc),
                option -> {
                    settingsPresenter.closeDialog();
                    showSetPinDialog(null);
                }));
    }

    /**
     * KIDS: activate/deactivate PIN protection and sync the global settings password.
     */
    private void setPinEnabled(boolean enabled) {
        mKidsData.setPinEnabled(enabled);
        syncSettingsPassword();
    }

    /**
     * KIDS: prompt for a new PIN. Blank input clears the stored PIN
     * (and switches protection off, since there's nothing to check against).
     * The callback receives the new PIN (null when cleared).
     */
    private void showSetPinDialog(PinCallback onDone) {
        SimpleEditDialog.showPassword(
                getContext(),
                getContext().getString(R.string.kids_set_pin),
                getContext().getString(R.string.kids_set_pin_blank_hint),
                null, // KIDS: never prefill the stored PIN (don't expose it on screen)
                newValue -> {
                    boolean isBlank = newValue == null || newValue.trim().isEmpty();

                    if (isBlank) {
                        // Blank = remove PIN entirely -> protection off
                        mKidsData.setPin(null);
                        mKidsData.setPinEnabled(false);
                    } else {
                        mKidsData.setPin(newValue.trim());
                    }

                    syncSettingsPassword();

                    if (onDone != null) {
                        onDone.onPinChanged(isBlank ? null : newValue.trim());
                    }
                    return true;
                });
    }

    /**
     * KIDS: enable/disable Kids Mode. Restrictions are applied/removed immediately.
     */
    private void enableKidsMode(boolean enable) {
        mKidsData.setEnabled(enable);
        syncSettingsPassword();
        refreshRestrictions();
    }

    /**
     * KIDS: keep the global Settings password in sync with Kids PIN state.
     * PIN protection ON -> Settings locked with the PIN (child can't change anything).
     * PIN protection OFF -> Settings unlocked (only if WE had set the password;
     * an unrelated password set by the user via General settings is never touched).
     */
    private void syncSettingsPassword() {
        GeneralData generalData = GeneralData.instance(getContext());
        String currentSettingsPassword = generalData.getSettingsPassword();
        String kidsPin = mKidsData.getPin();

        if (mKidsData.isPinEnabled()) {
            // Lock settings with our PIN (only if free or already ours)
            if (currentSettingsPassword == null || currentSettingsPassword.equals(kidsPin)) {
                generalData.setSettingsPassword(kidsPin);
            }
        } else {
            // Unlock immediately, but only if the password is ours
            if (kidsPin != null && kidsPin.equals(currentSettingsPassword)) {
                generalData.setSettingsPassword(null);
            }
        }
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
