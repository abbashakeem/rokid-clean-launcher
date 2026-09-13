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
