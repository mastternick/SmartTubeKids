# Kiosk mode (SmartTubeKids)

Kiosk mode blocks the child from leaving the app: HOME / RECENTS keys stop working,
notifications are hidden, the BACK key never exits, and the app can be relaunched
automatically after a TV reboot. The only way out is the PIN-protected switch:
**Settings → Kids Mode → (PIN) → "Kiosk mode (block leaving the app)"**.

There are two levels of protection:

| | Full lock (recommended) | Screen pinning (no setup) |
|---|---|---|
| Requires | One-time ADB command (Device Owner) | Nothing |
| Confirmation prompts | None, silent lock | System prompt on (re)lock |
| HOME / RECENTS | Blocked | Blocked while pinned |
| After TV reboot | App launches directly, re-locks itself | Normal launcher first; app re-asks to pin when opened |
| Child can escape? | No (only the PIN switch) | Possible: hold BACK to unpin, or ignore the pin prompt |

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

## Option B — Screen pinning (no ADB)

Just enable **Kiosk mode** in Kids Mode settings. When you close the settings, Android
shows a screen-pinning confirmation — accept it. The app stays locked until someone
holds BACK (and RECENTS) and confirms the unpin dialog, so it is weaker protection:
a determined older child can escape, and after a reboot the pin prompt must be
accepted again. Use Option A whenever possible.

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
- **Fără ADB:** activează doar comutatorul Kiosk — Android va cere o confirmare de
  „fixare a ecranului" (screen pinning). Protecția e mai slabă: se poate ieși cu
  BACK apăsat lung.
- Activează **PIN-ul** înainte de kiosk, altfel copilul poate opri comutatorul.
- Anularea device owner (dacă e nevoie): comanda `adb shell dpm remove-active-admin ...`
  de mai sus, sau oprește comutatorul din aplicație.
