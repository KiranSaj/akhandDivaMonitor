# Diya Monitor v2 — Design

Status: **approved 2026-10-17**. Everything runs on the phone (no cloud AI).

- **Target phone:** Samsung Galaxy A9 2018 (SM-A920F), Android 10.
- **Lamp:** open brass bowl, 2 wicks (both always lit), copper coil wick holders.
  A full bowl lasts on average **9–10 h, with about 1 h of buffer**; it is serviced (wick cleaned/trimmed, oil refilled) roughly every 10 h.
- **Delivery:** GitHub Actions builds the APK → the app's own **Check for update** installs it → Telegram carries alerts, commands and logs.

---

## 1. Alerts

| Level | Alert | Trigger (initial values, tuned from night logs) | At night |
|---|---|---|---|
| 🚨 Critical | **Both flames out** | both flames < 10 % of normal size for 20 s | 🔊 repeats every 5 min until "I'm on it" |
| 🚨 Critical | **Unexpected flame / fire** | a new flame-like bright blob that is not one of the wicks (petal or cloth burning, wick fallen into the oil) | 🔊 repeats |
| 🔴 High | **One flame out** | one flame out for 30 s while the other burns | 🔊 |
| 🔴 High | **Flame about to die** | one flame < 25 % of normal for 2 min | 🔊 |
| 🔴 High | **Oil very low — service now** | estimated oil ≤ 10 % (≈ 1 h left), or ≤ 20 % while both flames are shrinking | 🔊 |
| 🟠 Warning | **Oil getting low** | estimated oil ≤ 25 % | 🔕 silent |
| 🟠 Warning | **Wick needs trimming** | one flame ≥ 40 % smaller than normal for 10 min while the other is fine, or a flame turning redder and flickering | 🔕 |
| 🟠 Warning | **Camera or lamp moved** | scene changed; the app re-finds the flames and recalibrates itself, and warns only if it cannot | 🔕 |
| 🟡 Info | flame too large, draft or flicker, recovered, heartbeat | as in v1 | 🔕 |
| 🔴/🟠 | phone health | charger, battery, heat, camera stalled, app crashed (as in v1) | charger and crash alerts make sound |

- **Night hours:** 22:00–07:00 (configurable). Critical and High alerts make a sound; everything else is sent silently (`disable_notification`).
- **Morning summary** at the end of night hours: burn time, incidents, flame-size trend for each flame, oil estimate.
- Every Telegram message carries **"🕒 HH:MM"**, plus **"(delayed N min)"** if the network held it back.
- A **new** urgent incident (e.g. the flame goes out again after a relight) alerts immediately; the cooldown only throttles reminders.

## 2. Finding the flames automatically

You no longer draw boxes.

1. **Exposure lock.** At Start the camera lowers exposure (≈ −1.5 EV) and then locks exposure and white balance. This makes flames stand out, reflections get dimmer and readings stay stable. Alert photos come out darker; that is accepted.
2. **Detection.** The app looks for connected regions that are very bright and warm-coloured, taller than wide, and flickering. It keeps the expected number of them (a setting, default 2), ranked by having a saturated core and flickering independently. Brass and oil reflections are rejected because they have no saturated core, their flicker copies a real flame, and they sit below or beside it.
3. **Confirmation.** The detected flames are shown on screen and sent to Telegram ("Found 2 flames ✓"). A single tap on the right spot corrects a wrong pick.
4. **Tracking.** Each frame searches near each flame's last position, so small drifts are followed automatically.
5. **Scene-change check.** A small background thumbnail (excluding the flames) is compared with a reference. A large change means the camera or lamp moved: the app re-finds the flames and recalibrates. If the whole frame brightens at once, a room light was switched on: alerts pause, exposure is re-locked, and it is never treated as fire.
6. **Learning what normal looks like.** "Normal" for each flame is learned during the first 10 min after Start or Serviced, once the flames have settled.

## 3. Oil and wick carbon

Wick carbon and low oil both shrink the flame, and they happen at similar times in a burn, so the flame alone cannot always tell them apart.

- **Oil estimate is time-based and adjusted by flame size.** Oil used builds up in proportion to how big the flames are: bigger flames use oil faster. *Expected burn time* is a setting, **default 10 h** (range 8–14 h).
  `remaining = 100 % − Σ(average flame-size ratio × Δt) / expected burn time`.
