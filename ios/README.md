# HUD Companion (iPhone)

Prototype iPhone app that pushes your chosen iPhone calendars to the glasses over our own Bluetooth
LE channel, independent of the Rokid app. This is the clean fix for the original problem: only the
calendars you tick are sent, so hidden or unwanted ones never reach the HUD.

## What it does now

- Connects to the glasses and finds our launcher's BLE service on them.
- Lists your iPhone calendars (EventKit); you tick which to show.
- "Push to glasses" sends the next 7 days from the ticked calendars. The launcher merges them into
  its agenda automatically.

## How discovery works

The glasses' Bluetooth controller allows only one advertiser and Rokid already uses it, so our
launcher can't advertise its own service UUID. The app instead finds the glasses via Rokid's
advertisement (or by name) and discovers our service `6E5D0001-…` among theirs on the same device.
Both the Rokid app and this app can be connected at once.

## Build and sideload (Xcode)

1. Xcode → **New → Project → iOS App**. Name it `HUDCompanion`, interface **SwiftUI**, language
   **Swift**. Delete the auto-created `ContentView.swift` and `*App.swift`.
2. Drag the four files in `HUDCompanion/` into the project (copy if needed):
   `HUDCompanionApp.swift`, `ContentView.swift`, `BLEClient.swift`, `CalendarSync.swift`.
3. In the target's **Info** tab add two usage strings (required or iOS kills the app):
   - `NSBluetoothAlwaysUsageDescription` = "Send your calendar to the glasses."
   - `NSCalendarsFullAccessUsageDescription` = "Read the calendars you choose to show on the glasses."
     (on iOS 16 and earlier the key is `NSCalendarsUsageDescription`.)
4. Optional, for pushing while backgrounded later: **Signing & Capabilities → + Background Modes →
   Uses Bluetooth LE accessories**.
5. Set the **Team** to your Apple ID (free is fine). Plug in the iPhone, select it, **Run**.
   - Free Apple ID: the app is signed for 7 days, then re-run to renew.
   - First launch: trust the developer profile in Settings → General → VPN & Device Management.

## Try it

1. Have the launcher running on the glasses (it starts the BLE server automatically once granted
   `BLUETOOTH_ADVERTISE`; see the main SETUP.md).
2. Open the app; allow Bluetooth and Calendar.
3. Wait for "Connected", tick your calendars, tap **Push to glasses**.
4. The events appear in the HUD agenda within a refresh.

## Protocol (both ends)

Framing: `uint32` big-endian length + UTF-8 JSON, chunked to the MTU.
Message: `{"type":"calendar","data":{"events":[{"title","start":ms,"end":ms,"allDay":bool,"location"}]}}`.
Times are epoch milliseconds. Adding new message types (a note, a nav destination) is just another
`type` handled in `HudApp.onMessage` on the launcher and a `send(type:…)` here.

## Next

- Share extension so "Share → HUD" from Google/Apple Maps pushes a destination.
- Custom notes/reminders to the Messages panel.
- Auto-push on calendar change and on connect, instead of a manual button.


## Careful: two copies of the newer Swift files

When VoicePipeline.swift and Secrets.swift were added to the Xcode target they landed at the
project ROOT (`~/Documents/HUDCompanion/`), not inside `HUDCompanion/` where the older sources
live. The project references them by bare `path = VoicePipeline.swift;`, so **Xcode builds the root
copy**.

Both copies currently exist and are identical, which is a trap: editing the wrong one produces a
"fix" that changes nothing and still builds. When editing either file, write the root copy and sync:

```
cp ~/Documents/HUDCompanion/VoicePipeline.swift ~/Documents/HUDCompanion/HUDCompanion/
cp ~/Documents/HUDCompanion/VoicePipeline.swift <repo>/ios/HUDCompanion/
```

Tidying this properly means re-adding the files from `HUDCompanion/` in Xcode and deleting the root
copies.

## Actor isolation and CoreAudio callbacks

`VoicePipeline` is `@MainActor`, which infers global-actor isolation onto top-level declarations in
the same file. An actor-isolated function cannot be converted to a C function pointer, so
`AudioConverterFillComplexBuffer`'s callback, its context struct and the sentinel constant are all
marked `private nonisolated`. Without that the build fails with "a C function pointer can only be
formed from a reference to a 'func' or a literal closure".


## And a third copy: `MemoryStore 2.swift`

Adding `MemoryStore.swift` in Xcode produced `MemoryStore 2.swift` at the project root, because a
file of that name already sat there. **That space-named copy is the one in the target** — the
project references `path = "MemoryStore 2.swift";`. Three identical copies now exist:

```
~/Documents/HUDCompanion/MemoryStore 2.swift      <- built
~/Documents/HUDCompanion/MemoryStore.swift        <- ignored
~/Documents/HUDCompanion/HUDCompanion/MemoryStore.swift  <- ignored
```

Only one is in the target, so there is no duplicate-symbol error, but editing either ignored copy
changes nothing while still building cleanly. When editing, write all of them and check the md5s
match. Tidying properly means re-adding from one location in Xcode and deleting the strays.

## `remove(atOffsets:)` needs SwiftUI

`IndexSet`-based removal is a SwiftUI extension on `RangeReplaceableCollection`, not Foundation. A
store that exposes `delete(at offsets: IndexSet)` for `.onDelete` must `import SwiftUI` even though
it contains no views, or the build fails with "not available due to missing import of defining
module 'SwiftUI'".
