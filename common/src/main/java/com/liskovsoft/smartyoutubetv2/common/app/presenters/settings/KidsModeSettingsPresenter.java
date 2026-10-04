package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.app.Activity;
import android.content.Context;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup; // KIDS
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem; // KIDS
import com.liskovsoft.sharedutils.helpers.MessageHelpers; // KIDS
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video; // KIDS
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.KidsModeController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.KidsPinGate; // KIDS
import com.liskovsoft.smartyoutubetv2.common.misc.KidsScreenHelper; // KIDS
import com.liskovsoft.smartyoutubetv2.common.misc.KidsSidebarManager; // KIDS
import com.liskovsoft.smartyoutubetv2.common.misc.KidsTimeUpLock; // KIDS
import com.liskovsoft.smartyoutubetv2.common.misc.KioskModeManager; // KIDS
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager; // KIDS
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData; // KIDS
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

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
     * KIDS: quick controls for in-player dialogs: brightness percent + Kids Mode switch.
     * Reused by the player menu provider and by the screen-dimming long-press dialog.
     */
    public void appendQuickControls(AppDialogPresenter dialogPresenter) {
        appendBrightnessCategory(dialogPresenter);
        appendQuickEnableSwitch(dialogPresenter);
    }

    /**
     * KIDS: brightness in percent. Applies live while selecting.
     */
    private void appendBrightnessCategory(AppDialogPresenter dialogPresenter) {
        List<OptionItem> options = new ArrayList<>();

        options.add(UiOptionItem.from(
                getContext().getString(R.string.kids_brightness_auto),
                option -> {
                    if (option.isSelected()) {
                        applyBrightness(KidsScreenHelper.BRIGHTNESS_AUTO);
                    }
                },
                mKidsData.getBrightnessPercent() == KidsScreenHelper.BRIGHTNESS_AUTO));

        for (int percent : new int[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100}) {
            options.add(UiOptionItem.from(percent + "%",
                    option -> {
                        if (option.isSelected()) {
                            applyBrightness(percent);
                        }
                    },
                    mKidsData.getBrightnessPercent() == percent));
        }

        dialogPresenter.appendRadioCategory(getContext().getString(R.string.kids_brightness), options);
    }

    /**
     * KIDS: enable/disable Kids Mode right from the player.
     * Disabling requires the PIN when PIN protection is active.
     */
    private void appendQuickEnableSwitch(AppDialogPresenter dialogPresenter) {
        dialogPresenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.kids_mode_enable),
                option -> {
                    if (!option.isSelected() && mKidsData.isPinEnabled()) {
                        // KIDS v1.2.7: ask for the PIN at most once per parent session
                        // (KidsPinGate). Before, this prompt appeared even right after
                        // the same PIN was entered to open Settings.
                        dialogPresenter.closeDialog();
                        KidsPinGate.runWhenUnlocked(getContext(), () -> {
                            enableKidsMode(false);
                            MessageHelpers.showMessage(getContext(), R.string.kids_mode_disabled);
                        });
                    } else {
                        enableKidsMode(option.isSelected());
                        MessageHelpers.showMessage(getContext(),
                                option.isSelected() ? R.string.kids_mode_enabled : R.string.kids_mode_disabled);
                    }
                },
                mKidsData.isEnabled()));
    }

    private void applyBrightness(int percent) {
        // KIDS: getContext() returns the current Activity when one is available
        android.content.Context context = getContext();

        if (context instanceof Activity) {
            KidsScreenHelper.setBrightness((Activity) context, percent);
        }
    }

    /**
     * Shows the Kids Mode dialog, asking for PIN first if PIN protection is active.
     * KIDS v1.2.7: the prompt goes through KidsPinGate, so a PIN entered anywhere
     * else (e.g. to open the Settings list) counts here too — one unlock per session.
     */
    public void show(Runnable onFinish) {
        KidsPinGate.runWhenUnlocked(getContext(), () -> showDialog(onFinish));
    }

    public void show() {
        show(null);
    }

    private void showDialog(Runnable onFinish) {
        AppDialogPresenter settingsPresenter = AppDialogPresenter.instance(getContext());

        appendEnableSwitch(settingsPresenter);
        appendVisibleSectionsCategory(settingsPresenter); // AC1: tabs visible to the child
        appendAllowSearchSwitch(settingsPresenter);       // AC4: search top button
        appendSelectPlaylistsButton(settingsPresenter);   // kids playlists picker
        appendKioskSwitch(settingsPresenter); // KIDS: block leaving the app
        appendBlockShortsSwitch(settingsPresenter);
        appendBlockRecommendationsSwitch(settingsPresenter);
        appendTimerCategory(settingsPresenter);
        appendCalmExitSwitch(settingsPresenter);
        appendForceStopSwitch(settingsPresenter); // KIDS: hard stop + PIN-locked black screen
        appendExtendTimeCategory(settingsPresenter);
        appendBrightnessCategory(settingsPresenter); // KIDS: brightness also in full settings
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

    /**
     * KIDS AC1: which sidebar tabs the child sees while Kids Mode is on.
     * Default (first open): My videos + Playlists; Settings is always shown separately.
     */
    private void appendVisibleSectionsCategory(AppDialogPresenter settingsPresenter) {
        final int[][] sections = {
                {R.string.header_home, MediaGroup.TYPE_HOME},
                {R.string.header_trending, MediaGroup.TYPE_TRENDING},
                {R.string.header_subscriptions, MediaGroup.TYPE_SUBSCRIPTIONS},
                {R.string.header_history, MediaGroup.TYPE_HISTORY},
                {R.string.my_videos, MediaGroup.TYPE_MY_VIDEOS},
                {R.string.header_playlists, MediaGroup.TYPE_USER_PLAYLISTS}
        };

        List<OptionItem> options = new ArrayList<>();

        for (int[] pair : sections) {
            final int sectionId = pair[1];

            options.add(UiOptionItem.from(
                    getContext().getString(pair[0]),
                    option -> {
                        // MUST run first: writing bits into the raw -1 would show EVERYTHING
                        KidsSidebarManager.ensureConfigured(mKidsData);
                        mKidsData.setSectionVisible(sectionId, option.isSelected());
                        // applyKidsState, not forceApply: checking a box while Kids is OFF
                        // must not rewrite the user's own sidebar
                        KidsSidebarManager.applyKidsState(getContext());
                    },
                    KidsSidebarManager.isSectionCheckedForUI(mKidsData, sectionId)));
        }

        settingsPresenter.appendCheckedCategory(getContext().getString(R.string.kids_visible_sections), options);
    }

    /**
     * KIDS AC4: allow/hide the Search top button while Kids Mode is on.
     */
    private void appendAllowSearchSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.kids_allow_search),
                getContext().getString(R.string.kids_allow_search_desc),
                option -> {
                    mKidsData.setSearchEnabled(option.isSelected());
                    KidsSidebarManager.applyKidsState(getContext());
                },
                mKidsData.isSearchEnabled()));
    }

    /**
     * KIDS: opens the account-playlists picker; the chosen playlists are pinned
     * on top of the kids sidebar. Everything else the parent had pinned is hidden
     * while Kids Mode is on (F4=a) and comes back when it goes off.
     */
    private void appendSelectPlaylistsButton(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.kids_select_playlists),
                getContext().getString(R.string.kids_select_playlists_desc),
                option -> {
                    settingsPresenter.closeDialog();
                    loadAndShowPlaylistPicker();
                }));
    }

    private void loadAndShowPlaylistPicker() {
        // MediaServiceManager owns the subscription (disposes the previous one, main thread)
        MediaServiceManager.instance().loadPlaylists(new Video(), group -> {
            List<OptionItem> options = new ArrayList<>();

            if (group != null && group.getMediaItems() != null) {
                for (MediaItem item : group.getMediaItems()) {
                    final String playlistId = item != null ? item.getPlaylistId() : null;

                    if (playlistId == null || playlistId.isEmpty()) {
                        continue;
                    }

                    final String title = item.getTitle();

                    options.add(UiOptionItem.from(title,
                            option -> {
                                if (option.isSelected()) {
                                    mKidsData.addPlaylist(playlistId, title);
                                } else {
                                    mKidsData.removePlaylist(playlistId);
                                }

                                KidsSidebarManager.applyKidsState(getContext());
                            },
                            mKidsData.isPlaylistSelected(playlistId)));
                }
            }

            if (options.isEmpty()) { // signed out / no playlists = message, not an empty dialog
                MessageHelpers.showMessage(getContext(), R.string.kids_no_playlists_found);
                return;
            }

            AppDialogPresenter picker = AppDialogPresenter.instance(getContext());
            picker.appendCheckedCategory(getContext().getString(R.string.kids_pick_playlists), options);
            picker.showDialog();
        });
    }

    /**
     * KIDS: Kiosk mode switch — the child cannot leave the app (Lock Task / screen pinning).
     *
     * The lock is NOT started from here on purpose: this dialog lives in its own
     * transient task (AppDialogActivity, noHistory) and pinning that task would drop
     * the lock as soon as the dialog closes. Instead we persist the preference and
     * KioskModeManager.applyOnResume (hooked in MotherActivity.onResume) locks the
     * real activity (Browse/Playback) right after the dialog is gone.
     * Turning OFF unlocks immediately (stopLockTask is global for the app).
     */
    private void appendKioskSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.kids_kiosk_mode),
                getContext().getString(R.string.kids_kiosk_mode_desc),
                option -> {
                    mKidsData.setKioskEnabled(option.isSelected());

                    if (option.isSelected()) {
                        MessageHelpers.showMessage(getContext(), KioskModeManager.isDeviceOwner(getContext())
                                ? R.string.kids_kiosk_enabled_full
                                : R.string.kids_kiosk_enabled_pinned);
                    } else {
                        Context context = getContext();

                        if (context instanceof Activity) {
                            KioskModeManager.stopKiosk((Activity) context);
                        }

                        MessageHelpers.showMessage(getContext(), R.string.kids_kiosk_disabled);
                    }
                },
                mKidsData.isKioskEnabled()));
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

    /**
     * KIDS: "Stop immediately when time is up".
     *
     * OFF (default) = calm exit: the running clip finishes, then the screen fades to
     * black and the first key press returns to the playlist.
     * ON = the clip is cut the moment the daily limit expires and the screen STAYS
     * black: every key press asks for the Kids Mode PIN, and only the correct PIN
     * leaves that screen (no return to the playlist, no next video).
     *
     * Requires an enabled PIN — without one nobody could get past the black screen, so
     * the switch offers to set the PIN first (see KidsTimeUpLock.isForceStopActive).
     */
    private void appendForceStopSwitch(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.kids_force_stop),
                getContext().getString(R.string.kids_force_stop_desc),
                option -> {
                    if (!option.isSelected()) {
                        mKidsData.setForceStopOnExpire(false);
                        return;
                    }

                    if (mKidsData.isPinEnabled()) {
                        mKidsData.setForceStopOnExpire(true);
                        return;
                    }

                    // No usable PIN: ask for one, arm protection, then turn the switch on.
                    // Cancel/blank leaves the switch off (it is never enabled without a way out).
                    settingsPresenter.closeDialog();
                    MessageHelpers.showMessage(getContext(), R.string.kids_force_stop_needs_pin);
                    showSetPinDialog(newValue -> {
                        if (newValue != null) {
                            setPinEnabled(true);
                            mKidsData.setForceStopOnExpire(true);
                            KidsPinGate.lock(); // a fresh PIN must be typed again to unlock
                        }
                    });
                },
                mKidsData.isForceStopOnExpire()));
    }

    private void appendExtendTimeCategory(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        for (int minutes : EXTEND_OPTIONS_MIN) {
            options.add(UiOptionItem.from("+" + minutes + " min",
                    option -> {
                        mKidsData.setDailyBonusMs(mKidsData.getDailyBonusMs() + minutes * 60_000L);
                        AppDialogPresenter.instance(getContext()).closeDialog();
                        // KIDS: more time granted — release a time-up lock and let the next
                        // video play (the exact hard stop is re-armed by the controller).
                        refreshAfterExtend();
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
        // AC1/AC2/AC5: apply immediately; OFF = normal display. ALL toggle paths
        // (dialog, player quick-switch, setEnabledFromPlayer) go through here.
        KidsSidebarManager.forceApply(getContext());

        if (!enable) {
            // KIDS: Kids Mode off = no gate. Drop the time-up lock and its overlay,
            // otherwise the parent would stay on a black screen they just disabled.
            KidsTimeUpLock.release(getContext() instanceof Activity ? (Activity) getContext() : null);
        }
    }

    /**
     * KIDS: public entry used by the player menu provider (already PIN-checked there).
     */
    public void setEnabledFromPlayer(boolean enable) {
        enableKidsMode(enable);
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

    /**
     * KIDS: after "+N min" — clear the time-up gate and re-sync the controller so the
     * extended quota takes effect immediately (warnings reset, hard stop re-scheduled).
     */
    private void refreshAfterExtend() {
        KidsTimeUpLock.releaseStateOnly();

        PlaybackPresenter playbackPresenter = PlaybackPresenter.instance(getContext());
        KidsModeController controller = playbackPresenter != null ? playbackPresenter.getController(KidsModeController.class) : null;
        if (controller != null) {
            controller.onSessionExtended();
        }

        if (getContext() instanceof Activity) {
            KidsScreenHelper.hideBlackScreen((Activity) getContext());
            KidsScreenHelper.clearPendingScreenOff();
        }
    }
}
