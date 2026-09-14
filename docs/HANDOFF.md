# Rokid HUD Launcher — handoff

State as of 2026-09-14, commit `ecab5cd`, 87 commits, pushed to
`github.com/abbashakeem/rokid-clean-launcher`.

---

## 1. What this is, and the problem it solves

A replacement home screen for Rokid Glasses (2025) showing a 12-hour clock, condensed weather and
the next few calendar events, plus a voice/text assistant.

**The founding problem:** Rokid's native calendar mirrors *every* iPhone calendar including hidden
ones, and the feed carries no calendar names, so it cannot be filtered. Two independent paths now
solve it — a backend that pulls only allow-listed iCloud calendars via CalDAV, and an iPhone
companion app that pushes EventKit-selected calendars over BLE.

**Developer context:** no Kotlin background; Mac only, no Android phone; deployment over USB-C.

---

## 2. Three components

| Component | Language | Runs on | Purpose |
|---|---|---|---|
| `launcher/` | Kotlin | glasses | HOME replacement, HUD, mic capture, camera |
| `backend/` | Python/FastAPI | Render (free) | CalDAV sync, weather, settings, assistant proxy, APK hosting |
| `ios/HUDCompanion/` | Swift/SwiftUI | iPhone | calendar push, voice pipeline, chat, assistant routing |

Xcode project lives **outside** the repo at `~/Documents/HUDCompanion/`. The repo copy in
`ios/HUDCompanion/` is the source of truth; changes must be copied across (see §7 traps).

---

## 3. Device facts

```
serial        1901092544006964      (always export ANDROID_SERIAL — see §7)
model         RG-glasses
Android       12 (SDK 32)
screen        480x640 @240dpi portrait, monochrome green
drawable band rows 119–446px  →  band_top=79dp, band_height=218dp
ink colour    #40FF5E  (pure white blooms on the micro-LED panel)
HOME          com.abbas.hudlauncher.MainActivity  (set via cmd package set-home-activity)
camera        1 device, sensor 4032x3024; we capture 640x480 JPEG ≈ 10KB
mics          4 built-in, 16kHz; MIC/VOICE_COMMUNICATION/CAMCORDER/UNPROCESSED all work
              VOICE_RECOGNITION returns pure silence — unusable
iPhone BLE    5A:2F:8E:07:BC:B9 (discovered at runtime, not configured)
backend       https://rokid-hud-backend.onrender.com
```

---

## 4. What works, and how it was verified

Everything below was confirmed on hardware, not inferred.

**Launcher / HUD**
- HOME replacement, band layout, green palette, app picker, nav card with map tint
- Display sleep via `HudLockService` (accessibility `GLOBAL_ACTION_LOCK_SCREEN`); 5s idle and
  double-tap. Warning glyph appears when the service is disabled.
- Self-update over Wi-Fi from the backend

**Calendar → glasses**
- Backend CalDAV path, and BLE push from the phone (26 events delivered, verified in prefs)

**Battery**
- BLE scan was `SCAN_MODE_LOW_LATENCY` running **continuously**: `batterystats` showed 1h48m
  unbroken "unoptimized" scanning with 0 results. Now `LOW_POWER` in bounded 8s windows with a
  real stop between them, backing off 30s→5min. Measured **100% → 3%** duty cycle.
- `autoConnect` restores the link in ~10-20ms without scanning; scanning is only the fallback for
  iOS private-address rotation.

**Voice chain (end to end)**
```
glasses mic → Opus (c2.android.opus.encoder, ~12kbps) → BLE (50 batches/10s, 0 dropped)
→ iOS Opus decode (AudioConverter) → SFSpeechRecognizer → assistant → reply
```
Speech confirmed **by listening** to captured WAVs, not by levels (see §7). 498 of 500 frames
arrive intact; phone-side counter agrees with the glasses.

**Assistant**
- Three routes: Gemini direct from the phone, backend proxy, Apple on-device
- Gemini verified live: multi-turn, system prompt honoured (2 sentences, no markdown), Google
  Search grounding confirmed with Tokyo weather and a live Bitcoin price