- **It learns a little each night.** When you choose "Oil refilled", the app asks how much was left (*Plenty / About a quarter / Almost empty*) and nudges *expected burn time* by about 5 %.
- **Carbon is detected per wick.** One flame shrinking while the other stays fine means carbon on that wick.
- **Both flames shrinking late in the burn** produces one alert: "🪔 Lamp needs service — both flames shrinking, oil ~15 % (est.)", since the action is the same either way.
- **Serviced** (an in-app button or the `/serviced` Telegram buttons): *Wick cleaned* / *Oil refilled* / *Both*. Each choice resets the matching timer and re-learns what normal looks like.

## 4. Screen auto-off

Instead of keeping the screen on all night:

- **Setting: "Turn screen off after"** — *Never* / 1 / 2 / 5 / 10 min (default 2 min). Once monitoring is running, the app stops holding the screen on after that delay and the phone's own screen timeout turns it off and locks it.
- Monitoring keeps running with the screen off: the camera belongs to the foreground service, not to the screen.
- **Safety net.** If the camera stops delivering frames after the screen goes off (some Samsung builds pause cameras), the existing watchdog notices within about 60 s. It wakes the screen, restarts the camera, switches to *keep screen on* (black, minimum brightness) for the rest of the night, and sends a Telegram notice. The next night it tries screen-off again, and after two failures it stays in screen-on mode and says so in Settings.
- The lock screen does not affect Telegram commands, alerts or `/status`.

## 5. Telegram commands

These are polled with `getUpdates` and accepted only from the configured chat ID.

| Command or button | What it does |
|---|---|
| `/status` | photo, the state of each flame, oil estimate, battery |
| `/photo` | photo only |
| `/serviced` | buttons: Wick cleaned / Oil refilled / Both |
| `/log` | last night's log (compressed CSV plus snapshots) |
| **I'm on it** (button on critical alerts) | stops the repeats for this incident |

## 6. Night log

- One row per check (every 2 s): time; area, height, brightness, warmth and flicker for each flame; oil estimate; active alerts; battery % and °C. About 1–2 MB per night, gzipped before sending.
- A snapshot every 15 min and at every alert. The phone keeps 7 nights.
- Sent through `/log` or the **Send log** button. Save the file into this repo and use it to tune the thresholds.

## 7. In-app updates

- CI sets `versionCode` = GitHub run number. A `v*` tag sets `versionName` and publishes a GitHub Release; other builds are named `0.0.0-dev.N`.
- **Settings → Check for update** queries the latest GitHub release (the repo is public, so no login is needed), shows the version and its notes, downloads the APK, checks its size, and opens Android's installer. You tap **Install**. Android asks once to "Allow from this source".
- The app also checks once a day when it is opened. When a new version appears, it shows a notice and sends a Telegram message once per version.
- Updates are **blocked while monitoring is running**, because installing restarts the app.
- Settings are kept because every build is signed with the same key.

## 8. Samsung checks at Start

- Verify that battery optimisation is off for the app and that the charger is plugged in.
- A one-time checklist: turn off **Device care → Auto optimisation → auto restart**, and make sure the app is not listed under Sleeping or Deep sleeping apps.

## 9. Phases

| Phase | Contents |
|---|---|
| **0** | In-app updater; CI-set version numbers; immediate alert for a new urgent incident; message timestamps. *Install manually once; after that, update from inside the app.* |
| **1** | Exposure lock, auto-detecting and tracking 2 flames, per-flame readings, scene-change check, **screen auto-off**, night log and `/log`. |
| **2** | All alerts in §1, night rules, oil estimate, Serviced flow, morning summary. Thresholds come from Phase 1 logs. |
| **3** | Telegram commands and the "I'm on it" button. |

New alert types first run in **silent trial mode** (sent silently with a `[trial]` tag) for one or two nights. Each phase ships with unit tests on synthetic frames, and is pushed only after approval.

## 10. Risks

- Brass reflections mistaken for flames. Mitigations: lower exposure, the ranking rules, and the one-tap correction.
- False alarms for "unexpected flame" (headlights, a phone screen, a torch). Mitigations: the global-brightness check, requiring persistence, and trial mode.
- The camera pausing with the screen off on Samsung. Mitigation: the §4 safety net.
- An old battery on the charger for 10–12 h every night. Mitigation: the heat alert stays on.
