package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.KidsModeData;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * KIDS v1.2.7: one PIN entry per parent session (bug fix).
 *
 * Before, every protected screen asked for the PIN separately: the Settings list
 * (global settings password synced with the Kids PIN), then the Kids Mode dialog
 * (KidsModeSettingsPresenter.show), then the quick disable switch. A parent who
 * had just typed the PIN was asked again immediately ("enter it once, be asked
 * again"). Now the first correct PIN unlocks the whole parent session: all gates
 * share this token.
 *
 * The unlock is in-memory only (a process restart locks everything again) and
 * expires after PARENT_SESSION_MS of no protected action, so a parent leaving
 * the settings open doesn't keep the door unlocked forever.
 */
public class KidsPinGate {
    // A parent finishing a settings session (kiosk toggle, PIN change, timers)
    // rarely needs more than a few minutes. 10 minutes stays invisible in one
    // continuous session but re-locks if the remote is put down.
    private static final long PARENT_SESSION_MS = 10 * 60_000L;

    private static long sUnlockedUntilMs;

    private KidsPinGate() {
    }

    /**
     * @return true when the PIN protects the parent areas right now.
     */
    public static boolean isProtected(Context context) {
        return KidsModeData.instance(context).isPinEnabled();
    }

    /**
     * @return true when the parent already entered the PIN in this session.
     */
    public static boolean isUnlocked() {
        return System.currentTimeMillis() < sUnlockedUntilMs;
    }

    /**
     * KIDS: the single entry point for all PIN-protected actions.
     * Runs the action immediately when there's no PIN protection or the session
     * is already unlocked; otherwise asks for the PIN exactly once and, after a
     * correct entry, never asks again during this session.
     */
    public static void runWhenUnlocked(Context context, Runnable action) {
        KidsModeData data = KidsModeData.instance(context);

        if (!data.isPinEnabled()) {
            action.run();
            return;
        }

        if (isUnlocked()) {
            // Refresh the window so a long active session doesn't re-ask mid-work.
            sUnlockedUntilMs = System.currentTimeMillis() + PARENT_SESSION_MS;
            action.run();
            return;
        }

        SimpleEditDialog.showPassword(
                context,
                context.getString(R.string.kids_enter_pin),
                null,
                newValue -> {
                    if (Utils.passwordMatch(data.getPin(), newValue)) {
                        sUnlockedUntilMs = System.currentTimeMillis() + PARENT_SESSION_MS;
                        action.run();
                        return true;
                    }
                    return false;
                });
    }

    /**
     * KIDS: mark the parent session unlocked after a PIN was verified OUTSIDE
     * {@link #runWhenUnlocked} (the time-up black screen shows its own dialog so it
     * can also react to cancel). Same window as a normal unlock.
     */
    public static void unlock() {
        sUnlockedUntilMs = System.currentTimeMillis() + PARENT_SESSION_MS;
    }

    /**
     * KIDS: forget the session (used when the PIN is (re)armed so an old
     * unlock can't survive a PIN change).
     */
    public static void lock() {
        sUnlockedUntilMs = 0;
    }
}