- Context injected: local time, calendar, remembered facts; weather on the backend route only
- Memory is **phone-owned** (`MemoryStore`, UserDefaults, 200 facts): "remember that ..." is
  captured on-device, answers instantly, no model call
- Text chat shares one conversation with voice; last 20 turns travel with each request
- Settings screen: keys in Keychain, editable at runtime

**Camera**
- Our app **can** open camera 0 despite Rokid's assistserver existing. 9,989-byte JPEG captured.
  `CameraProbe.kt`, triggered by `adb shell am broadcast -a com.abbas.hudlauncher.CAM_TEST`

---

## 5. Not done

| Item | Notes |
|---|---|
| **Vision** | Camera proven, Gemini `inline_data` shape confirmed. Needs: JPEG over the existing BLE link, then Gemini vision. ~10KB is <1s on the link. |
| **Apple orchestrator** | Apple answers what it can, routes the rest to Gemini. Recommended design: constrained triage on Apple (it is unreliable at free-form tool calls), plus auto-escalation when its answer contains a refusal or no-internet admission. |
| **Function button** | Dropped after 4 attempts. It is `KEY_MENU` on `/dev/input/event0` → `KEYCODE_SPRITE_FUNCTION`, claimed by `SingleKeyGesture` at the policy layer before app dispatch, then broadcast as `ACTION_SPRITE_BUTTON_UP`. Short press = picture, long = video. Use our own trigger instead. |
| **Memory durability** | Solved by moving to the phone. Backend `DATA_DIR` is ephemeral (no disk in `render.yaml`). |
| **BLE address rotation** | If the link drops *and* iOS rotates its address *and* the app is backgrounded, reconnect fails until the app is foregrounded. Bonding exists but is stored against the private address. |
| **CalDAV retirement** | Redundant now the phone pushes calendars. |
| **Anthropic provider** | Code complete and tested for isolation; needs only `ANTHROPIC_API_KEY` + `ASSISTANT_PROVIDER=anthropic`. |

---

## 6. Build and deploy

```bash
# launcher
source env.sh                      # JDK17 in tools/jdk17, ANDROID_HOME
cd launcher && ./gradlew assembleDebug
export ANDROID_SERIAL=1901092544006964
adb install -r ~/Library/Caches/hudlauncher-build/app/outputs/apk/debug/app-debug.apk

# MANDATORY after every install — otherwise display-off silently stops working
adb shell settings put secure enabled_accessibility_services com.abbas.hudlauncher/.HudLockService
adb shell settings put secure accessibility_enabled 1
adb shell pm grant com.abbas.hudlauncher android.permission.RECORD_AUDIO
adb shell pm grant com.abbas.hudlauncher android.permission.CAMERA

# backend: push to main; render.yaml has autoDeploy. Keys are set in the Render dashboard.
# iOS: build in Xcode from ~/Documents/HUDCompanion/
```

Gradle output is redirected to `~/Library/Caches/hudlauncher-build/` because `Documents` is
iCloud-synced and produced `name 2.xml` duplicates that broke resource merging.

**Debug triggers** (DEBUG builds, receiver registered only while the activity is resumed):
```
adb shell am broadcast -a com.abbas.hudlauncher.MIC_TEST     # 10s voice capture → phone
adb shell am broadcast -a com.abbas.hudlauncher.CAM_TEST     # one still → files/shot.jpg
adb shell am broadcast -a com.abbas.hudlauncher.DEBUG_NAV --es cmd stop
```

---

## 7. Traps — read this before debugging anything

These each cost real time. Most are recorded in code comments too.

**The log tag is `HudMain`, not `MainActivity`.** Filtering `adb logcat -s MainActivity:V` returns
empty forever and looks exactly like "the app isn't logging". This invalidated four separate
observations in one session. Other tags: `Sleeper`, `HudLock`, `BleCentral`, `MicCapture`,
`AssistantMic`, `OpusEncoder`, `RokidScenes`, `CameraProbe`.

**Always `export ANDROID_SERIAL=1901092544006964`.** An emulator left running once shadowed the
glasses, and a whole afternoon of BLE conclusions turned out to be about the emulator.

