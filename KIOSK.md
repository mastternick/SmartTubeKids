# Kiosk mode (SmartTubeKids)

Kiosk mode blocks the child from leaving the app: HOME / RECENTS keys stop working,
notifications are hidden, the BACK key never exits, and the app can be relaunched
automatically after a TV reboot. The only way out is the PIN-protected switch:
**Settings → Kids Mode → (PIN) → "Kiosk mode (block leaving the app)"**.

There are two levels of protection:

| | Full lock (recommended) | Soft lock (no setup) |
|---|---|---|
| Requires | One-time ADB command (Device Owner) | Nothing |
| Confirmation prompts | None, silent lock | None |
| HOME / RECENTS | Blocked | App climbs back on screen ~2s after leaving |
| BACK key | Never exits | Never exits |
| Playback / Settings inside the app | Fully working | Fully working |
| After TV reboot | App launches directly, re-locks itself | Normal launcher start; app returns on its own once opened |
| Child can escape? | No (only the PIN switch) | Partially: quick look at the home screen, may keep if Android refuses the return |

> **Why no screen pinning anymore (v1.2.7)?** This app runs every screen in its own
> task (`launchMode=singleInstance`). System screen pinning confines one task, so
> pinning the browse screen made it impossible to start playback or open Settings —
> exactly the bug users reported in v1.2.6. Lock Task is therefore applied only with
> Device Owner (the whole package is allowlisted, all app screens keep working).

Enable the Kids **PIN** before enabling kiosk — otherwise the child can open
Kids Mode settings and turn kiosk off.

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

Just enable **Kiosk mode** in Kids Mode settings. No system dialogs: the BACK key
never exits the app, and when the child presses HOME the app tries to climb back on
screen after ~2 seconds. Weaker protection than Option A — on Android 10+ the system
may refuse the background return, in which case the app simply stays closed until
opened again — but playback and Settings keep working normally inside the app.
Use Option A whenever possible.

## Exiting kiosk

Settings → Kids Mode → enter PIN → turn **Kiosk mode** off. Everything
(lock, HOME override, allowlist) is removed immediately.

---

# Mod kiosk (română)

Modul kiosk blochează ieșirea copilului din aplicație: tastele HOME / RECENTS nu mai
funcționează, BACK nu mai închide aplicația, iar după repornirea TV-ului aplicația
poate porni direct. Se dezactivează doar din **Setări → Kids Mode → (PIN) → „Kiosk
mode"**.

- **Recomandat (blocare totală):** conectează TV-ul prin ADB din rețea și rulează,
  imediat după instalare și **înainte** de a deschide aplicația:
  ```bash
  adb shell dpm set-device-owner app.smarttubekids/com.liskovsoft.smartyoutubetv2.common.misc.KioskDeviceAdminReceiver
  ```
  Apoi activează PIN-ul și Kiosk mode în setările Kids. Fără mesaje de confirmare,
  copilul nu poate ieși.
- **Fără ADB (soft lock):** activează doar comutatorul Kiosk — BACK nu mai iese din
  aplicație, iar după apăsarea HOME aplicația încearcă să revină pe ecran în ~2
  secunde. Pe Android 10+ sistemul poate refuza revenirea din fundal; în rest,
  redarea și Setările funcționează normal în interiorul aplicației.
- Activează **PIN-ul** înainte de kiosk, altfel copilul poate opri comutatorul.
- Anularea device owner (dacă e nevoie): comanda `adb shell dpm remove-active-admin ...`
  de mai sus, sau oprește comutatorul din aplicație.
