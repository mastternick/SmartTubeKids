# Kiosk mode (SmartTubeKids)

Kiosk mode blocks the child from leaving the app. Since **v1.6 the real lock on TV
boxes is the kiosk key guard** (`KioskKeyGuardService`, an Accessibility input
filter enabled once by the parent): HOME, RECENTS and the microphone/assistant
keys are swallowed system-wide, so neither the launcher, nor the assistant, nor
any app summoned from them can be opened. System screen pinning (v1.2.8) stays as
a second layer where the device supports it — most Android TV 14 boxes keep screen
pinning disabled, which is exactly why the guard exists (without it the soft lock
degrades to "only BACK is blocked"). Since v1.4 a **screen guardian** (foreground
service) additionally covers the launcher and pulls SmartTubeKids back on screen
every second whenever the app ever loses it. The BACK key only exits after the
correct PIN, and with the Device Owner setup the app can be relaunched
automatically after a TV reboot. The lock is turned off from
**Settings → Kids Mode → (PIN) → "Kiosk mode (block leaving the app)"**.

There are two levels of protection:

| | Full lock (recommended) | Soft lock (no setup) |
|---|---|---|
| Requires | One-time ADB command (Device Owner) | Nothing |
| Confirmation prompts | None, silent lock | None |
| HOME / RECENTS / search key | Blocked by Lock Task; the key guard kills them too (enable it in **either** mode) | Blocked **only when the kiosk key guard is enabled** (Option B step 1); until then the current screen is pinned where the system allows it and the v1.4 guardian covers any window that opens (unpin cycle, BACK+HOME combo) — the launcher is covered and the app returns ~1 s later |
| Assistant mic button (`ASSIST` keys) | Lock Task refuses the assistant's activity start | Needs the extra step: the assist keys are decided before any input filter, so disable the assistant app over ADB (Option B step 1 note). Guard + guardian keep HOME dead and pull the app back meanwhile |
| BACK key | PIN-protected exit: asks for the PIN, correct PIN exits the app | PIN-protected exit: asks for the PIN, correct PIN exits the app (without a PIN set: never exits) |
| Other apps reachable? | No | Only under the guardian cover for ~1–2 s, with no key input reaching them |
| Playback / Settings inside the app | Fully working | Fully working (v1.2.8 auto-unpins before each navigation) |
| After TV reboot | App launches directly, re-locks itself | Normal launcher start; app returns on its own once opened |
| Child can escape? | No (only the PIN switch / exit with PIN) | No sustained escape: any loss of screen is covered and undone by the guardian (it releases itself after ~45 s only if the app itself cannot start — broken-install safety valve) |

> **Screen pinning vs navigation (the v1.2.6 → v1.2.8 story).** This app runs every
> screen in its own task (`launchMode=singleInstance`). System screen pinning
> confines one task, so in v1.2.6 pinning the browse screen made it impossible to
> start playback or open Settings — exactly the bug users reported. v1.2.7 reacted
> by dropping pinning without Device Owner (HOME was no longer blocked). v1.2.8
> restores pinning — it's what blocks HOME — but releases the pin **right before
> every internal launch** (`KioskModeManager.releaseForNavigation`, called from
> `ViewManager.safeStartActivityInt` and `MotherActivity.startActivity`) and
> re-pins the new screen on its resume. Device Owner full lock allowlists the whole
> package and never needs this cycle.

> **Exit with PIN (v1.2.8, durable since v1.7).** On the app's root screen BACK
> opens the PIN dialog (KidsPinGate — one PIN entry per parent session). The
> correct PIN drops the lock and exits the app, and the app **stays out**: the
> release is *persisted* (`KidsModeData`, KIDS v1.7). That matters because the
> exit kills the process, and the system then rebinds the key guard in a fresh
> process — an in-memory grace window was already gone by then, so
> `onServiceConnected → bringAppBack` used to pull the app back on screen right
> after a correct PIN. With Device Owner the persistent HOME + lock-task
> allowlist are cleared on exit too (re-applied on the next open). Opening the
> app again clears the release (`applyOnResume`) and locks it immediately.
> A TV **reboot** does not clear the release by default — the app stays out until it
> is opened again; enable **Re-lock kiosk after a reboot** in Kids Mode if a reboot
> should re-arm the lock instead (the guardian / Device Owner then bring the app back
> on screen by itself). Waking from standby is not a reboot and never re-arms.
> Without a PIN set, BACK stays blocked in both modes.

Enable the Kids **PIN** before enabling kiosk — otherwise the child can open
Kids Mode settings and turn kiosk off, and there's no PIN exit either.

---

## Option A — Full lock via ADB (Device Owner)

Requirements: TV and computer on the same network, ADB debugging enabled on the TV
(Settings → Device Preferences → About → tap Build 7 times, then Developer options →
Network debugging / USB debugging).

1. Connect to the TV:
   ```bash
   adb connect <TV_IP>:5555
   ```
2. Install the APK **fresh** (the device-owner command fails if the app was already
   opened or old data exists):
   ```bash
   adb uninstall app.smarttubekids        # only if already installed
   adb install SmartTubeKids-<version>.apk
   ```
3. **Before opening the app**, set it as device owner:
   ```bash
   adb shell dpm set-device-owner app.smarttubekids/com.liskovsoft.smartyoutubetv2.common.misc.KioskDeviceAdminReceiver
   ```
   Expected output: `Success: Device owner set to package app.smarttubekids`
4. Open the app → Settings → Kids Mode → enable **PIN**, then enable **Kiosk mode**.
   The lock is applied silently as soon as the settings dialog closes.

Notes / troubleshooting:
- Other build flavors use different ids: `app.smarttubekids.stable`,
  `app.smarttubekids.fdroid` — adjust the command accordingly.
- `Not allowed to set the device owner` → the app was already launched or the device
  has accounts/users set up. Uninstall, reinstall, run the command before opening.
- To remove device owner later (parent):
  ```bash
  adb shell dpm remove-active-admin app.smarttubekids/com.liskovsoft.smartyoutubetv2.common.misc.KioskDeviceAdminReceiver
  ```
  (or simply turn the kiosk switch off in the app; a factory reset also clears it).

## Option B — Soft lock (no ADB)

1. **Enable the key guard — this is the part that blocks HOME / RECENTS and the
   search/mic key device-wide.** (AOSP handles those three in
   `interceptKeyBeforeDispatching`, i.e. *after* the accessibility input filter,
   so consuming them here really kills them.) Turn on **Kiosk mode** in Kids Mode
   settings: right after the switch the app shows the explanation and opens the
   system **Accessibility** screen. Tick **"SmartTube Kids kiosk key guard"**
   there (on Android 13+ side-loaded apps may need App info → ⋮ → *Allow
   restricted settings* first), then press BACK — the app returns by itself (a
   5-minute grace window keeps the guardian quiet during setup). The guard is
   inert whenever kiosk is OFF, so it can stay enabled forever. ADB alternative
   (on Android 13+: grant the restricted-settings app-op first, then add the key
   guard without overwriting other enabled accessibility services):
   ```bash
   PKG="app.smarttubekids" # or "app.smarttubekids.stable" / "app.smarttubekids.fdroid"
   adb shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow
   adb shell "cur=\$(settings get secure enabled_accessibility_services) && target='$PKG/com.liskovsoft.smartyoutubetv2.common.misc.KioskKeyGuardService' && case \":\$cur:\" in *:\$target:*) val=\"\$cur\";; '::'|':null:') val=\"\$target\";; *) val=\"\$cur:\$target\";; esac && settings put secure enabled_accessibility_services \"\$val\" && settings put secure accessibility_enabled 1"
   ```
   (other flavors: `app.smarttubekids.stable` / `app.smarttubekids.fdroid`.
   Alternatively, read `settings get secure enabled_accessibility_services` first:
   if empty/null, set the target directly; if other services already exist, append
   with a colon `:app.smarttubekids/...` instead of overwriting.)

   > **The assistant button itself is a separate case (platform limit).**
   > `KEYCODE_ASSIST` / `KEYCODE_VOICE_ASSIST` are handled in
   > `PhoneWindowManager.interceptKeyBeforeQueueing`, which runs *before* any
   > input filter — the launch is already posted when the guard sees the event, so
   > no app-side/Accessibility trick can stop it. Remotes whose mic button sends
   > `KEYCODE_SEARCH` (very common on Android TV) are fully covered by the guard.
   > To kill the assistant completely, disable the assistant app once over ADB
   > (no Device Owner needed — find it with `adb shell pm list packages | grep -i assist`,
   > it is usually `com.google.android.katniss` on ATV):
   > ```bash
   > adb shell pm disable-user --user 0 com.google.android.katniss
   > ```
   > (re-enable with `pm enable`). With Device Owner (Option A) Lock Task refuses
   > the assistant's activity start, which closes the same hole without ADB.
   > Either way the v1.4 guardian already limits a pop-up to ~1 s and HOME stays
   > dead, so the child cannot stay in another app.
2. The rest is automatic: the current screen is pinned by the system where it
   supports pinning, clips / Settings / dialogs keep working (the pin is released
   moments before every internal navigation and re-applied on the new screen),
   and BACK asks for the PIN — only the correct PIN exits the app. If any window
   still opens, the **v1.4 screen guardian** takes over: a foreground service
   relaunches the app every second and, while it isn't on top yet, a fullscreen
   cover hides and swallows input on whatever is behind (the launcher, another
   app).

Option A still wins on robustness: device-owner Lock Task needs no accessibility
permission, blocks notifications too, survives reboots and refuses the assistant
start. But with the key guard on there is no sustained escape in either mode:
HOME, RECENTS and the search key are dead device-wide while kiosk is on. Set the
Kids **PIN** too, otherwise BACK has no exit path at all. Use Option A whenever
possible.

> Note: while the guard is on, a remote whose search/mic button sends
> `KEYCODE_SEARCH` no longer opens search — that is the point, it is the
> assistant's entry point. Whitelisted search stays reachable from the app's own
> top panel when **Allow search** is enabled in Kids Mode.