**Empty output is not evidence.** `/proc/<pid>/maps`, `dumpsys` greps and `logcat` filters all
return nothing both when the answer is "no" and when the query is wrong. Verify the instrument
fires on a known-positive case first.

**Level metrics do not identify speech.** A "bursty envelope" was reported as speech when it was
room noise; `VOICE_COMMUNICATION` measured 0% voiced while containing clear words. Dump to WAV and
listen.

**The glasses must be worn** for mic tests. Desk captures peak ~150 and hold only noise; worn
captures peak ~3000.

**The accessibility service wipes repeatedly** (installs, and sometimes spontaneously). Display-off
then degrades to a no-op *by design* — an earlier version manipulated power directly and left the
device unwakeable twice. Never reintroduce direct power/brightness manipulation or global key
filtering (`flagRequestFilterKeyEvents` swallowed all touchpad input and bricked it).

**Render free tier cold start measured 237 seconds.** Not a bug. The assistant now calls Gemini
directly to avoid it; the backend route uses a 300s timeout.

**Duplicate Swift files.** `VoicePipeline.swift`, `Secrets.swift` and `MemoryStore.swift` exist at
both `~/Documents/HUDCompanion/` and `~/Documents/HUDCompanion/HUDCompanion/`. Xcode builds the
**root** copies, and `MemoryStore` is referenced as `"MemoryStore 2.swift"`. Editing an ignored copy
changes nothing and still builds. Write all copies and compare md5s.

**Missing imports for ambient-feeling symbols.** `remove(atOffsets:)` needs `import SwiftUI`;
`@Published`/`ObservableObject` need `import Combine`. Both surface as `#MemberImportVisibility`.

**Android GATT allows one outstanding operation.** Writing chunks in a loop fails on every chunk
after the first. Writes are queued and pumped from `onCharacteristicWrite`.

**Android refuses unfiltered BLE scans while the screen is off** ("Cannot start unfiltered scan in
screen-off"). A HUD is mostly screen-off, so the scan must carry a filter.

**iOS republishing a service creates a duplicate instance.** `getService()` returns the first, which
may be the orphan — a subscribe then succeeds against nothing. Guard publication; resolve across all
instances.

**Rokid scene launches force-stop us.** The assist server force-stops top non-allowlisted packages
and the allowlist is hardcoded, so opening a Rokid scene flashes their launcher. Mitigated with an
"Opening X…" hand-off frame; not fixable.

**`CXRServiceBridge.openAudioRecord` is a dead end.** Permitted for third-party apps, codec 1-3 and
mode 1-6 valid, but it delivers exactly one 640-byte frame per open regardless of parameters.
Chained re-opening recovers ~4% of the sample rate. Use plain `AudioRecord`.

---

## 8. Architecture decisions worth keeping

- **Memory on the phone, not the backend.** The backend filesystem is ephemeral; a feature whose
  value is persistence cannot live there.
- **Assistant direct to the provider, glasses data via the backend.** A voice assistant cannot
  tolerate a 237s cold start; calendar sync can.
- **Explicit memory capture only.** "remember that ..." and nothing else. Letting the model decide
  accumulates junk and makes what it knows unpredictable.
- **Client-supplied context is discarded server-side**, so `context` cannot be used for injection.
- **Every lookup isolated.** A failing weather provider must not break an unrelated question.
- **Display-off degrades to a no-op** rather than risking the unwakeable state.
- **Compression is mandatory** for audio: raw PCM is 30KB/s against a ~24KB/s link; Opus is ~1.5KB/s.

---

## 9. Working practices that mattered

Late in the session several failures came from editing text I had not read, and from claiming
success on the strength of a clean build. What stopped it:

- Every scripted edit **asserts on its anchor** and prints a grep proving it landed
- Builds, copies and commits sit **behind** that gate, never beside it — a `&&` chain once produced
  a commit whose message described work it did not contain
- Structural edits get a **brace-balance check** (`{` count minus `}` count must be 0)
- `swiftc`/`xcodebuild` exit codes are captured directly, not read from a pipeline's last command
- Verify the instrument on a known-positive case **before** asking the user to do anything
