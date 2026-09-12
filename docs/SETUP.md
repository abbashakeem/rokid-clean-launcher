# Rokid HUD Launcher — Setup Guide

Three parts: **0** confirm the glasses can take a launcher, **A** deploy the backend to Render,
**B** point the backend at your iCloud calendars, **C** build and sideload the Android app from your Mac.

Everything runs from Terminal on the Mac. No Android Studio, no phone required.

---

## 0. Confirm the glasses over USB (do this first)

1. In the Rokid companion app on your iPhone, enable **Developer mode / USB debugging**
   (usually Settings → About → tap the build number several times, or a "Lab" / "Developer" section).
2. Plug the glasses into the Mac with a USB-C **data** cable. After Part C's toolchain install, run:

```bash
adb devices
```

   Accept the "Allow USB debugging" prompt on the glasses if one appears.

3. Read what we need to know:

```bash
adb shell getprop ro.build.version.sdk && adb shell wm size && adb shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN
```

   - The first number is the Android API level. If it is below 28, lower `minSdk` in `launcher/app/build.gradle.kts`.
   - `wm size` is the display resolution; the layout uses percentages so it should adapt, but note it.
   - The last command prints the current home activity. If it prints Rokid's own launcher, a HOME chooser probably exists.
     If it errors, the HOME category may be locked; fall back to launching the app from Rokid's app list
     (the UI is identical, it just won't replace the home screen).

Display note: the 2025 Rokid Glasses have a monochrome **green** display. White text renders green;
pure black (`#000000`) is still fully transparent. Nothing in the app needs to change for this.

---

## A. Backend on Render (free tier)

Render's free web service sleeps after 15 minutes idle and wakes in 30–50 s when the glasses call it.
The backend is built for that: iCloud is re-read on request whenever the cache is older than
`SYNC_INTERVAL_MIN`, and the app shows the last cached agenda while waiting.

### A1. Run it locally once

```bash
cd backend && python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
```

Create `backend/.env` from `.env.example`. Generate the shared secret:

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
```

Put that value in `API_KEY`. Start the server:

```bash
cd backend && . .venv/bin/activate && uvicorn main:app --reload --port 8000
```

Test in a second terminal (replace `KEY`):

```bash
curl -s -H "X-API-KEY: KEY" localhost:8000/get-calendar
```

### A2. Push the project to GitHub

Render deploys from a Git repo. Create an empty **private** repository on https://github.com/new
(no README), then from the project root:

```bash
git remote add origin https://github.com/abbashakeem/rokid-clean-launcher.git && git push -u origin main
```

`.gitignore` already excludes `.env`, `local.properties`, the venv and the `tools/` JDK, so no secrets go up.

### A3. Create the service (Blueprint)

1. Render dashboard → **New +** → **Blueprint** → connect GitHub → pick `rokid-clean-launcher`.
   Render reads `render.yaml` at the repo root: Docker runtime, free plan, health check on `/health`.
2. It will prompt for the variables marked `sync: false`. Fill in:
   `API_KEY` (from A1), `ICLOUD_USER`, `ICLOUD_APP_PASSWORD`, `CALENDARS` (Part B),
   `OWM_API_KEY`, `LAT`, `LON` (A4). Change `TZ` / `UNITS` if needed.
3. Click **Apply**. First build takes 3–5 minutes. Your URL is `https://rokid-hud-backend.onrender.com`
   (Render appends a suffix if the name is taken; copy the real one from the service page).

If you'd rather not use Blueprints: **New +** → **Web Service** → same repo, set **Root Directory** `backend`,
**Runtime** Docker, **Instance Type** Free, and add the same environment variables by hand.

Check it:

```bash
curl -s https://rokid-hud-backend.onrender.com/health
```

Logs are under the service's **Logs** tab; a successful refresh prints `synced N events from [...]`.
Every push to `main` redeploys automatically. Free instances lose the local `calendar.json` on restart,
which is fine: the next request rebuilds it from iCloud.

### A4. OpenWeatherMap key

Create a free account at https://home.openweathermap.org/users/sign_up, copy the key from
https://home.openweathermap.org/api_keys (new keys take ~10 minutes to activate). Find your `LAT`/`LON`
by right-clicking your location in Apple Maps or Google Maps.

---

## B. iCloud calendars (CalDAV)

The backend logs into iCloud with an **app-specific password** and reads only the calendars you list.
Hidden calendars never appear because they are simply not in the allow-list.

1. Go to https://account.apple.com → **Sign-In and Security** → **App-Specific Passwords** → **+**.
   Name it `rokid-hud`. Copy the `xxxx-xxxx-xxxx-xxxx` value into `ICLOUD_APP_PASSWORD`.
   (Two-factor authentication must be on for your Apple ID.)
2. `ICLOUD_USER` is your Apple ID email.
3. Open **Calendar** on iPhone → **Calendars** (bottom). Copy the exact display names of the calendars you
   want, comma-separated, into `CALENDARS`, e.g. `CALENDARS="Personal,Work"`.
   Names are case-sensitive. If a name is wrong, the Render **Logs** tab shows
   `calendars not found on iCloud: [...] (available: [...])` with the real names.
4. Force a sync and look at the result:

```bash
curl -s -X POST -H "X-API-KEY: KEY" https://rokid-hud-backend.onrender.com/sync-now
```

```bash
curl -s -H "X-API-KEY: KEY" "https://rokid-hud-backend.onrender.com/get-calendar?limit=3"
```

### Optional: iOS Shortcut override

`POST /update-calendar` accepts the same JSON, so a Shortcut can push events instead of (or as well as) CalDAV.
Build it as:

1. **Find Calendar Events** — filter `Calendar is <name>`, `Start Date is in the next 7 days`, Sort by Start Date, Limit 10.
2. **Repeat with Each** over the results. Inside the loop:
   - **Format Date** (Repeat Item → Start Date) with custom format `yyyy-MM-dd'T'HH:mm:ssZ`.
   - **Format Date** on End Date the same way.
   - **Dictionary** with keys `title` (Repeat Item → Title), `start_time`, `end_time`, `location` (Repeat Item → Location).
   - **Add to Variable** `events`.
3. After the loop: **Get Contents of URL** → `https://rokid-hud-backend.onrender.com/update-calendar`, Method **POST**,
   Headers `X-API-KEY: KEY`, Request Body **JSON**, and set the body to the `events` variable (Shortcuts sends it as an array).
4. Automation: **Time of Day**, several times a day, **Run Immediately** (iOS 17+). Shortcuts cannot run on an interval,
   which is why CalDAV is the primary path.

---

## C. Build and install the Android launcher

### C1. Toolchain (once)

```bash
brew install --cask android-commandlinetools
```

Java: `brew install openjdk@17` currently fails on this Mac (macOS 27 has no Homebrew bottle), so the
project uses a Temurin JDK downloaded into the git-ignored `tools/` folder. Re-run this if `tools/jdk17` is missing:

```bash
mkdir -p tools && curl -sSL -o tools/jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse" && tar xzf tools/jdk17.tar.gz -C tools && rm tools/jdk17.tar.gz && mv tools/jdk-17* tools/jdk17
```

In every new terminal, from the project root, load the toolchain variables:

```bash
source ./env.sh
```

(Or paste the exports from `env.sh` into `~/.zshrc` to make them permanent.) The Gradle wrapper jar is
already checked in under `launcher/gradle/wrapper/`, so no separate Gradle install is needed.

Install the SDK pieces (accept licences when asked):

```bash
source ./env.sh && yes | sdkmanager --licenses >/dev/null; sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "emulator" "system-images;android-30;google_apis;arm64-v8a"
```

### C2. Point the app at your backend

Copy `launcher/local.properties.example` to `launcher/local.properties` and fill in `HUD_BASE_URL`
(your Render URL, or `http://<mac-lan-ip>:8000` while testing against a local uvicorn) and `HUD_API_KEY`.
The file is git-ignored.

### C3. Build

```bash
source ./env.sh && cd launcher && ./gradlew assembleDebug
```

The APK lands at `~/Library/Caches/hudlauncher-build/app/outputs/apk/debug/app-debug.apk` (see Build output below).

### C4. Try it on the Mac first (emulator, since there is no phone)

```bash
avdmanager create avd -n hud -k "system-images;android-30;google_apis;arm64-v8a" --device "pixel" --force
```

```bash
emulator -avd hud -no-boot-anim &
```

Once it boots:

```bash
adb install -r ~/Library/Caches/hudlauncher-build/app/outputs/apk/debug/app-debug.apk && adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

Pick **HUD Launcher → Always** in the chooser. To prove the 12-hour format ignores the system setting,
enable 24-hour time in the emulator's Settings → the clock should still read `hh:mm AM/PM`.
Toggle airplane mode: weather falls back to the dimmed mock strings and the agenda shows the last cached list.

Reaching a local backend from the emulator: use `http://10.0.2.2:8000` as `HUD_BASE_URL`.

### C5. Install on the glasses

```bash
adb install -r ~/Library/Caches/hudlauncher-build/app/outputs/apk/debug/app-debug.apk
```

Press the home gesture/button on the glasses. If a chooser appears, pick **HUD Launcher → Always**.
If nothing changes, try:

```bash
adb shell cmd package set-home-activity com.abbas.hudlauncher/.MainActivity
```

If that is refused, launch the app from Rokid's app list instead. To go back to Rokid's launcher at any time:

```bash
adb uninstall com.abbas.hudlauncher
```

Grant two permissions once over USB. The first lets the brightness slider write the system setting,
the second lets the now-playing pill read the Bluetooth music session:

```bash
adb shell appops set com.abbas.hudlauncher WRITE_SETTINGS allow && adb shell cmd notification allow_listener com.abbas.hudlauncher/.HudNotificationListener && adb shell appops set com.abbas.hudlauncher GET_USAGE_STATS allow && adb shell appops set com.abbas.hudlauncher SYSTEM_ALERT_WINDOW allow && adb shell pm grant com.abbas.hudlauncher android.permission.BLUETOOTH_CONNECT
```

The last two feed the return watcher. Rokid's assist server force-stops the foreground third-party app
when it opens a scene, then shows Rokid's home with the scene page on top, and double-tap only finishes
the page. The system re-binds our notification listener within a fraction of a second of the kill, so the
watcher lives there: armed via SharedPreferences before the scene call, it polls usage events and
relaunches the HUD as soon as Rokid's home resumes after the page.

### App picker contents

The carousel mirrors Rokid's own list. Translation, Teleprompter, Subtitles, Navigation and Vision AI are
Rokid "scenes", opened through the assist server's binder (`MasterAssistService`, exported without a
permission) with the same JSON command Rokid's launcher sends. Music, Device info and Rokid home are
exported activities in Rokid's launcher, started by explicit intent. Settings is Android's.

