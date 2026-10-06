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
   inert whenever kiosk is OFF, so it can stay enabled forever. ADB alternative:
   ```bash
   adb shell settings put secure enabled_accessibility_services app.smarttubekids/com.liskovsoft.smartyoutubetv2.common.misc.KioskKeyGuardService
   adb shell settings put secure accessibility_enabled 1
   ```
   (other flavors: `app.smarttubekids.stable` / `app.smarttubekids.fdroid`. The
   value replaces the whole enabled-services list, so do this only on a TV that
   doesn't already use another accessibility service.)

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

### Verifying the key guard on the device (if HOME still leaks)

The guard filters keys **only while kiosk mode is ON** and outside an approved
exit — with kiosk OFF every key passes through by design. If HOME still reaches
the launcher, check the three failure modes in order:

> **First, the switch that ticks itself off (Android 13+).** If the accessibility
> toggle checks itself and then reverts, the platform's *restricted settings*
> protection is blocking a side-loaded app: accessibility services cannot be
> enabled until the user explicitly allows it. The app (v1.7.2) detects the failed
> setup trip and shows a dialog with both shortcuts. Manual fix: **App info →
> ⋮ → *Allow restricted settings*** (⋮ is the overflow menu, top-right of the App
> info screen), then tick the guard again. No such menu on your build? Install or
> update the APK over ADB (`adb install -r app.apk`) — ADB-installed apps are
> exempt — or write the service straight into the secure setting with the
> `settings put` commands above (that path bypasses the Settings UI gate).

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
  poate rămâne activat permanent. Alternativă ADB:
  ```bash
  adb shell settings put secure enabled_accessibility_services app.smarttubekids/com.liskovsoft.smartyoutubetv2.common.misc.KioskKeyGuardService
  adb shell settings put secure accessibility_enabled 1
  ```
  (alte variante: `app.smarttubekids.stable` / `app.smarttubekids.fdroid`; comanda
  înlocuiește întreaga listă de servicii active, folosește-o doar pe un TV care nu
  are deja alt serviciu de accesibilitate pornit.)
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
> Accesibilitate se pune singură și apoi revine, te blochează protecția
> *restricted settings* a platformei: serviciile de accesibilitate nu pot fi
> activate pentru o aplicație instalată manual până nu le permite utilizatorul
> explicit. Aplicația (v1.7.2) detectează ieșirea nereușită și afișează un dialog
> cu ambele scurtături. Remediere manuală: **App info → ⋮ → *Allow restricted
> settings*** (⋮ este meniul din colțul dreapta-sus al ecranului App info), apoi
> bifează din nou paznicul. Nu există acest meniu pe build-ul tău?
> Instalează/actualizează APK-ul prin ADB (`adb install -r app.apk`) — aplicațiile
> instalate prin ADB sunt exceptate — sau scrie serviciul direct în setarea
> securizată cu comenzile `settings put` de mai sus (acea cale ocolește poarta din
> interfața Setărilor).

1. **Nu e activat:** `adb shell settings get secure enabled_accessibility_services`
   trebuie să conțină `app.smarttubekids/...KioskKeyGuardService`.
2. **Activat dar nelegat de sistem:** `adb shell dumpsys accessibility | grep -A5 KioskKeyGuardService`.
3. **Legat, dar ROM-ul ignoră filtrul:** `adb logcat -s KioskKeyGuardService` — dacă
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
