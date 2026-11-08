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
activity decor (`MileMarkerOverlay`): a dark road with ten **numbered markers
(1–10)**, each flanked by street-light poles. Nothing about the gate changes —
keys are still swallowed and the PIN dialog still needs 10 escape presses, each within
2 s of the previous one.
The overlay NEVER releases the lock by itself.

**KIDS v1.9.4: quota gate auto-opens at reset boundary; KIDS v1.9.5: that boundary is a real pause;
KIDS v1.9.7: the pause only starts once the quota is used up.**
The quota gate (force stop path) schedules a one-shot task at the end of the cooldown pause — and only once the
quota is exhausted (no pause is running before that). When the
pause ends, the fresh quota is written and the gate releases **without a PIN** — Back and Home work again.
Lock-at-end mode is deliberately excluded: it still requires the PIN.

KIDS v1.9.5 replaced the clock-aligned grid with **cooldown-from-exhaustion** semantics, and KIDS v1.9.7
made the reset interval a **pause that only starts when the watch limit is reached** — it is not a rolling
window at all. Until the quota is exhausted nothing resets: the child's budget keeps being spent down across
sessions, however many days that takes, because `KidsQuotaWindow.isWindowExpired` returns false for any stored
anchor while `used < limit` **or while the `inCooldown` flag is clear** — elapsed time alone never proves a pause
is running. An anchor without the flag (the parent lowered the timer below the already-counted `used`, or the
process died between the counter save and the anchor save in `accumulatePlayTime`) is not a boundary: no rollover,
and `anchorCooldownIfNeeded()` writes a proper pause anchor on the next check, so a false "fresh quota" can never
appear. The anchor means nothing before exhaustion and the PAUSE anchor is written at the moment the
quota runs out (`KidsModeController.anchorCooldownIfNeeded()`, called both from the counter
(`accumulatePlayTime`) and from the `isTimeExpired` gate, once per exhaustion thanks to the persisted
`inCooldown` flag, blob index 24), so the pause is exactly `[T, T + interval)` from that instant.
`KidsModeData.resetWindow()` does store a timestamp at rollover as well; it is simply not a pause
boundary without the flag, and `isWindowExpired` never treats it as one. The rollover —
in the controller (`resetWindowIfNeeded`) and in the gate one-shot (`KidsTimeUpLock.scheduleRolloverCheck`) —
then writes the fresh quota and starts the next window at `now`. Because a scheduled check always has an
exhaustion anchor, the gate one-shot always waits a full interval from exhaustion: no boundary exists before
that, and `scheduleRolloverCheck` refuses to schedule at all while `used < limit` or `inCooldown` is clear
(otherwise the one-shot would fire into `onRolloverCheck`, see "not expired yet" and re-post itself every
`ROLLOVER_MIN_MS`). The car plus the gate
read the SAME stored anchor, so the Mile Marker car reaching lamp 10 and the gate opening land on the same
instant. A hand-edited or corrupted blob whose anchor is 0 still recovers: the `ROLLOVER_MIN_MS` clamp fires
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
was idle, or the kiosk branch leaves it to the next play), the helper performs the FULL rollover right there:
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
  screen — only the timer path passes the pref.
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

## Time-up screen: PIN or straight out (KIDS v1.9.6)

When the watch limit runs out the screen is a gate: every key is swallowed and nothing is
shown. Ten quick escape presses earn the way out. Two things are configurable.

### Ask for the PIN after 10 presses (`Ask for the PIN after 10 presses`, default ON)

- **ON (default):** the ten presses open the PIN dialog. Only the correct PIN releases the
  gate — same behaviour as every earlier build.
- **OFF:** the ten presses release the gate directly, no dialog. The presses themselves are
  the proof that a grown-up is there.

**Kiosk mode always wins.** With kiosk enabled the PIN is required in both cases: the gate
never opens the app without it. Turning the switch off only ever affects a device with kiosk
disabled.

The same rule applies to the automatic release: when the cooldown pause ends (i.e. once the quota has been
used up and the configured interval has elapsed), the gate opens
by itself **only if kiosk is off**. With kiosk on, the reset still replenishes the quota (so
the next clip is not insta-locked) but the screen stays behind the PIN.

### What counts as "10 quick presses"

One bound (`KidsTimeUpKeyGate.GAP_MAX_MS`): **each press within 2 s of the previous one**
(KIDS v1.9.6). Ten presses at 1.5 s intervals count (9 gaps = 13.5 s total); a press that
arrives more than 2 s after the previous one restarts the sequence, so a child pressing once
every few seconds can never accumulate a count.

The earlier build also required all ten presses inside 10 s measured from the first one. That
outer bound is gone: it silently rejected a steady 1.5 s mash and made the real requirement
much stricter than "no pause longer than 2 s".

Counted keys stay BACK / HOME / RECENTS / ESCAPE. The mic / assistant keys are never counted.

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

- The **app-op** is the part that survives reboots and app updates; without it every other
  command is undone at the next start. Lowercase `access_restricted_settings` fails with
  `Unknown operation string` — the name is case-sensitive.
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
