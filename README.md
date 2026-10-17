# 🪔 Diya Monitor

Turns an old Android phone into an overnight monitor for a brass oil diya (akhand diya).
The phone's camera watches the flame and sends **Telegram alerts with a photo** when:

| Alert | Rule (vs. calibrated baseline) |
|---|---|
| 🔥❌ **Flame out** (urgent, repeats every cooldown) | bright flame area < 10% for 30 s |
| ⚠️ **Dimming** | flame area or brightness < 60% for 2 min |
| 🛢️ **Oil may be running low** | steady decline of flame area > 30% over a 30-min window (linear regression) |
| ⚠️ **Possible soot / trim the wick** | area > 160%, or heavy flicker (std-dev of area over 30 s ≫ normal), or the zone just above the flame darkens |
| ✅ **Recovered** | the condition has been clear for 30 s |
| 💓 Heartbeat (optional) | every 2 h: "still monitoring, flame OK" |
| 🔋🔌🌡️ Phone health | battery < 20 %, charger unplugged, battery > 45 °C, camera stalled, app stopped/crashed |

Because a brass diya hides the oil level, low oil is *inferred* from the flame slowly shrinking.

Each alert type has a cooldown (default 15 min), so you won't be spammed.

---

## 1. Create the Telegram bot (2 minutes)

1. In Telegram, open **@BotFather** and send `/newbot`.
2. Choose a name (e.g. *Diya Monitor*) and a username ending in `bot`.
3. BotFather replies with a **token** like `123456789:AAH…`. Keep it private.
4. Open your new bot and send it any message (e.g. `hi`).
   *To alert a family group instead, add the bot to the group and send a message in the group.*
5. In the app's **Setup** tab, paste the token and press **Find chat ID**. The app reads your bot's latest message and fills in the chat ID.
   (You can also get it from @userinfobot.)
6. Press **Send test** and check that the message arrives in Telegram.

## 2. Download the APK

**From a release (easiest, no login needed):**
open <https://github.com/KiranSaj/akhandDivaMonitor/releases/latest> and download `diya-monitor-v….apk`.
A release is created automatically whenever a tag such as `v1.0.0` is pushed:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

**From any build (requires being logged in to GitHub):**
open <https://github.com/KiranSaj/akhandDivaMonitor/actions/workflows/build.yml>, click the latest green run,
and under **Artifacts** download **diya-monitor-apk**. It is a zip that contains the APK. Artifacts expire after 90 days.

## 3. Install on the phone

1. Copy the APK to the phone, or open the download link directly on the phone.
2. Tap the APK. Android will ask you to allow installing from this source
   (**Settings → Install unknown apps → your browser / Files app → Allow**).
3. Install it and open **Diya Monitor**. Allow **Camera** (and **Notifications** if asked).
4. In **Setup**, tap **Allow background running** and choose *Allow*. This turns off battery optimisation, which Android would otherwise use to pause the app overnight.
   On Samsung phones, also check **Settings → Battery → Background usage limits** and make sure Diya Monitor is *not* listed under "Sleeping apps".

All builds are signed with the same key, so a newer APK installs over the old one and keeps your settings.

### Updating the app (no laptop needed)

After the first install, update from inside the app:

1. Stop monitoring (installing an update restarts the app).
2. **Settings → App updates → Check for update**. If a newer release exists, its changes are shown.
3. Press **Download and install**, then tap **Install** on Android's screen.
   The first time, Android asks you to **Allow from this source**. Switch it on and press the button again.

The app also checks once a day when it is opened, and announces a new version in Telegram once.

## 4. Each night

1. Place the phone (see tips below) and plug in the charger.
2. **Monitor** tab: drag a box tightly around the flame. The dashed box above it is the "soot zone".
3. Press **Calibrate** and keep everything still for 60 s while a normal, healthy flame burns.
4. Press **Start**. A "monitoring started" message with a photo arrives in Telegram.
5. Press **Night** for a black screen at minimum brightness. The screen stays on so the camera keeps running. Double-tap to leave night mode.

If you move the phone or the diya, draw the box again and recalibrate.

### Camera placement tips
- Put the phone **30–50 cm** from the diya, on a stand or propped up so it **cannot move**. Any shift invalidates the calibration.
- Place it **side-on, at about flame height**, so the area just above the flame is visible (that's where soot and smoke show up).
- A plain, dim wall behind the flame works best. Avoid windows, TVs and other lights in the frame.
- Keep the phone **plugged in** and away from the heat of the flame.

### Settings
- **Sensitivity**: thresholds for out / dim / oversize / flicker / low-oil, and the bright-pixel threshold. If calibration says "no flame found", lower the bright-pixel threshold and recalibrate.
- **Timing**: check interval (default 2 s), per-alert cooldown, heartbeat.
- **Quiet test mode**: Telegram messages arrive silently, prefixed with `[TEST]`, and all timings are ×0.1 (flame-out after 3 s, dimming after 12 s, calibration in 15 s), so you can test by covering or blowing out a candle.

Settings apply the next time you press **Start**.

## ⚠️ Safety

**This is a convenience monitor, not a fire-safety device.** It can miss things (camera knocked over, phone dies, Wi-Fi down for hours).
Always keep a **working smoke alarm** nearby, put the diya on a **metal tray**, and keep it **away from curtains**, paper and anything flammable.

---

## How it works (for developers)

- **Kotlin**, single activity, **CameraX** `ImageAnalysis` (640×480). There's no ML and no OpenCV, just plain pixel analysis on luma (Y) plus the V chroma channel for flame colour.
- `core/`: a pure Kotlin/JVM module containing the detection logic:
  - `MetricsExtractor`: bright-pixel area %, ROI brightness, soot-zone luma and warmth, computed from an upright, downsampled frame.
  - `Calibrator` → `Baseline`.
  - `FlameAnalyzer`: rolling windows, sustained-condition timers, a regression trend for low oil, cooldowns and recovery.
  - Unit tests use synthetic frames for normal, dimming, out, flicker, oversize, soot darkening and gradual shrink: `./gradlew :core:test`.
- `app/`:
  - `MonitorService` is a foreground service (type `camera`) with a partial wake lock and a Wi-Fi lock. It owns the camera, so analysis continues with the UI closed.
  - `TelegramOutbox` keeps a FIFO queue with exponential-backoff retries, plus an immediate retry when the network returns.
  - `TelegramClient` uses OkHttp to call `sendMessage`, `sendPhoto` and `getUpdates`.
  - If the system restarts the service, monitoring resumes and a message is sent. Crashes and unexpected stops are also reported.
- CI: `.github/workflows/build.yml` runs the tests, builds the debug APK, uploads the `diya-monitor-apk` artifact, and creates a Release for `v*` tags.
- minSdk 26 (Android 8), targetSdk 36.