> **Guardian details (v1.4, `KioskWatchdogService`).** Armed from
> `MotherActivity.onStop` (`KioskModeManager.scheduleReentry`), idles/stops when
> the app is on screen again, kiosk is switched off, playback is in PIP, the
> screen is off, or the parent left with the PIN. The PIN-exit check uses the
> *persisted* release (KIDS v1.7): the exit kills the process, so on the sticky
> restart that follows, an in-memory grace window would be gone and the loop
> would relaunch the app the parent just left. It needs the
> *Draw over other apps* permission, which Android TV grants automatically at
> install (`SYSTEM_ALERT_WINDOW` is a normal permission on TV); if a device
> refuses it, the climb-back loop still runs without the visual cover. As a
> broken-install safety valve it stops covering after ~45 s of failed relaunches,
> so a TV can never be left unusable by the guardian itself.
>
> **Android 14 (targetSdk 34) needs the foreground-service type.** The shipped
> flavors override `targetSdkVersion` to 34 (`smarttubetv/build.gradle`: stbeta /
> ststable / stfdroid → `project.properties.compileSdkVersion`; `constants.gradle`'s
> 27 is *not* what ships — check with `aapt2 dump badging`). Since v1.7.2 the service
> declares `android:foregroundServiceType="specialUse"` + the
> `FOREGROUND_SERVICE_SPECIAL_USE` permission and the matching subtype property;
> without them `startForeground()` throws `MissingForegroundServiceTypeException`,
> the catch in `onCreate` stops the service, and this whole layer is dead on Android
> TV 14 while looking perfectly fine in the code.

### Verifying the key guard on the device (if HOME still leaks)

The guard filters keys **only while kiosk mode is ON** and outside an approved
exit — with kiosk OFF every key passes through by design. If HOME still reaches
the launcher, check the three failure modes in order:

> **First, the switch that ticks itself off (Android 13+).** If the accessibility
> toggle checks itself and then reverts, platform restricted settings may be
> preventing the sideloaded service from activating. The app cannot automatically
> reactivate the service on its own — user confirmation or ADB permissions are
> required. Manual fix: **App info → ⋮ → *Allow restricted settings*** (⋮ is the
> overflow menu, top-right of the App info screen), then tick the guard again.
> If that menu is absent on your build, grant the restriction app-op over ADB
> and add the service without replacing other accessibility services:
> ```bash
> PKG="app.smarttubekids" # or "app.smarttubekids.stable" / "app.smarttubekids.fdroid"
> # check current status (typically "ACCESS_RESTRICTED_SETTINGS: deny"):
> adb shell appops get "$PKG" | grep -i restrict
> # grant the app-op (name is CASE-SENSITIVE):
> adb shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow
> # add the key guard preserving existing services:
> adb shell "cur=\$(settings get secure enabled_accessibility_services) && target='$PKG/com.liskovsoft.smartyoutubetv2.common.misc.KioskKeyGuardService' && case \":\$cur:\" in *:\$target:*) val=\"\$cur\";; '::'|':null:') val=\"\$target\";; *) val=\"\$cur:\$target\";; esac && settings put secure enabled_accessibility_services \"\$val\" && settings put secure accessibility_enabled 1"
> ```

1. **Not enabled** — the service must be listed by the system:
   ```bash
   adb shell settings get secure enabled_accessibility_services
   # must contain app.smarttubekids/com.liskovsoft.smartyoutubetv2.common.misc.KioskKeyGuardService
   ```
   Missing → enable it from Settings → Accessibility (or the ADB command above).
2. **Enabled but not bound** — the system must have it connected with the key filter:
   ```bash
   adb shell dumpsys accessibility | grep -A5 KioskKeyGuardService
   ```
3. **Bound but the ROM ignores the filter** — watch the guard on a HOME press:
   ```bash
   adb logcat -s KioskKeyGuardService
   ```
   Press HOME **on the remote** — `input keyevent` is NOT a valid test: keys injected
   over ADB enter at the dispatcher and skip the accessibility input filter, so the
   launcher opens with no `swallowed key` line even on a perfectly healthy guard.
   Also check that no setup grace is running: turning the kiosk switch on without the
   guard enabled (and the "Open App info" / "Open Accessibility again" shortcuts of the
   revert dialog) arms a 5-minute window in which every key passes through by design
   (the guard early-returns silently — again no log). The window ends early once the
   app is back from the Accessibility screen with the guard enabled. The verdict below
   is only valid with a real remote press and no grace.
   If it logs `Kiosk key guard: swallowed key 3` while the launcher still opens, the
   vendor build bypasses accessibility key filtering → fall back to the Device Owner
   setup (Option A), which blocks HOME/RECENTS in the window manager itself.

Related: `adb logcat | grep "Screen pinning not engaged"` proves the screen-pinning
no-op on Android TV 14 boxes (the root cause the guard was added for).