### Messages panel and phone-link glyph

The launcher registers itself as a client of Rokid's assist server (`registerClient` over the same binder)
and receives the server's broadcast messages: `cmd_bluetooth_gatt_status` (Rokid app link up/down) and
`cmd_bluetooth_gatt_normal_result` with `Ntf_SendNewMsg` / `Ntf_ResetMsgList` (phone notifications relayed
by the Rokid app). The left-most bar button opens the Messages panel with the latest ones; double tap closes.
The small phone glyph next to Wi-Fi is lit when the Rokid app link is up or, as a fallback, when the
phone's Bluetooth audio (A2DP sink / HFP client) is connected. Needs the `BLUETOOTH_CONNECT` grant above.

### Display crispness

No text shadows, no translucent fills, and greys as solid colours: soft edges and alpha blends bloom on
the micro-LED panel. Keep vector strokes at least ~1 dp after scaling (tiny glyphs need thicker strokes).

### Now-playing pill

While a media session is active (phone music over Bluetooth appears as
`com.android.bluetooth/BluetoothMediaBrowserService`), the Home button is replaced by a pill showing
play state, title, artist and progress. Tap toggles play/pause. Requires the notification-listener grant above.

### Build output

`Documents` is iCloud-synced and iCloud creates `name 2.xml` duplicates inside Gradle's build folder, which
breaks resource merging. Both Gradle build directories therefore live under `~/Library/Caches/hudlauncher-build/`.
The APK is at `~/Library/Caches/hudlauncher-build/app/outputs/apk/debug/app-debug.apk`.

### Touchpad controls

The temple touchpad's firmware recognises gestures and sends them as keys (recorded with `getevent`):
touch start = `KEYCODE_NOTIFICATION` (83), single tap = `ENTER` (about 0.5 s after the touch),
double tap = `BACK`, swipe forward = `DPAD_RIGHT` plus `DPAD_DOWN` repeats, swipe back = `DPAD_LEFT` plus `DPAD_UP`.

In the launcher: swipe moves the focus ring between the three bottom buttons, tap selects, double tap
turns the display off (the same gesture Rokid's launcher uses). The display also turns off after 5 s idle;
this uses the window's own user-activity timeout, so other apps keep the normal system timeout.
Left button opens the brightness slider (swipe to adjust, tap to close), middle refreshes weather and
calendar, right opens the built-in app picker (every launchable app on the glasses: swipe to move,
tap to launch, double tap to close). Rokid's own app picker and brightness page are pages inside its
launcher activity and cannot be opened from outside, which is why both are re-implemented here.

The tap click sound comes from the system sound-effects pool, so the launcher switches
`sound_effects_enabled` off while it is in front and back on when another app takes over.

Logs while it runs:

```bash
adb logcat -s Weather Calendar
```
