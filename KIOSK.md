# Kiosk mode (SmartTubeKids)

Kiosk mode blocks the child from leaving the app: HOME / RECENTS keys stop working
(system screen pinning — v1.2.8 — even without ADB), the BACK key only exits after
the correct PIN, and with the Device Owner setup the app can be relaunched
automatically after a TV reboot. Since v1.4 a **screen guardian** (foreground
service) additionally covers the launcher and pulls SmartTubeKids back on screen
every second whenever the app ever loses it — so HOME, RECENTS and the unpin combo
cannot put the child into another app. The lock is turned off from
**Settings → Kids Mode → (PIN) → "Kiosk mode (block leaving the app)"**.

There are two levels of protection:

| | Full lock (recommended) | Soft lock (no setup) |
|---|---|---|
| Requires | One-time ADB command (Device Owner) | Nothing |
| Confirmation prompts | None, silent lock | None |
| HOME / RECENTS | Blocked | Blocked on the current screen (system screen pinning); if any window ever opens (unpin cycle, BACK+HOME combo), the v1.4 guardian covers the launcher and brings the app back ~1 s later |
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

> **Exit with PIN (v1.2.8).** On the app's root screen BACK opens the PIN dialog
> (KidsPinGate — one PIN entry per parent session). The correct PIN drops the pin
> and exits the app, with a 15 s grace window so the soft lock doesn't pull the app
> back. Without a PIN set, BACK stays blocked in both modes.

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

Just enable **Kiosk mode** in Kids Mode settings. The current screen is pinned by
the system, so HOME / RECENTS do nothing, while clips, Settings and dialogs keep
working: the pin is released moments before every internal navigation and
re-applied on the new screen. BACK asks for the PIN — only the correct PIN exits
the app. If the child uses the system unpin combo (BACK+HOME hold) or the pin
window ever opens, the **v1.4 screen guardian** takes over: a foreground service
relaunches the app every second and, while it isn't on top yet, a fullscreen
cover hides and swallows input on whatever is behind (the launcher, another app).
The child never operates another app — only the cover is briefly visible.
Still weaker than Option A (a ~1–2 s visual window exists while the guardian
climbs back), but no escape is sustained. Set the Kids **PIN** too, otherwise
BACK has no exit path at all. Use Option A whenever possible.

> **Guardian details (v1.4, `KioskWatchdogService`).** Armed from
> `MotherActivity.onStop` (`KioskModeManager.scheduleReentry`), idles/stops when
> the app is on screen again, kiosk is switched off, playback is in PIP, the
> screen is off, or the parent left with the PIN (15 s grace). It needs the
> *Draw over other apps* permission, which Android TV grants automatically at
> install (`SYSTEM_ALERT_WINDOW` is a normal permission on TV); if a device
> refuses it, the climb-back loop still runs without the visual cover. As a
> broken-install safety valve it stops covering after ~45 s of failed relaunches,
> so a TV can never be left unusable by the guardian itself.

## Exiting kiosk

- Leave the app (parent): press BACK on the main screen and enter the PIN.
- Turn the lock off: Settings → Kids Mode → enter PIN → switch **Kiosk mode** off.
  Everything (pin, HOME override, allowlist) is removed immediately.

---

# Mod kiosk (română)

Modul kiosk blochează ieșirea copilului din aplicație: tastele HOME / RECENTS nu mai
funcționează (screen pinning de la sistem — v1.2.8 — chiar și fără ADB), tasta BACK
iese din aplicație doar după PIN-ul corect, iar cu Device Owner aplicația poate
porni direct după repornirea TV-ului. Dezactivarea se face din
**Setări → Kids Mode → (PIN) → „Kiosk mode"**.

- **Recomandat (blocare totală):** conectează TV-ul prin ADB din rețea și rulează,
  imediat după instalare și **înainte** de a deschide aplicația:
  ```bash
  adb shell dpm set-device-owner app.smarttubekids/com.liskovsoft.smartyoutubetv2.common.misc.KioskDeviceAdminReceiver
  ```
  Apoi activează PIN-ul și Kiosk mode în setările Kids. Fără mesaje de confirmare,
  copilul nu poate ieși; BACK cere PIN-ul pentru ieșire.
- **Fără ADB (soft lock):** ecranul curent este fixat de sistem (screen pinning) —
  HOME/RECENTS sunt blocate, iar clipurile, Setările și dialogurile funcționează
  pentru că fixarea se eliberează chiar înainte de fiecare navigare internă și se
  reaplică automat pe ecranul nou. BACK cere PIN-ul; doar PIN-ul corect scoate
  aplicația. Iar din v1.4, dacă aplicația pierde ecranul oricum (combo-ul
  BACK+HOME ținut sau fereastra de deblocare), **paznicul de ecran**
  (`KioskWatchdogService`, serviciu foreground) o repornește în fiecare secundă
  și acoperă launcherul cu un ecran „întorcem în aplicație…" care absoarbe toate
  apăsările de taste — copilul nu poate folosi nicio altă aplicație, doar vede
  acoperirea ~1–2 secunde până revine SmartTubeKids. Fără PIN setat, BACK rămâne
  complet blocat. (Permisunea „suprapunere peste alte aplicații" este acordată
  automat de Android TV la instalare; dacă un dispozitiv o refuză, repornirea
  automată continuă fără acoperire. Siguranță anti-blocare: dacă aplicația nu
  reușește deloc să revină pe ecran timp de ~45 s, paznicul eliberează ecranul.)
- Activează **PIN-ul** înainte de kiosk — fără PIN nu există ieșire din aplicație
  cu BACK, altfel copilul poate opri și comutatorul din Kids Mode.
- Anularea device owner (dacă e nevoie): comanda `adb shell dpm remove-active-admin ...`
  de mai sus, sau oprește comutatorul din aplicație.
