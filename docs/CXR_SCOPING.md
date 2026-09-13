# CXR path: scoping

Alternative to raw BLE, which we falsified on this hardware (the reference implementation's own
receiver also fails to discover the iPhone — see IPHONE_APP.md). CXR is Rokid's sanctioned SDK suite,
so the link rides their transport (BLE GATT + classic socket + Wi-Fi Direct with fallback) instead of
us opening our own radio.

## Does the companion app survive? Yes.

Only the transport changes. Everything that made the app worth building stays:

- the EventKit calendar picker (the founding problem: choose which iPhone calendars sync)
- the throttled auto-push (on connect, on calendar change, max every 2 hours)
- the SwiftUI UI and the manual "Push now"

`BLEClient.swift` is replaced by a CXR transport. `CalendarSync.swift` and `ContentView.swift` are
essentially untouched.

## The three SDKs

| SDK | Runs on | Role for us |
|---|---|---|
| **CXR-M** | phone (Android documented; iOS existence unclear in community docs) | mobile companion |
| **CXR-S** | glasses | our launcher receives messages |
| **CXR-L** | phone, **Android/iOS** | extends the Rokid AI app; iOS client exists |

## Confirmed iOS availability

`Anezium/Rokid-Lyrics-iOS` integrates the **CXR-L client via CocoaPods: `RGCxrClient`**. It exposes
`openCustomView` / `updateCustomView` / `sendCustomCmd`, with no app ID, client secret or licence
mentioned. That is concrete proof an iOS CXR client exists and is usable.

Note the community docs only document CXR-M for Android; the iOS story runs through CXR-L.

## Glasses side

CXR-S gives an on-device app a message bridge:

- `CXRServiceBridge.sendMessage(name, Caps)` — glasses → phone
- a matching subscription API — phone → glasses (`message-subscription.md`)

Payloads are `Caps`, which we already decoded and implemented (`tools/rokid-link/caps.py`), so the
framing is not new work.

## Open questions to resolve before committing

1. **Does `sendCustomCmd` from CXR-L reach a third-party glasses app**, or only Rokid's own scenes?
   This is the crux: we need the launcher to receive the calendar payload.
2. Does CXR-L require the **Hi Rokid app** to be installed and connected? CXR-L is described as
   "extending the Rokid AI APP", which implies yes. Acceptable for us (it is already connected), but
   it means we do not escape the Rokid app the way raw BLE would have.
3. Whether `RGCxrClient` is on a public CocoaPods spec repo or needs Rokid's private source.
4. Payload size limits for `sendCustomCmd` (a week of events is a few KB, likely fine).

## Effort estimate

- iOS: swap transport, ~half a day, assuming the pod installs cleanly.
- Glasses: add CXR-S dependency and a message subscription in the launcher, ~half a day.
- Risk concentrated in open question 1; if custom commands cannot reach our app, this path dies and
  the Wi-Fi backend fallback is the answer.

## Recommended next step

Spike the smallest possible test: add `RGCxrClient` to the iOS app, send one `sendCustomCmd`, and add
a CXR-S subscription in the launcher to see whether anything arrives. That answers question 1 cheaply
before any real work.
