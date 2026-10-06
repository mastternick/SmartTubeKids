package com.liskovsoft.smartyoutubetv2.common.misc;

/**
 * KIDS: mash-gate for the time-up black screen ({@link KidsTimeUpLock}).
 *
 * The force-stop screen used to answer EVERY key press with the PIN dialog, which
 * hands the child the PIN prompt for free: one accidental BACK shows the very thing
 * that is supposed to keep them out. Since this gate the screen stays black and
 * completely silent until an escape key has been pressed {@link #REQUIRED_PRESSES}
 * times inside {@link #WINDOW_MS}; only then does the PIN dialog appear.
 *
 * Counted presses are escape attempts only (BACK / HOME / RECENTS / ESCAPE —
 * {@link #isEscapeKey}). Navigation keys are still swallowed, they just never reveal
 * the dialog: a child mashing arrows or OK gets nothing. The mic / assistant keys are
 * NOT counted either — mashing the microphone is the most likely thing a child does in
 * front of a black screen, and it must not surface the PIN prompt (the user asked for
 * "nothing appears" until an exit key has been pressed).
 *
 * Both key paths feed the SAME counter, because with the kiosk key guard enabled HOME
 * and RECENTS are swallowed device-wide and never reach an activity:
 * {@link MotherActivity#dispatchKeyEvent} (everything the app sees) and
 * {@link KioskKeyGuardService#onKeyEvent} (guard-swallowed escape keys).
 *
 * Pure Java on purpose: no Android types, so the counting rule is verifiable without a
 * device or a build. Callers must drop long-press auto-repeat themselves
 * ({@code KeyEvent.getRepeatCount() == 0}) — one held key is one press, not ten.
 */
public final class KidsTimeUpKeyGate {
    /** Distinct escape presses needed before the PIN dialog is allowed to appear. */
    public static final int REQUIRED_PRESSES = 10;

    /** Press 1 and press {@link #REQUIRED_PRESSES} must be at most this far apart. */
    public static final long WINDOW_MS = 10_000L;

    // Literals on purpose: this file must stay free of Android imports so the counting
    // rule is testable with plain javac/java (no SDK, no gradle — see BUILD-POLICY.md).
    private static final int KEYCODE_HOME = 3;           // KeyEvent.KEYCODE_HOME
    private static final int KEYCODE_BACK = 4;           // KeyEvent.KEYCODE_BACK
    private static final int KEYCODE_ESCAPE = 111;       // KeyEvent.KEYCODE_ESCAPE
    private static final int KEYCODE_APP_SWITCH = 187;   // KeyEvent.KEYCODE_APP_SWITCH (RECENTS)

    private static int sCount;
    private static long sFirstPressMs;
    // Threshold reached. Sticky: it survives a caller that could not open the dialog
    // (no resumed activity, dialog throttled) so the escape attempt is never lost and
    // the next press does show the PIN. Cleared by resetSequence().
    private static boolean sArmed;

    private KidsTimeUpKeyGate() {
    }

    /**
     * Register one distinct escape press (BACK / HOME / RECENTS / ESCAPE / assist / mic).
     *
     * @return true when the mash threshold is now reached and the PIN dialog may be shown.
     */
    public static boolean onEscapeKeyPressed() {
        return onEscapeKeyPressed(System.currentTimeMillis());
    }

    /** Same as {@link #onEscapeKeyPressed()} with an explicit clock (test seam). */
    static boolean onEscapeKeyPressed(long nowMs) {
        if (sArmed) {
            return true; // still waiting for a caller that can show the dialog
        }

        // First press of a sequence, or the previous one went stale: start over. The
        // window is measured from the FIRST press, so "10 presses in 10 s" holds even
        // for a slow, steady mash.
        if (sCount == 0 || nowMs - sFirstPressMs > WINDOW_MS) {
            sCount = 1;
            sFirstPressMs = nowMs;
            return false;
        }

        sCount++;

        if (sCount >= REQUIRED_PRESSES) {
            sArmed = true;
            sCount = 0;
            sFirstPressMs = 0;
            return true;
        }

        return false;
    }

    /**
     * @return true when the threshold has been reached and no PIN dialog consumed it yet.
     */
    public static boolean isArmed() {
        return sArmed;
    }

    /**
     * Drop the armed state and any partial sequence — call once the PIN dialog is up
     * (a cancelled dialog must require a fresh 10 presses) and whenever the lock goes
     * away ({@link KidsTimeUpLock#release}, {@link KidsTimeUpLock#releaseStateOnly}).
     */
    public static void resetSequence() {
        sArmed = false;
        sCount = 0;
        sFirstPressMs = 0;
    }

    /**
     * Escape keys: the ones a child presses to get OUT of the app. Everything else on
     * the time-up screen is navigation noise and must not reveal the PIN.
     *
     * Deliberately ABSENT:
     *  - POWER / VOLUME / MEDIA / DPAD / OK: not escape attempts;
     *  - SEARCH (84) and EXPLORER (64, browser key): the kiosk key guard swallows them
     *    too, but they do not take the child out of the app;
     *  - ASSIST (219) and VOICE_ASSIST (231, the mic button): not exit keys, and mashing
     *    the microphone is the most likely toddler reaction to a black screen — counting
     *    it would pop the PIN prompt with exactly the presses the gate is meant to ignore;
     *  - MENU: opens in-app menus.
     */
    public static boolean isEscapeKey(int keyCode) {
        switch (keyCode) {
            case KEYCODE_BACK:
            case KEYCODE_HOME:
            case KEYCODE_APP_SWITCH: // RECENTS
            case KEYCODE_ESCAPE:
                return true;
            default:
                return false;
        }
    }
}