**The mic/assistant button only.** Which key does your remote send? Hold the mic
button and watch the log:
```bash
adb logcat -s KioskKeyGuardService
```
- `swallowed key 84` → your remote sends `KEYCODE_SEARCH`: the guard covers it, done.
- Nothing in the log and the assistant still opens → the remote sends
  `KEYCODE_ASSIST` (219) / `KEYCODE_VOICE_ASSIST` (231). Those are handled in
  `PhoneWindowManager.interceptKeyBeforeQueueing`, *before* any input filter, so no
  app can stop them — disable the assistant app instead:
  ```bash
  adb shell pm list packages | grep -i assist     # e.g. com.google.android.katniss
  adb shell pm disable-user --user 0 <assistant pkg>
  ```
  (or use the Device Owner setup, whose Lock Task refuses the assistant's start).

## Mile Marker Mode (KIDS v1.9)

When the parent enables **Mile Marker Mode** in Kids Mode settings, the time-up
black screen (force stop path) is replaced by a night-road scene drawn on the
activity decor (`MileMarkerOverlay`): a night road on pure black with ten **numbered markers
(1–10)**, each flanked by a street-light pole that lights up and glows brighter behind
the car as it passes. While the pause is still running the gate underneath is unchanged —
keys are swallowed and the PIN dialog needs 10 escape presses, each within 2 s of the
previous one — but since **KIDS v1.9.8** the pause ending opens it by itself: the
countdown reaches `0:00`, the car arrives at lamp 10, the "Reset complete!" sweep runs,
and the overlay releases the lock; during that 3 s celebration any key press that reaches the
app opens it immediately (the exact key rule is below).

> **Superseded by v1.15:** the car no longer opens by itself at the reset boundary — it stays
> parked at lamp 10 until the child presses an escape key (see the v1.15 section).

**KIDS v1.9.8 visuals: the scene IS the destination countdown.** Pure black AMOLED
background (`canvas.drawColor(Color.BLACK)`; the sky gradient is gone), so the road, its
edge stripes and the car stay the only lit elements. Lamps are brighter on black
(`LAMP_GLOW_ALPHA_MED` 22→45, `LAMP_BRIGHTNESS_MED` 50→80, `LAMP_GLOW_ALPHA_MAX` 85→120,
the behind-the-car ramp capped at 0.65 so a passed lamp keeps climbing, celebration halo
42→70) and the car is bigger (`CAR_WIDTH_FRAC` 0.04→0.052, `CAR_HEIGHT_FRAC`
0.035→0.046). `CAR_END_MARGIN_FRAC` 0.03→0 puts lamp 10 exactly at progress 1.0, i.e. the
reset instant, and the line at `height*0.78` now shows the **remaining time** to it
(`KidsQuotaWindow.formatRemaining`: `h:mm:ss` when ≥ 1 h else `m:ss`, seconds rounded UP,
pinned to the same 1 s refresh that moves the car) instead of being empty; only the
celebration branch draws "Reset complete!".

**KIDS v1.9.4: quota gate auto-opens at reset boundary; KIDS v1.9.5: that boundary is a real pause;
KIDS v1.9.7: the pause only starts once the quota is used up; KIDS v1.9.8: it opens with kiosk on too,
after a 3 s celebration.**
The quota gate (force stop path) schedules a one-shot task at the end of the cooldown pause — and only once the
quota is exhausted (no pause is running before that). When the
pause ends, `KidsTimeUpLock.onRolloverCheck` does not open at once: it posts `sOpenGateTask`
and `GATE_OPEN_CELEBRATION_MS` (3 s) later `onOpenGate` writes the fresh quota
(`resetWindow(now)`) and releases the gate — long enough for the car to be seen reaching
lamp 10 and the "Reset complete!" sweep to run. No PIN, no mash, and **v1.9.8 deleted the
v1.9.6 "kiosk keeps the gate shut" rule**: a device with kiosk ON opens by itself too, and
because the window is re-anchored at that instant the next clip is not insta-locked.
`sOpenGatePending` allows exactly one celebration per gate (a resume re-runs
`scheduleRolloverCheck`, which must not restart the 3 s countdown) and is cleared in
`release()` / `releaseStateOnly()` together with the task.
Lock-at-end mode is deliberately excluded: it still requires the PIN.

> **Superseded by v1.15:** the one-shot no longer opens the gate; the car/classic screen waits for
> an escape key and the night art for 06:00 (see the v1.15 section).

KIDS v1.9.5 replaced the clock-aligned grid with **cooldown-from-exhaustion** semantics, and KIDS v1.9.7
made the reset interval a **pause that only starts when the watch limit is reached** — it is not a rolling
window at all. Until the quota is exhausted nothing resets: the child's budget keeps being spent down across
sessions, however many days that takes, because `KidsQuotaWindow.isWindowExpired` returns false for any stored
anchor while `used < limit` **or while the `inCooldown` flag is clear** and no last watched instant is known —
elapsed time alone never proves a pause is running. An anchor without the flag (the parent lowered the timer
below the already-counted `used`, or the process died before `stopCounting()` could anchor the pause and the
idle `onTickle()` has not run yet) is not a pause boundary, so it alone can never trigger a rollover.
KIDS v1.12 adds the missing evidence: the **last watched instant** (`lastWatchMs`, blob index 27,
recorded by `KidsModeData.addUsedMs` in the same write as the counter on every counted minute) is the
fallback pause boundary, so a restart after a power cut recovers the real pause instead of anchoring a
fresh one (see "KIDS v1.12: the pause survives a restart" below). Without that value — an old blob —
the boundary falls back to `windowStartMs` (the only evidence left), or to `now` when even that is
0, and `resetWindowIfNeeded()` rolls over instead of arming a fresh wait when the pause has already
elapsed. The anchor means nothing before exhaustion and the PAUSE anchor is
written **when the exhausted playback STOPS** (`KidsModeController.anchorCooldownIfNeeded()`, called
from `stopCounting()` — pause, end of clip, released engine — and from the idle `onTickle()` for an
exhaustion nothing is playing anymore, once per exhaustion thanks to the persisted `inCooldown` flag,
blob index 24), so the pause is exactly `[T, T + interval)` from that instant. With **Stop
immediately** ON the cut is the stop, so the pause starts at the cut; with it OFF the running clip
finishes first (see the v1.10 reset-timing section below).
`KidsModeData.resetWindow()` does store a timestamp at rollover as well; it is simply not a pause
boundary without the flag, and `isWindowExpired` never treats it as one. The rollover —
in the controller (`resetWindowIfNeeded`) and in the gate one-shot (`KidsTimeUpLock.scheduleRolloverCheck`) —
then writes the fresh quota and starts the next window at `now`. Because a scheduled check always has an
exhaustion anchor, the gate one-shot always waits a full interval from exhaustion: no boundary exists before
that, and `scheduleRolloverCheck` refuses to schedule at all while `used < limit` **or `inCooldown` is clear**
— so a last-watch-known, flag-clear blob is never scheduled by the gate directly: the controller anchors (or rolls
over) first, and the gate is only ever armed after that (otherwise the one-shot would fire into
`onRolloverCheck`, see "not expired yet" and re-post itself every `ROLLOVER_MIN_MS`). The car plus the gate
read the SAME stored anchor, so the Mile Marker car reaches lamp 10 exactly at the reset instant and the
gate opens 3 s later — the length of the celebration (`GATE_OPEN_CELEBRATION_MS`) — never at a different
boundary. A hand-edited or corrupted blob whose anchor is 0 still recovers: the `ROLLOVER_MIN_MS` clamp fires
the one-shot and `onRolloverCheck` treats anchor 0 as expired.
The task uses the application context (not the Activity, which is destroyed by `forceFinish()`) and is cancelled
in `release()` and `releaseStateOnly()` to prevent chain stacking.

**User-visible consequence:** the watch budget is spent down across sessions until it is used up — an unused
quota is never replenished by the clock — and only then does the child wait the configured interval before a
fresh quota starts.

A parent extend during a cooldown (bonus minutes via `KidsModeSettingsPresenter` → `setBonusMs`, or a longer
timer) goes through `KidsModeData.applyParentTimeChange(...)` at the source, so no accumulate tick can ever
observe the new limit against the old anchor. While the pause is still running and the quota became unspent,
only the flag is cleared (anchor and spent budget untouched), so the NEXT exhaustion re-anchors at its own
instant and the pause is again a full interval from that new expiry — never a stale deadline from the first
one. Clearing it at the source (rather than waiting for the controller's next tick) matters because a child can
spend a small bonus in a single accumulate gap (the gap guard bounds `delta`, not the tickle) and be exhausted
again while the flag from the previous exhaustion is still set; the stale, already-elapsed anchor would then
hand out a free window. `KidsModeController.anchorCooldownIfNeeded()` still clears the flag on its
`used < limit` branch, covering any other path that leaves the quota unspent.

If the pause had ALREADY run out when the parent adds time (nothing materialised the rollover yet — the app
was idle, or no gate was locked to run the one-shot), the helper performs the FULL rollover right there:
`used = 0`, bonus = 0, anchor = `now`, flag cleared — and then re-applies only the grant THIS call just made.
That ordering is deliberate: an older bonus was spent by definition (exhaustion means `used >= timer + bonus`,
and `used` can even overtake the limit when a clip runs to its end with force stop off or the PIN gate session
suspends the hard stop), so keeping it would grant it twice; and testing elapse before "is the quota
replenished" is required because a grant smaller than the overrun would otherwise look unspent-but-exhausted
and be lost when the rollover zeroes the bonus.

Load-bearing rules (do not "simplify" them away):

- **The lamps show REAL window progress, not a demo animation.** Each 1 s tick
  recomputes `progress = elapsed / interval` from the persisted cooldown anchor
  (`windowStartMs` — written at expiry, not at grid boundaries). The car traverses
  the road over the full cooldown interval, and lamps light up behind it. Stateless
  by design: a process death or re-arm inherits nothing, and opening the scene
  after the window already rolled shows all lamps lit without re-firing the
  green celebration.
- **Art is frozen at arm time.** `KidsTimeUpLock.arm(activity, useMileMarker)`
  stores the choice in `sMileMarkerShown` (in-memory, next to `sLocked`); every
  re-cover path (`applyOnResume`, `onKeyPress`, the dialog-dismiss callback)
  routes through `cover()`, so a settings change mid-lock cannot swap the art
  under the child. The "Lock at end" mode deliberately keeps the classic black
  screen — only the timer path passes the pref (at bedtime it now gets the night
  art instead: see v1.11.0 below).
- **Separate decor tag.** The scene is tagged `mile_marker_overlay`, NOT the
  black screen's `kids_screen_off_overlay`. Anything that checks for a visible
  time-up cover must look for BOTH — that is why the countdown badge also
  suppresses on `MileMarkerOverlay.isShown()`, and why
  `refreshAfterExtend()` calls `MileMarkerOverlay.hide()` next to
  `hideBlackScreen()` (the extend path releases STATE only, so the view cleanup
  must be explicit there).
- `show()` is idempotent per host (tag check + `removeCallbacks(sTick)` before
  reposting) — it is called from both `arm` and `applyOnResume`.
- Suppressed in PIP; restores the configured brightness (the scene is useless
  at the black screen's MIN_BRIGHTNESS).

The same release expands the reset-interval options to
**5/10/15/30/60/90/120/180/240/300/360/480/600/720/1440 min** (`KidsModeData.RESET_INTERVAL_MINUTES`;
anything outside the set sanitizes back to 1440). The stored unit switched from hours to minutes
with a one-time migration (`KidsMigration.migrateResetIntervalUnit`), and the settings UI shows
values ≥ 60 as hours, values < 60 as minutes.

## Time-up screen: PIN or straight out (KIDS v1.9.6, revised v1.9.8)

When the watch limit runs out the screen is a gate: every key is swallowed and nothing is
shown. Ten quick escape presses earn the way out. Two things are configurable.

### Ask for the PIN after 10 presses (`Ask for the PIN after 10 presses`, default ON)

- **ON (default):** the ten presses open the PIN dialog. Only the correct PIN releases the
  gate — same behaviour as every earlier build.
- **OFF:** the ten presses release the gate directly, no dialog. The presses themselves are
  the proof that a grown-up is there.

**KIDS v1.9.8: the switch wins over kiosk.** The v1.9.6 rule ("kiosk mode always wins" — with
kiosk enabled the PIN was required in both cases, so turning the switch off only ever mattered
on a device with kiosk disabled) is deleted. With the switch OFF the ten presses release the
time-up screen with no PIN dialog **even when kiosk mode is on**: kiosk keeps HOME/RECENTS
blocked device-wide, and no `KidsPinGate` parent session is granted here (deliberately — that
session would let the same presses open the Kids Mode settings), so the child gains nothing
beyond the time-up screen. With the switch ON (default) the PIN is required, and kiosk adds
nothing to that rule.

The automatic release follows the same rule: when the cooldown pause ends (i.e. once the quota has
been used up and the configured interval has elapsed), the gate opens by itself **regardless of
kiosk mode** — 3 s after the boundary so the Mile Marker car is seen reaching lamp 10 — and the
fresh quota is written first, so the next clip is not insta-locked.

> **Superseded by v1.15:** nothing opens at the boundary any more. The fresh quota is written only
> when an escape key releases the car/classic gate; the night art leaves at 06:00.

**KIDS v1.9.8: one key press opens it once the pause is over.** `onKeyPress` (every key the
activity sees — `MotherActivity.dispatchKeyEvent` feeds them all while locked) and
`onGuardEscapeKey(context, event)` (the keys the guard swallows, filtered by
`KidsTimeUpLock.isEscapePress` to BACK / HOME / RECENTS / ESCAPE before consulting
`isResetWindowDone`) both bypass the mash counter once the pause has run out. So once the pause is
over, **any key press that reaches the app** opens the gate instantly — no 10 presses, no PIN, kiosk
included. With kiosk ON, HOME/RECENTS reach it through the guard; the mic/assistant and SEARCH keys
are swallowed by the guard, but `isEscapePress` rejects them early, so they never open the gate
(with kiosk OFF nothing swallows them, so there they reach `onKeyPress` directly).
`isResetWindowDone` is the controller's `isWindowExpired` predicate **plus** a
`used >= limit` guard, so a corrupted anchor-0 blob cannot open a live gate.
`MotherActivity`'s BACK path reaches the same check through `onKeyPress`, and
`onGuardEscapeKey` gained a leading `Context` parameter for it (call site in
`KioskKeyGuardService.onKeyEvent`). The release uses the host activity the caller already
has (`openResetGate(context, host)`), falling back to `MotherActivity.getResumedActivity()`.

### What counts as "10 quick presses"

One bound (`KidsTimeUpKeyGate.GAP_MAX_MS`): **each press within 2 s of the previous one**
(KIDS v1.9.6). Ten presses at 1.5 s intervals count (9 gaps = 13.5 s total); a press that
arrives more than 2 s after the previous one restarts the sequence, so a child pressing once
every few seconds can never accumulate a count.

The earlier build also required all ten presses inside 10 s measured from the first one. That
outer bound is gone: it silently rejected a steady 1.5 s mash and made the real requirement
much stricter than "no pause longer than 2 s".

Counted keys stay BACK / HOME / RECENTS / ESCAPE. The mic / assistant keys are never counted.

## Night scene after an hour (KIDS v1.10.0-kids)

> **Status (2026-11-11):** committed on branch `kids` as `8c7fd064`; CI run
> [43014606413](https://github.com/mastternick/SmartTubeKids/actions/runs/43014606413) green
> (lint + build, artifact `SmartTubeKids_32.56-kids-83_*`). DEX/arsc verified on that artifact.
> **Not released; on-device smoke still pending.**

Kids Mode gained a second art choice for the time-up screen: **Night scene after** (`kids_bedtime`).
Values are **Off** (default) and **18:00 / 19:00 / 20:00 / 21:00 / 22:00 / 23:00**, stored as
minutes-of-day at **blob index 26** (`KidsModeData.mBedtimeMinutes`, default `KidsBedtime.OFF`).
A blob written by an older build has no index 26 and reads as Off, so no migration was needed;
a corrupted value sanitizes back to Off.

**When it replaces the car.** Only when the quota gate arms: `KidsModeController.forceStopSession()`
evaluates `KidsBedtime.isNight(hour, now)` once and calls `KidsTimeUpLock.armNight(activity)` instead of
`arm(activity, mKidsData.isUseMileMarker())`. On a night gate the scene therefore wins over Mile Marker
Mode (`SCENE_NIGHT` has precedence). The choice is frozen at arm time exactly like the car is —
`cover()` redraws the same scene on every re-cover (`applyOnResume`, key presses, dialog dismiss), so the
clock crossing the bedtime during a running gate never swaps the art underneath the child.

**The window crosses midnight.** Night is `[hour, 06:00)`: `21:00` means 21:00 → 05:59 and the car is back
at 06:00. The end is `KidsBedtime.NIGHT_END_MINUTES = 360`, a **constant**, deliberately not a second
setting. 06:00–18:00 keeps the v1.9.8 behaviour.

**What it draws.** `KidsNightScene` adds a view tagged `kids_night_scene` to the activity decor
(`setClickable(true)`, `setFocusable(false)`, brightness restored like the car does): pure black canvas,
a deterministic star field with a slow twinkle, a moon with craters and glow, a ground line and a small
animal curled up asleep with a breathing scale and floating "Z"s — all `Paint`/`Canvas`, no assets.
After `SCENE_MS` it fades over `FADE_MS = 2_000` into the plain black screen at minimum brightness —
the "switched off" TV the request asked for at the time. `show(activity, elapsedMs)` needs no
separate expiry branch in `cover()`: once the elapsed time is already past the scene's duration it goes
straight to the end state, so a resume (or a watchdog re-cover) after the scene is over shows the end
state instead of restarting the scene. (v1.10.0 shipped `SCENE_MS = 60_000` with that
minimum-brightness end state; v1.11.0 raised the duration to five minutes and the end state is now the
switched-off screen — next section.)

**Nothing else changes.** The gate underneath is v1.9.8 unchanged: every key is swallowed, ten
BACK/HOME/RECENTS/ESCAPE presses with at most 2 s between neighbours earn the way out (`KidsTimeUpKeyGate`),
the PIN dialog appears only while **Ask for the PIN after 10 presses** is ON (otherwise the tenth press
releases directly), the gate opens by itself at the reset boundary, and the kiosk layers, key guard and
guardian are untouched. In v1.10.0 the night scene was **art, not a second lock**: it lived strictly
inside the quota gate, so with **Stop immediately** off there was no gate at all and therefore no night
scene. **Superseded by v1.11.0** (next section): the night gate now arms from the time-up decisions
themselves and holds with that switch off. The no-PIN case still self-releases on the first key press.

> **Superseded by v1.15:** the night art is a clock lock — no automatic release at the reset
> boundary, only the mash + PIN or the 06:00 boundary.

Load-bearing details:

- **The wrap is the whole point.** The test must be true at 00:30 for a 21:00 bedtime. A naive
  `nowMinutes >= bedtimeMinutes` comparison is false there and would hand the car back at 00:30 while the
  overnight reset window is still running; `KidsBedtime.isNight` compares against `NIGHT_END_MINUTES` in
  that branch — do not "simplify" it into one comparison.
- **`isGateActive` gained no scene term in v1.10.** The "never trap" release — the first key press
  releasing a gate the parent disarmed meanwhile — is intentional. v1.11 adds a scene term, but only as
  `isBedtimeGateActive()` (guarded by `isKidsGateBase` + `!isGateUnlocked()`), never
  as "a scene view is on screen"; see the next section.
- **The fade needs an opaque cover, not scene alpha.** Fading the scene view itself makes its own
  black canvas transparent and shows the playlist through it for the two seconds; `KidsNightScene`
  therefore fades a black `kids_night_scene_fade` view in above the artwork (and the backlight down
  with it). Same family as the v1.2.x overlay bugs: a black screen is only black if something opaque
  is on top.
- **The uptime anchor owns the countdown, not the tick.** `show()` is called by `cover()` on every key
  press and resume; it posts the tick only in the attach branch. Re-posting there would let a child
  mashing the remote push the expiry out indefinitely, so the scene would never reach the end
  state the request asked for. The duration itself (`SCENE_MS`) is the only thing that moved.
- **One arm helper.** `arm(...)` and `armNight(...)` both delegate to `armQuotaGate(activity, scene)`,
  which sets `armState()`, the scene and `sNightStartMs`, covers, sets `sQuotaGate = true` and schedules
  the rollover check. Letting them diverge would silently break the no-PIN release branch
  (`sQuotaGate && !isPinRequiredAfterMash()`) and the automatic opening at reset.

## Night scene holds five minutes, then the TV is off (KIDS v1.11.0-kids)

**The bug.** With **Stop immediately when time is up** OFF the time-up never reached the gate: the
expired clip took the calm-exit path (fade + `setPendingScreenOff()` + `forceFinish()`), the next
playlist load was refused with a message, and no cat ever appeared — the night art was reachable only
through `forceStopSession()`, i.e. only with that switch ON.

**The fix.** The night gate now arms from the same two time-up decisions the hard stop uses, and the
switch is no longer a precondition for the NIGHT art:

- `KidsModeController.onNewVideo` — quota expired and `isForceStopActive() || isBedtimeLockDue()` → a
  refused clip ends on the cat-then-black gate (with the switch OFF the clip already running is still
  allowed to finish, as before).
- `KidsModeController.onVideoSessionEnd` — the exhausted clip ending calls `forceStopSession()` when
  `isTimeExpired() && (isForceStopActive() || isBedtimeLockDue())`. This is the path the report hit.
- `isBedtimeLockDue()` is the one place the bedtime test lives now (`KidsBedtime.isNight` inside, false
  when `KidsModeData.getBedtimeMinutes()` is Off); `forceStopSession()` uses it too. It also requires
  `!KidsTimeUpLock.isGateUnlocked()`: during the parent's 10-minute gate authorization the night gate
  does not re-arm, exactly like the hard stop (which `isForceStopActive` already lapses then), so an
  authenticated parent is not re-locked by the next refused clip.

**Five minutes, then a switched-off TV.** `KidsNightScene.SCENE_MS = 5 * 60_000L`; the run is still
anchored in uptime (`KidsTimeUpLock.sNightStartMs`), so re-covers continue it and a resume past the
five minutes goes straight to the end state. After `FADE_MS` (2 s, opaque cover + backlight down) the
scene hands over to `KidsScreenHelper.showScreenOff(activity)`: black overlay **plus window brightness
exactly 0**, indistinguishable from a TV switched off. `KidsNightScene.setBrightness` therefore clamps
to `[0, 1]` instead of flooring at `MIN_BRIGHTNESS` — the fade must reach a dark panel.

**The gate stays locked underneath.** `KidsTimeUpLock` keeps `sLocked`, swallows every key, requires
the 10-press mash and — while **Ask for the PIN after 10 presses** is ON — the PIN (otherwise the
tenth press releases directly), and re-opens by itself at the reset boundary: the same gate as
v1.10, only the art changes. While the PIN dialog is on screen the five-minute countdown holds still
— and "on screen" is the live dialog itself, `KidsTimeUpLock.isPinDialogOpen()` reading the stored
`AlertDialog.isShowing()` (`SimpleEditDialog.showPassword` now returns the dialog, `null` when the
`show()` call threw), so a swallowed `BadTokenException` cannot leave the scene frozen forever and a
genuinely open dialog is never darkened. During that time `KidsNightScene.show()` re-applies the black
overlay with `KidsScreenHelper.showBlackScreenKeepBrightness`, which leaves the configured window
brightness alone — a key-press re-cover (or a guard-swallowed HOME) cannot dim the panel under the
prompt. Dismissal re-covers into the switched-off state.

> **Superseded by v1.15:** the night art no longer re-opens at the reset boundary — it is a clock
> lock, released only by the mash + PIN or at 06:00.

**Lock at end gets the art too.** `lockScreenAtVideoEnd()` calls
`KidsTimeUpLock.armNightArt(activity)` when `isBedtimeLockDue()`, else the classic `arm(activity)`.
Art only: `sQuotaGate`/`sBedtimeLock` stay false, so the lock keeps its v1.7.5 semantics — the reset
boundary never opens it, the mash opt-out does not apply (PIN always), and gate activity still comes
from `isLockAtEndActive`. Five minutes of cat, then the switched-off screen, same re-cover anchor.

**One prompt at a time.** `onKeyPress` returns before opening a dialog when `isPinDialogOpen()` — the
30 s throttle alone is not enough: a kiosk-swallowed HOME/RECENTS mash after it lapsed would stack a
second prompt and replace the tracked reference, and cancelling that one would null the reference
while the first prompt is still on screen (the scene would then darken the panel under it). The
throttle stays for the other case it was written for: a `show()` that failed, whose prompt never
appeared.

**Why `showScreenOff` re-applies brightness on every call.** The overlay is idempotent, the brightness
is not: a resumed activity, a `KidsScreenHelper.applyBrightness` from a settings change or a parent
brightness restore would otherwise leave the "switched off" screen glowing at a visible level. For the
same reason `KidsTimeUpLock.onKeyPress` calls `KidsScreenHelper.applyBrightness(activity)` immediately
before `SimpleEditDialog.showPassword(...)` — a PIN prompt on a panel that is still at 0 is a trap.
A cancel re-covers (`cover()`) and dims it again.

**The safety invariant.** The gate holds with **Stop immediately** OFF because `isGateActive()` now has
a third reason, `KidsTimeUpLock.isBedtimeGateActive()` (`sBedtimeLock` + `!isGateUnlocked()` +
`isKidsGateBase()`), planted by `armQuotaGate` for `SCENE_NIGHT` and cleared in
`release()`/`releaseStateOnly()`/`armState()`. With no PIN configured — or Kids Mode off —
`isKidsGateBase()` is false, so the first key press still releases the gate: no trap. It ends on any
release path (PIN unlock, the automatic open at the reset boundary, `openResetGate`, a parent bonus,
Kids Mode or the PIN switched off), not on a quota check — the helper deliberately has no
`isQuotaExhausted` term, exactly like `isForceStopActive`: a rolled-over window reached through an
unhandled key press must still go through the `sQuotaGate && isResetWindowDone` branch that writes the
fresh quota, otherwise the child would land on a screen that re-locks on the next play attempt.

> **Superseded by v1.15:** the reset boundary is no longer a release path of its own; it merely
> permits the escape-key release (and the night art's 06:00 boundary).

> **Status:** committed on branch `kids` as `26f04b7b` (v1.11.0) and `aaaccc95` (v1.11.1, the
> Lock-at-end art); CI runs
> [44172595898](https://github.com/mastternick/SmartTubeKids/actions/runs/44172595898) and
> [44178526400](https://github.com/mastternick/SmartTubeKids/actions/runs/44178526400) green
> (lint + build, artifacts `…kids-86_*` / `…kids-89_*`). DEX probe of the universal APKs:
> `showScreenOff`, `showBlackScreenKeepBrightness`, `isBedtimeLockDue`, `isPinDialogOpen`,
> `isBedtimeGateActive`, `armNightArt` present, `isBedtimeNight` absent (the renamed helper).
> **Not released.**
>
> **Device:** the TV box (Box R 4K, Android 14, `armeabi-v7a`) was updated **in place** on
> 2026-11-18 with `adb install -r SmartTubeKids_32.56-kids-89_armeabi-v7a.apk` — previous build
> `32.56-kids-85`, no uninstall, so the Kids Mode PIN, the quota counters and the app data
> survived. Verified on the box: `versionName=32.56-kids-89`, `ACCESS_RESTRICTED_SETTINGS=allow`,
> `KioskKeyGuardService` enabled **and bound** (`dumpsys accessibility`), no FATAL/ANR in logcat,
> `BrowseActivity` resumed. The on-device smoke of the night lock itself is still pending.

## Reset pause starts when the exhausted clip stops (KIDS v1.10 fix)

**The bug (v1.9.7 → v1.10.0).** `KidsModeController.accumulatePlayTime()` anchored the reset pause the moment
`used` crossed the limit. With **Stop immediately when time is up** OFF nothing stops the clip at that instant —
it keeps playing while the pause runs — so the later tickle hit `resetWindowIfNeeded()`, rolled the window over
mid-clip, and handed the child a fresh quota while the very same clip was still on screen (badge, gate and
counters all jumped back).

**The new rule.** The counter only SPENDS the budget and never anchors. The pause anchor is written when the
exhausted playback STOPS:

- `stopCounting()` — pause, end of clip, released engine — clears the play marker and then anchors, so the
  child's own "stop watching" instant is the pause start;
- `onTickle()` anchors an exhaustion that nothing is playing anymore (process restart, quota spent to the last
  millisecond) — the idle path has no stop event;
- `anchorCooldownIfNeeded()` refuses to anchor while `mLastPlayStartMs != 0` unless
  `KidsTimeUpLock.isForceStopActive()`, which is what closes the mid-clip hole.

**Force-stop ON is unchanged.** The cut IS the stop, so the pause starts at the cut and the gate opens one
interval later, exactly as in v1.9.8.

Edge cases, on purpose:

- Pause/stop mid-clip after exhaustion: the pause anchors then. Pressing PLAY again stops the pause clock — an
  elapsed pause rolls over first, and a still-running one drops the `inCooldown` flag (the anchor stays but is not
  a boundary), so the next stop re-anchors the full pause and no rollover can land mid-clip. With force-stop ON
  the anchor is kept so the gate still opens one interval after the cut.
- Process death while exhausted and playing: no anchor is written until the next `stopCounting()`/`onTickle()`.
  Since v1.12 the last watched instant `lastWatchMs` is persisted on every counted minute, so a restart
  recovers the real pause boundary instead of anchoring a fresh wait (see the next section).
- No blob index was added by v1.10 itself; v1.12 appends index 27 (the last watched instant) and otherwise
  leaves `KidsQuotaWindow`'s rule and `KidsModeData`'s earlier slots untouched.

**Device smoke (pending — needs the parental PIN):** set limit 15 min, reset 5 min, **Stop immediately** OFF and
start a 30-minute clip → at 15:00 the badge reaches `0:01` and then disappears, and the quota does NOT roll over
while the clip plays; when the clip ends the pause starts; new videos are blocked; after 5 min the next clip
plays normally. Then with **Stop immediately** ON the clip must still cut at 15:00 and the screen must open 5 min
later.

## KIDS v1.12: the pause survives a restart

**The bug.** The quota was exhausted, the box was powered off overnight, and opening the app ~10 h later showed
the Mile Marker car again — a fresh 3 h pause instead of the one that had already elapsed. `KidsModeData` stored
`usedMs`, `windowStartMs` and `inCooldown`, but never WHEN the child was last actually watching. A blob left at
"`used >= limit`, `inCooldown == false`" therefore had no boundary: `isWindowExpired` answered false, nothing
rolled over, and `anchorCooldownIfNeeded()` anchored a fresh pause at the next app start (the first
`PlaybackPresenter.instance(...)` tickles it, before any play).

Three ways that ambiguous state arises:

1. **Deferred anchor + force-stop OFF**: the clip keeps playing after the crossing and the process dies before
   `stopCounting()` writes the anchor;
2. **Two-write race**: the counter write (`addUsedMs`) and the anchor write are separate blob writes; a power cut
   between them loses the anchor (`KidsQuotaWindow` documented this already);
3. **`onPlay` clearing the flag**: starting a clip after exhaustion drops `inCooldown` (the pause clock stops),
   and a death before the next stop leaves the spent budget with no flag.

**The fix.** `KidsModeData.addUsedMs` records the LAST WATCHED instant in the SAME write as the counter,
on every accepted delta: `lastWatchMs = clamp(now, max(windowStartMs, 1), now)`, persisted as blob
index 27. `KidsQuotaWindow.isWindowExpired` now takes that value and, when `inCooldown` is clear, uses
the last watched instant as the pause boundary. `anchorCooldownIfNeeded()` anchors at
`max(lastWatchMs, windowStartMs)` when that is positive, else at `now`, and `resetWindowIfNeeded()`
rolls over first when that boundary + interval has already elapsed, so the child gets the fresh quota
the elapsed pause earned instead of a new wait. The gate (`KidsTimeUpLock`) and the countdown badge
pass the same value, so car, badge and gate share one predicate.

**Why the last watch and not the crossing.** With **Stop immediately** OFF a clip is allowed to finish,
so it can run for hours past the limit. The instant the quota was CROSSED would credit that whole
overrun as pause: a box killed at 22:00 after a context that crossed at 18:00 would look like a pause
that ran 4 h and hand out a fresh quota immediately. The last counted minute keeps the pause honest —
it starts when the child actually stopped watching. It also removes a badge inconsistency: mid-clip the
crossing + interval could expire while the controller (correctly) refuses to roll over, so the badge
would flip to a fresh quota the hard stop does not honour.

**The field is not a permanent fact.** It is cleared when the counters roll over (`resetWindow`), when
a parent change makes the quota spendable (`applyParentTimeChange`'s first statement, before its
`!inCooldown` early return), and by `setUsedMs` below the limit. It is deliberately NOT cleared by
`clearCooldown()`: with "Stop immediately" OFF the clip keeps playing after a resume, so every counted
minute must keep moving the instant — dropping it there would make the next death fall back to `now`.
`anchorCooldown()` does not adopt it either; the pause anchor lives in `windowStartMs` while the flag
is set.

Two load-bearing scenarios (limit exhausted at 18:00, interval 3 h):

- **Scenario A — 10 h idle.** The quota is spent at 18:00, so `addUsedMs` writes `lastWatchMs = 18:00`.
  The box dies at 18:05 before `stopCounting()`, so `inCooldown` stays false. At 04:00 (10 h after the
  last watched minute) the app starts: `isWindowExpired` sees `used >= limit`, the flag clear and
  boundary 18:00, and 04:00 − 18:00 ≥ 3 h → `resetWindowIfNeeded()` writes `resetWindow(04:00)` (fresh
  quota) before any anchor; `anchorCooldownIfNeeded()` then finds `used = 0 < limit` and does nothing.
  The child gets the fresh quota the elapsed pause earned and **no gate** appears.
- **Scenario B — 4 h overrun, then a kill.** **Stop immediately** OFF, the quota crosses at 18:00 but
  the clip runs to 22:00; every counted minute moves `lastWatchMs`, so the blob says 22:00 (and
  `used = limit + 4 h`). The box is killed at 22:05 before `stopCounting()`. Restart at 23:00:
  23:00 − 22:00 < 3 h → the quota stays exhausted and no rollover happens, so a due gate (force stop
  on, or bedtime) is restored and anchored at 22:00. The gate opens at **01:00**, 3 h after the last
  watched minute — not at 21:00 as the crossing would have claimed. Restart at 01:30 instead and the
  pause has elapsed: fresh quota, no gate.

Behaviour table (the "boundary" is `windowStartMs` while `inCooldown` is set, else `lastWatchMs`):

| `inCooldown` | `lastWatchMs` | elapsed since boundary | result |
|---|---|---|---|
| — (any) | any | n/a, `used < limit` | no rollover — the pause has not started |
| true | any (ignored) | `< interval` | pause continues, gate stays armed |
| true | any (ignored) | `>= interval` | rollover to a fresh quota |
| false | `0` (old blob, or just reset) | — | no rollover in the predicate; the controller anchors at the window start, or at `now` when that is 0 |
| false | `> 0` (last watch) | `< interval` | pause continues from the last watched minute |
| false | `> 0` (last watch) | `>= interval` | **rollover immediately** — the reported bug |

**Blob rule: append only.** `lastWatchMs` is the 28th item (index 27), appended after `mBedtimeMinutes`;
no existing index moves. An older blob ends at index 26, so `parseLong(..., 27, 0)` yields 0 = unknown
and the pre-v1.12 path is taken. Pre-release builds wrote the crossing into this slot; a crossing is
always ≤ the last watched instant of the same window, so such a blob is safe to read as-is (it merely
errs on the side of an earlier boundary). Nonsense values are dropped to 0 (`<= 0`, `< windowStartMs`,
or `> now` after a clock change). A downgrade to a build without index 27 simply drops the field — the
counters, the flag and the anchor remain readable, so the only loss is the restart recovery.

**Mid-clip guard.** `resetWindowIfNeeded()` refuses to mutate the window while `mLastPlayStartMs != 0` unless
force stop is active, exactly like the v1.10 anchor guard: the fallback boundary is evaluated on
`accumulatePlayTime` ticks too, and a > interval clip must never roll over mid-clip through it. With
"Stop immediately" ON the cut is the stop, so the rollover-authorized path is intact.

## Calm-exit warning banner (KIDS v1.10)

The -5 / -2 / -1 minute warnings were a toast (invisible on top of the player) plus `showOverlay(true)`, which
interrupted the clip. `KidsWarningBanner.show(activity, text)` now attaches a bottom-center `TextView` tagged
`kids_calm_warning` to the activity decor (`0xCC000000`, 26sp white bold, non-focusable / non-clickable /
non-long-clickable), refreshes the text if the banner is already up and removes itself after 8 s. A short
`ToneGenerator` beep (`STREAM_MUSIC`, released after the tone) plays **only** while a clip is initialized and
playing, so a paused player or the browse screen stays silent. Playback is never paused, seeked or overlaid.

The RO translations (`kids_calm_exit`, `kids_calm_exit_desc`, `kids_time_left`) were missing and are added.
`kids_lock_at_video_end` now reads **Lock screen at end of clip (PIN)** / **Blocare ecran la finalul clipului
(PIN)**; its description text is unchanged.

## Kiosk key guard after a reboot (KIDS v1.9.6)

**Symptom:** the guard worked once, the TV was restarted, and now the Accessibility switch
ticks itself back off — it cannot be enabled at all.

**Cause (platform, not an app bug):** on Android 13+ a package installed outside the Play Store
carries `ACCESS_RESTRICTED_SETTINGS: deny`. AccessibilityManagerService re-filters such a
package at every boot and rewrites `enabled_accessibility_services` to null, so a guard that
was written with `settings put` is silently undone by the next restart.

**Durable fix, once, over ADB:**

```bash
PKG="app.smarttubekids" # or app.smarttubekids.stable / app.smarttubekids.fdroid
adb shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure enabled_accessibility_services "$PKG/com.liskovsoft.smartyoutubetv2.common.misc.KioskKeyGuardService"
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services   # must print the component, not null
```

- The **app-op** is the part that survives reboots; without it every other command is undone
  at the next start. It is NOT permanent against updates: a sideloaded update can silently
  re-arm the restriction, so re-check `appops get` after every sideloaded update.
  Lowercase `access_restricted_settings` fails with `Unknown operation string` — the name
  is case-sensitive.
- The **`pm grant`** is optional but recommended: with it the app re-enables its own guard at
  boot (`KioskModeManager.trySelfHealKeyGuard`, called from `onDeviceBooted` and
  `applyOnResume`), appending its component to whatever else is enabled — never replacing it.
  Without it the guard must be re-ticked manually after every reboot, and the app can only
  show the guidance dialog.
- A sideloaded **update** can re-arm the restriction; re-check with
  `adb shell appops get "$PKG" ACCESS_RESTRICTED_SETTINGS` if the switch starts reverting again.

Verify binding and function separately: `adb shell dumpsys accessibility | grep -A5 KioskKeyGuardService`
(bound, with `CAN_REQUEST_FILTER_KEY_EVENTS`) and then press HOME **on the physical remote** —
`adb shell input keyevent` bypasses the accessibility input filter and proves nothing.

## Hard lock while a time-up gate is up (KIDS v1.13)

The car (Mile Marker) and the cat (night scene) are now a **hard lock**: while either is on
screen the device has no route out for the child except the parent's PIN, the reset window
elapsing, or the parent turning Kids Mode / the PIN off. Five changes make that true, each one
closing a way the gate could be lost:

1. **The art gates always ask for the PIN** (`KidsTimeUpLock.onKeyPress`). The "Ask for the PIN
   after 10 presses" switch now only applies to the classic black time-up screen
   (`sQuotaGate && sScene == SCENE_PLAIN && !isPinRequiredAfterMash()`). With the switch off a
   mashed BACK on the car or the night scene no longer releases anything — those two are the
   hard lock and only the PIN opens them. The settings description says so.
2. **The gate survives a restart** (`KidsTimeUpLock.restoreIfDue`, called from
   `applyOnResume` *before* the in-memory `sLocked` guard). The lock used to live only in
   memory, so a process death, a crash or a reboot handed the child the playlist with the
   quota already spent. A fresh process now re-derives the gate from the persisted quota and
   re-arms the same art the controller would choose (bedtime → night scene, otherwise the mile
   marker if selected). The rollover one-shot is re-scheduled from the persisted
   `windowStartMs`, so a restored gate still opens by itself at the end of the pause; a blob
   whose pause flag was never written (a cold launch has no controller tick yet, a legacy blob
   has no last watched instant at all) is normalised first — boundary = the last watched
   instant, else "now" — so the auto-open always has one. If that boundary has already
   elapsed, the quota is rolled over and no gate is armed. The check runs **once per process**
   (`sRestoreChecked`): only the first resume after a process start may invent a gate, later
   resumes merely re-apply an existing one. Without that, a mid-clip resume at bedtime with
   "Stop immediately" OFF (a dialog, PIP, entering Settings) would re-arm the night gate and
   cut a clip the parent allowed to finish.
   **Superseded by v1.15:** the restored car waits for an escape press instead of opening at the
   end of the pause, and the restored night art survives an elapsed pause until 06:00.
3. **Escape keys and the bring-back are gate-aware** (`KioskModeManager`).
   `isKeyGuardActive` is `KidsTimeUpLock.isLocked() || (kiosk on && no grace && no parent
   release)`, so where the accessibility guard is enabled the input filter kills HOME /
   RECENTS / mic device-wide **even with kiosk OFF** — the in-app swallow is useless once the
   app is backgrounded. `consumeEscapeKey` shares that predicate. `bringAppBack` and
   `scheduleReentry` treat a locked gate like kiosk, so the child is pulled back onto the
   ask-for-PIN screen; `scheduleReentry` also starts the v1.4 watchdog, which keeps relaunching
   even on a device where the guard was never enabled (a key leak then costs the child ~1 s on
   the launcher, never the gate). `KioskKeyGuardService` still feeds the swallowed keys into
   `KidsTimeUpLock.onGuardEscapeKey`, which is what makes the mash/PIN flow reachable with the
   app off screen. The Device Owner exemption in `scheduleReentry` now requires an active kiosk:
   with kiosk off there is no lock task confinement, so a DO box would otherwise leave a gate
   with no recovery.
4. **PIN guesses are bounded** (`KidsTimeUpLock`). Five wrong PINs dismiss the prompt, clear
   the mash sequence and pause the prompt for five minutes (`MAX_PIN_ATTEMPTS = 5`,
   `PIN_LOCKOUT_MS = 5 min`). The gate stays armed throughout — a brute-forcing child only
   loses the prompt, never the lock. The counters reset on a correct PIN, on a new arm and on
   every release.
5. **The watchdog never abandons a locked screen** (`KioskWatchdogService`). The ~45 s
   give-up valve does not fire while `KidsTimeUpLock.isLocked()` (it keeps covering and
   relaunching, logging once per valve period), and a PIP window no longer counts as a
   legitimate "not fullscreen" state while a gate is up.

### The never-trap invariant

The hard lock can only ever be released by something the parent controls or by the clock:

- **Kids Mode off or the PIN off** ⇒ `isKidsGateBase` is false ⇒ the next key press (or the
  key guard's own `onGuardEscapeKey` route) releases the gate at once. A parent who disables
  either in Settings is never locked out by a leftover overlay.
- **The reset boundary always opens the gate** — no mash, no PIN, kiosk or not
  (`openResetGate`, and the first key press after the boundary).
  **Superseded by v1.15:** the boundary alone opens nothing; for the car and the classic screen the
  first escape press after it releases without a PIN, and the night art leaves at 06:00.
- **The correct PIN always releases** — and grants the usual 10-minute gate session. That
  authorization is in-memory only, so a process death drops it and the restored gate arms
  again (the parent simply types the PIN once more); what survives a restart is the persisted
  quota, not the unlock.
- **A gate is never invented**: `restoreIfDue` returns unless the quota is actually exhausted
  and a gate is due anyway (hard stop on, or the bedtime window). The calm exit stays calm.

### PIN lockout

Five wrong PIN entries in one gate → the prompt closes and cannot be opened again for five
minutes; the screen stays covered, the mash must be earned from scratch afterwards. The counter
is per gate and in memory: a correct PIN, a reset of the window, a new arm or any release
clears it.

### Out of the app's reach

Even with all of the above, these stay outside the lock — they are the platform's, not the
app's:

- **POWER / reboot** — the TV can always be switched off, and a reboot drops every in-memory
  state (the persisted-quota restore brings the gate back on the next resume).
- **TV / HDMI-CEC input switching** — the remote's input key and a CEC device can select
  another source; no app-level filter can stop the set from changing input.
- **The volume OSD** — volume keys are handled by the system and show its own overlay.
- **The assistant overlay** — on devices where the assist key is decided before any input
  filter, the assistant can still cover the screen (disable the assistant app for a real lock).
- **The system power menu** — long-press POWER belongs to the system.

> **Status (2026-11-19):** committed on `kids` as `48425e763` (v1.13) and `3b3c79fc3` (v1.14, the
> last-watched boundary + restore-once); CI runs
> [44512686648](https://github.com/mastternick/SmartTubeKids/actions/runs/44512686648) and
> [44517813264](https://github.com/mastternick/SmartTubeKids/actions/runs/44517813264) green
> (artifacts `…kids-95_*` / `…kids-99_*`; the DEX carries `restoreIfDue`, `isKioskOrGateActive`,
> `sPinLockoutUntilMs`, `getLastWatchMs`, `sRestoreChecked`, and no `mExhaustedAtMs`). The box was
> updated **in place** twice (`32.56-kids-89` → `32.79-kids-95` → `32.79-kids-99`):
> `ACCESS_RESTRICTED_SETTINGS=allow`, `KioskKeyGuardService` enabled **and bound**, no FATAL in
> logcat. On the kids-95 launch a gate was **not observed**: the captured screen showed the browse
> grid with 27:37 left on the badge, i.e. the quota was not exhausted, so `restoreIfDue` returned at
> `isQuotaExhausted`. The lock itself was **not** exercised on the device — that needs a spent quota
> plus the parent PIN.

## v1.15: the gate stays, the child leaves (KIDS v1.15)

**The complaint.** At the end of the reset pause the car opened the playlist by itself. The owner
wants the opposite: the screen is a wall, and the child is the one who leaves it. The car at lamp 10
must stay parked; BACK then exits without a PIN, because the pause has already been served. The cat
(night art) is a clock lock and never opens on a key press without the PIN.

**(a) The car no longer auto-opens.** `GATE_OPEN_CELEBRATION_MS`, `sOpenGateTask`,
`sOpenGatePending` and `onOpenGate()` are gone. The rollover one-shot is now informational: at the
boundary `onRolloverCheck()` logs
`"Reset pause over: gate keeps the art until an escape press (scene=%s)"` and returns — it opens
nothing and writes no quota. `scheduleRolloverCheck()` also returns early when the pause has already
elapsed, so a resume does not re-post a pointless timer. The fresh quota is written only by
`openResetGate()`, i.e. when an escape key actually releases the car or the classic black gate
(`onKeyPress` / `onGuardEscapeKey`). Before arrival the PIN is still earned the v1.13 way: ten quick
escape presses, each within 2 s.

**(b) The night art is a clock lock.** At the reset boundary the cat is untouched: `onKeyPress`'s
arrival branch is skipped for `SCENE_NIGHT` and `onGuardEscapeKey` falls through to the mash counter,
so only the mash + PIN releases it early. In the morning `scheduleMorningCheck()` arms a one-shot for
`KidsBedtime.msUntilMorning(now)` — the next 06:00 — and `onMorningCheck()` releases the gate right
after replenishing the window (`resetWindow(now)`), otherwise the first morning clip would re-lock.
A box that is **asleep** at 06:00 has no resumed activity (`MotherActivity.onPause` cleared it):
releasing there would leave the cat/black view attached with no lock behind it, so the check retries
every 60 s (`MORNING_RETRY_MS`) and, if the wake-up happens after 06:00, the resume path re-arms it
with a 1 s delay (`scheduleMorningCheck` sees a non-night clock) instead of waiting for tomorrow.
The one-shot is re-armed by `armQuotaGate` (night scene), by `applyOnResume` and by itself when the
clock is pushed back into the window, and is dropped in `release()` / `releaseStateOnly()`. It also
survives a process restart: `restoreIfDue` reads the persisted pause tuple — `inCooldown` set,
`used >= limit`, and the pause anchor (`windowStartMs`) **inside the night window** — as `nightHeld`.
The anchor scoping is load-bearing: the same tuple also describes a daytime pause, and adopting it
there would hand a restarting child a full fresh quota (the new "morning already passed" wipe would
fire on a 15:00 anchor) and would arm the cat at 23:00 for an exhaustion from the afternoon. Adopting
the held gate still goes through the unchanged "gate is due anyway" test — **Stop immediately ON, or
the clock still inside the night window** — so a parent who turned the hard stop off is not locked by
a restart. A held night gate whose clock already passed 06:00 — the release never ran because the box
was off — is replenished and dropped instead of being shown for a moment on that launch.

**(c) The controller no longer rolls the window under a gate.**
`KidsModeController.resetWindowIfNeeded()` returns at once while `KidsTimeUpLock.isLocked()`, so the
~60 s `onTickle` cannot drop the overlay by rewinding the quota behind it. The gate owns the boundary
now; the release paths write the fresh window themselves.

**(d) The PIN prompt is non-cancelable.** `SimpleEditDialog.showPasswordSticky(...)` is the gate's
variant: `setCancelable(false)` + `setCanceledOnTouchOutside(false)`, so BACK cannot dismiss the
prompt (the child cannot hide it and re-mash for free); Cancel still works and re-covers the screen.
Every settings prompt keeps the old dismissible behavior. Because BACK can no longer clear it, every
gate release dismisses the prompt explicitly (`dismissGatePrompt`, called by `release` and
`releaseStateOnly`): a non-cancelable prompt must never float over the playlist it was gating —
e.g. when the morning boundary releases the gate at 06:00 with a prompt open.

**(e) Brightness applies everywhere.** `MotherActivity.onResume` calls
`KidsScreenHelper.applyBrightness(this)` right after `applyFullscreenModeIfNeeded()` and before
`KidsTimeUpLock.applyOnResume` (the night scene's screen-off state must win afterwards). The
presenter's `applyBrightness` persists first and then applies to a real activity — its own context
when it is an activity, otherwise `MotherActivity.getResumedActivity()` — because the application
context has no window and the old code was a silent no-op. `KidsScreenHelper.setBrightness` had no
callers left and was deleted.

> **Status (2026-11-20):** commit `7c8a7afd3` on `kids`, on top of the upstream-32.80 merge
> `7187a6f3c`; CI "Build Debug APK" run
> [44780556550](https://github.com/mastternick/SmartTubeKids/actions/runs/44780556550) green →
> build `32.80-kids-102` (lint + build). DEX proof on
> `SmartTubeKids_32.80-kids-102_universal.apk`: `scheduleMorningCheck`, `onMorningCheck`,
> `msUntilMorning`, `showPasswordSticky`, `dismissGatePrompt` present; `onOpenGate`,
> `GATE_OPEN_CELEBRATION_MS`, `sOpenGateTask`, `sOpenGatePending` absent. Installed in place on the
> box (`adb install -r`, previous `32.79-kids-99`) as `versionName=32.80-kids-102`;
> `ACCESS_RESTRICTED_SETTINGS=allow`, `KioskKeyGuardService` enabled **and bound**, no FATAL in
> logcat. **Device smoke of the new rules still PENDING** — the box is asleep in the night gate and
> the parent opens it for the smoke.

### Smoke checklist (needs the parental PIN)

- Car arrival: quota spent, pause ends while the car sits at lamp 10 — the screen **stays** on the
  car, the playlist never appears.
- BACK without a PIN: one BACK after the boundary releases the car/classic gate, and the next clip
  plays with the fresh quota.
- Mash before arrival: ten quick escape presses inside the pause show the PIN dialog; a wrong PIN
  keeps it; BACK on the prompt does **not** dismiss it.
- Cat mash + PIN: with the night art the boundary changes nothing; only the mash + PIN (or 06:00)
  releases it.
- Morning auto-exit: leave the cat on screen past 06:00 — it opens by itself and the quota is fresh.
- Restart under the cat: kill the app at 02:00, reopen — the cat is back (no playlist), and 06:00
  still releases it.
- Restart during a **daytime** pause: spend the quota at 15:00 with a 60 min interval, restart the
  app at 15:10 — the gate/pause must still be there (no full fresh quota), and the cat must **not**
  appear at 23:00 for that afternoon exhaustion.
- Prompt over a release: open the PIN prompt (mash) and let the morning boundary pass — the prompt
  must disappear with the gate, not float over the playlist with a dead BACK.

**Known residual (accepted, pre-existing class):** a pause anchored *inside* the night window whose
interval already elapsed, followed by a process restart while the clock is still night, restores the
cat for a window that is objectively fresh (the persisted tuple cannot tell "the night gate was up"
from "the pause was anchored at night"). Fixing that needs a persisted scene flag; the morning
boundary still releases it.

## Upstream sync (official SmartTube → kids)

`kids` was synced to the official release tag **32.79** (`ecaab71…`): 191 upstream commits merged,
19 conflicts resolved by policy — upstream's submodule pins, upstream wholesale for the files with
no KIDS code, ours-plus-upstream for `MotherActivity` and `MainApplication`, upstream verbatim plus
the KIDS deltas in `smarttubetv/build.gradle` (applicationId, flavours, APK naming, no
crash-reporting libs), upstream base plus the 47 KIDS keys in `values-ro`. One semantic conflict was
fixed by hand: `VideoLoaderController` got upstream's live-SABR dispatch back (our pre-base
`!formatInfo.isLive()` guard made the SABR-live preference selectable but unusable).

**It repeats by itself.** `.github/workflows/upstream-sync.yml` runs daily at 04:17 UTC (and on
demand): it takes the newest **release tag** (not the moving master tip), skips when `kids` already
contains it, trial-merges, and

- **conflicts** → merge aborted, the conflicting paths posted in an `upstream-sync` issue (deduped by
  title) and the run fails — the workflow never resolves anything by itself;
- **clean merge** → post-merge invariants (applicationId, KIDS strings, the KIDS/Kiosk sources,
  `KIOSK.md`), commit, fast-forward push to `kids` guarded against a branch that moved meanwhile, then
  `CI.yml` is **dispatched explicitly** for that exact commit (a `GITHUB_TOKEN` push cannot trigger a
  push workflow, `workflow_dispatch` can) and watched; a red build opens an issue and fails the run —
  nothing is reverted automatically.

**Default branch is `kids`** (switched 2026-11-19): scheduled workflows only run from the default
branch, so keeping it on the stale `master` snapshot meant a copy of this file had to be mirrored there.
`master` still carries one mirror commit for the period before the switch; it is inert now.


## Exiting kiosk

- Leave the app (parent): press BACK on the main screen and enter the PIN. With
  the key guard on, HOME is dead for the parent inside the app too — but after a
  PIN exit the app **stays out** until it is opened again, so HOME and the
  launcher work normally while the parent is outside; opening the app re-locks it
  at once. Inside it, the ways out are this BACK+PIN path and turning kiosk off.
- Turn the lock off: Settings → Kids Mode → enter PIN → switch **Kiosk mode** off.
  Everything (pin, HOME override, allowlist) is removed immediately. The key guard
  service may stay enabled — it goes inert with kiosk OFF.

---

# Mod kiosk (română)

Modul kiosk blochează ieșirea copilului din aplicație. Din **v1.6 blocarea reală pe
TV o face paznicul de taste** (`KioskKeyGuardService`, un filtru de taste
Accessibility activat o singură dată de părinte): HOME, RECENTS și tasta de
căutare/microfon sunt înghițite la nivel de sistem, deci nu se pot deschide
launcherul sau alte aplicații. Tastele proprii ale asistentului (`ASSIST`) sunt
decise de sistem înainte de orice filtru de taste — pentru microfonul complet,
dezactivează aplicația de asistent (vezi mai jos). Screen pinning-ul
de sistem (v1.2.8) rămâne ca al doilea strat acolo unde dispozitivul îl suportă —
majoritatea cutiilor Android TV 14 îl țin dezactivat, exact de asta există
paznicul (fără el soft lock-ul se degradează la „doar BACK e blocat"). Din v1.4 un
**paznic de ecran** (serviciu foreground) acoperă launcherul și readuce aplicația
pe ecran în fiecare secundă ori de câte ori o pierde. Tasta BACK iese din aplicație
doar după PIN-ul corect, iar cu Device Owner aplicația poate porni direct după
repornirea TV-ului. Dezactivarea se face din
**Setări → Kids Mode → (PIN) → „Kiosk mode"**.

- **Blocarea reală fără ADB (paznicul de taste, v1.6):** activează **Kiosk mode** în
  Kids Mode → aplicația deschide automat **Setări → Accesibilitate** → bifează
  **„Paznic de taste kiosk SmartTube Kids"** (pe Android 13+ aplicațiile instalate
  manual pot cere întâi App info → ⋮ → *Allow restricted settings*). Apoi apasă
  BACK — aplicația revine singură (fereastra de grație de 5 minute ține paznicul
  liniștit în timpul configurării). Paznicul nu face nimic când kiosk e oprit, deci
  poate rămâne activat permanent. Alternativă ADB (pe Android 13+: acordă
  app-op-ul de restricted settings, apoi adaugă serviciul fără a suprascrie
  alte servicii de accesibilitate existente):
  ```bash
  PKG="app.smarttubekids" # sau "app.smarttubekids.stable" / "app.smarttubekids.fdroid"
  adb shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow
  adb shell "cur=\$(settings get secure enabled_accessibility_services) && target='$PKG/com.liskovsoft.smartyoutubetv2.common.misc.KioskKeyGuardService' && case \":\$cur:\" in *:\$target:*) val=\"\$cur\";; '::'|':null:') val=\"\$target\";; *) val=\"\$cur:\$target\";; esac && settings put secure enabled_accessibility_services \"\$val\" && settings put secure accessibility_enabled 1"
  ```
  (alte variante: `app.smarttubekids.stable` / `app.smarttubekids.fdroid`.
  Alternativ, citește întâi cu `adb shell settings get secure enabled_accessibility_services`:
  dacă e gol sau null, setează ținta direct; dacă există deja alte servicii, adaugă
  la sfârșit cu două puncte `:app.smarttubekids/...` în loc să suprascrii.)
- **Recomandat (blocare totală, cu ADB):** conectează TV-ul prin ADB din rețea și
  rulează, imediat după instalare și **înainte** de a deschide aplicația:
  ```bash
  adb shell dpm set-device-owner app.smarttubekids/com.liskovsoft.smartyoutubetv2.common.misc.KioskDeviceAdminReceiver
  ```
  Apoi activează PIN-ul și Kiosk mode în setările Kids. Fără mesaje de confirmare,
  copilul nu poate ieși; BACK cere PIN-ul pentru ieșire. (Paznicul de taste poate fi
  activat și aici — pentru tasta de căutare/mic; tastele `ASSIST` ale asistentului
  se închid prin Lock Task sau prin dezactivarea aplicației de asistent.)
- **Fără paznic (doar pinning + paznicul de ecran):** ecranul curent este fixat de
  sistem (screen pinning) — HOME/RECENTS sunt blocate **doar dacă** dispozitivul
  suportă pinning, iar clipurile, Setările și dialogurile funcționează pentru că
  fixarea se eliberează chiar înainte de fiecare navigare internă și se reaplică
  automat pe ecranul nou. BACK cere PIN-ul; doar PIN-ul corect scoate aplicația.
  Iar din v1.4, dacă aplicația pierde ecranul oricum (combo-ul BACK+HOME ținut sau
  fereastra de deblocare), **paznicul de ecran** (`KioskWatchdogService`, serviciu
  foreground) o repornește în fiecare secundă și acoperă launcherul cu un ecran
  „întorcem în aplicație…" care absoarbe toate apăsările de taste — copilul nu
  poate folosi nicio altă aplicație, doar vede acoperirea ~1–2 secunde până revine
  SmartTubeKids. Fără PIN setat, BACK rămâne complet blocat. (Permisiunea
  „suprapunere peste alte aplicații" este acordată automat de Android TV la
  instalare; dacă un dispozitiv o refuză, repornirea automată continuă fără
  acoperire. Siguranță anti-blocare: dacă aplicația nu reușește deloc să revină pe
  ecran timp de ~45 s, paznicul eliberează ecranul.)
- Ieșirea părintelui: BACK pe ecranul principal + PIN. Cu paznicul de taste pornit,
  HOME este mort și pentru părinte în interiorul aplicației — dar după o ieșire cu
  PIN aplicația **rămâne afară** până când este deschisă din nou (eliberarea este
  persistată, KIDS v1.7, pentru că ieșirea închide procesul, iar sistemul
  re-leagă paznicul într-un proces nou; fereastra de grație doar în memorie era
  pierdută, iar paznicul readucea aplicația imediat după PIN-ul corect). HOME și
  launcherul funcționează normal cât timp părintele este afară; la redeschidere
  aplicația se încuie imediat. În interior, singurele ieșiri sunt BACK+PIN sau
  oprirea kiosk-ului. Serviciul de accesibilitate poate rămâne activat: devine
  inert când kiosk este oprit. După o ieșire cu PIN, aplicația rămâne afară și peste
  o repornire a TV-ului; opțiunea „Re-încuie kiosk după repornire" din Kids Mode face
  ca un reboot real să re-încuie automat (paznicul / Device Owner readuc aplicația pe
  ecran). Trecerea în standby nu este o repornire și nu re-încuie niciodată.
- Activează **PIN-ul** înainte de kiosk — fără PIN nu există ieșire din aplicație
  cu BACK, altfel copilul poate opri și comutatorul din Kids Mode.
- Anularea device owner (dacă e nevoie): comanda `adb shell dpm remove-active-admin ...`
  de mai sus, sau oprește comutatorul din aplicație.

### Verificarea paznicului pe dispozitiv (dacă HOME tot scapă)

Paznicul filtrează taste **doar cât timp kiosk este pornit** (și în afara unei
ieșiri aprobate) — cu kiosk oprit, toate tastele trec, prin design. Dacă HOME tot
deschide launcherul, verifică în ordine:

> **Întâi, comutatorul care se debifează singur (Android 13+).** Dacă bifa de la
> Accesibilitate se pune singură și apoi revine, setările restricționate ale
> platformei pot bloca serviciul instalat manual. Aplicația nu se poate reactiva
> automat — este necesară permisiunea utilizatorului sau configurarea prin ADB.
> Remediere manuală: **App info → ⋮ → *Allow restricted settings*** (⋮ este
> meniul din colțul dreapta-sus al ecranului App info), apoi bifează din nou
> paznicul. Nu există acest meniu pe build-ul tău? Deblochează app-op-ul prin ADB
> și adaugă serviciul păstrând celelalte servicii active:
> ```bash
> PKG="app.smarttubekids" # sau "app.smarttubekids.stable" / "app.smarttubekids.fdroid"
> # verifică starea curentă (de regulă "ACCESS_RESTRICTED_SETTINGS: deny"):
> adb shell appops get "$PKG" | grep -i restrict
> # deblochează op-ul (numele este CASE-SENSITIVE):
> adb shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow
> # adaugă paznicul fără a suprascrie alte servicii existente:
> adb shell "cur=\$(settings get secure enabled_accessibility_services) && target='$PKG/com.liskovsoft.smartyoutubetv2.common.misc.KioskKeyGuardService' && case \":\$cur:\" in *:\$target:*) val=\"\$cur\";; '::'|':null:') val=\"\$target\";; *) val=\"\$cur:\$target\";; esac && settings put secure enabled_accessibility_services \"\$val\" && settings put secure accessibility_enabled 1"
> ```

1. **Nu e activat:** `adb shell settings get secure enabled_accessibility_services`
   trebuie să conțină `app.smarttubekids/...KioskKeyGuardService`.
2. **Activat dar nelegat de sistem:** `adb shell dumpsys accessibility | grep -A5 KioskKeyGuardService`.
3. **Legat, dar ROM-ul ignoră filtrul:** `adb logcat -s KioskKeyGuardService`.
   Apasă HOME **de pe telecomandă** — `input keyevent` NU e un test valid: tastele
   injectate prin ADB intră direct în dispatcher și ocolesc filtrul de accesibilitate,
   așa că launcherul se deschide fără nicio linie `swallowed key` chiar și cu un
   paznic perfect sănătos. Verifică și că nu rulează fereastra de grație: pornirea
   comutatorului kiosk fără paznic activat (și scurtăturile „Open App info" / „Deschide
   din nou Accesibilitatea" din dialogul de revenire) armează 5 minute în care toate
   tastele trec prin design (paznicul iese silențios, tot fără log). Fereastra se
   încheie mai devreme când aplicația revine din Accesibilitate cu paznicul activat.
   Verdictul de mai jos e valid doar cu apăsare reală și fără grație. Dacă
   scrie „swallowed key 3" iar launcherul tot se deschide, build-ul vendor nu aplică
   filtrarea de taste de accesibilitate → folosește Device Owner (Opțiunea A), care
   blochează HOME/RECENTS direct în window manager.

Util: `adb logcat | grep "Screen pinning not engaged"` confirmă no-op-ul
screen pinning-ului pe cutiile Android TV 14 (cauza pentru care există paznicul).

**Doar butonul de microfon/asistent.** Ce tastă trimite telecomanda ta? Ține apăsat
butonul de mic și urmărește logul:
```bash
adb logcat -s KioskKeyGuardService
```
- `swallowed key 84` → telecomanda trimite `KEYCODE_SEARCH`: paznicul îl acoperă.
- Nimic în log și asistentul tot se deschide → trimite `KEYCODE_ASSIST` (219) /
  `KEYCODE_VOICE_ASSIST` (231). Acestea sunt tratate în
  `PhoneWindowManager.interceptKeyBeforeQueueing`, *înainte* de orice filtru de
  taste, deci nicio aplicație nu le poate opri — dezactivează aplicația de asistent:
  ```bash
  adb shell pm list packages | grep -i assist     # de obicei com.google.android.katniss
  adb shell pm disable-user --user 0 <pachet-asistent>
  ```
  (sau folosește Device Owner, unde Lock Task refuză pornirea asistentului).
