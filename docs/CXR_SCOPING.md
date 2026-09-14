# Rokid SDK scoping — findings from direct inspection

Supersedes the earlier version of this document, which was wrong on two counts: it claimed BLE had
been "falsified on this hardware" (our BLE link to the iPhone works and carries the calendar), and
it misstated which side each SDK runs on.

Everything below was established by pulling the actual artifacts and testing on the device
(serial 1901092544006964, YodaOS-Sprite, Android 12), not from documentation.

## Which SDK runs where

| SDK | Runs on | Distribution | Notes |
|---|---|---|---|
| **CXR-M** | phone | Maven `com.rokid.cxr:client-m` | Android only, minSdk 28 |
| **CXR-L** | phone | Maven `client-l` (Android) **and** CocoaPods `RGCxrClient` (iOS) | binds the Rokid AI app |
| **CXR-S** | glasses | not published under that name | see `cxr-service-bridge` below |
| **cxr-service-bridge** | glasses | Maven `com.rokid.cxr:cxr-service-bridge` | the glasses-side API, ships arm64 native libs |

CXR-L's AAR manifest declares `<queries>` for `com.rokid.sprite.aiapp`, which is how an app declares
it will bind another app **on the same device**. That package is not installed on the glasses, so
CXR-L is phone-side. The glasses run `com.rokid.cxrservice` instead (`/system/app/CXRService`), whose
native half is `/system/lib64/libcxr_service_jni.so`.

## iOS: RGCxrClient

Real, official (published by a rokid.com account), public on the CocoaPods trunk, latest 1.1.1,
iOS 13+, Swift 5, one dependency (`RGCoreKit`). Binary framework from Rokid's OSS.

- Links **CoreBluetooth only** — no `ExternalAccessory`, so **no Apple MFi enrolment needed**.
- `RGCxrClientInitializationOptions` carries only `appDisplayName` and `pageName`: **no app key or
  client secret**, so no Rokid developer account is required.
- `RGCxrClientAuthPermission` includes `microphone`, `camera`, `media`, `deviceManage`.
- `RGCxrSessionMedia`: `startAudioStream(codec:mode:)`, `feedAudio(_:)`, `startPlayAudio(codec:)`,
  `takePhoto(width:height:quality:callback:)`.
- `RGCxrSessionMediaEvents.audioPublisher` is a Combine publisher emitting
  `.started(codec, type, channels)` then `.stream(data, timestamp)`. Sample rate is not carried.

## Glasses-side audio: two paths, only one usable

### CXRServiceBridge (Rokid's own) — NOT usable for continuous capture

`openAudioRecord(codec, mode, AudioRecordParam, AudioRecordCallback)` works and is permitted for a
third-party app (the service labels us `glassesApp.N`, the flora socket connects, no SELinux denial).
Validated empirically against the service's own error logs:

- **codec**: 1-3 valid; 0 and 4+ rejected with `AudioCapture: invalid codec N`.
- **mode**: 1-6 valid; 0 rejected with `AudioCapture: invalid mode N`.
- `AudioRecordParam(denoiseMode, rokidDtlnAEC, rokidBF)` — beamforming and echo cancellation.
- Modes differ in DSP routing: 3 and 6 go through `RokidAudio2`, 5 through `RokidAudio1`.

**It delivers exactly one 640-byte frame (320 samples = 20ms) and then closes**, every time, on every
codec/mode/denoise combination, worn or not. Chained re-opening recovers ~706 Hz of the 16000 Hz
needed (about 4%), so it is not a workaround. The close arrives as a real `closeAudioRecord3` command
for that record name; the only native call site is the `nativeCloseAudioRecord` JNI entry, and the
bridge's documented auto-close path (`onAudioRecordId` with an unseeded pending map) was ruled out by
pre-seeding the map across a wide id range. Cause remains unknown; it behaves like a deliberate cap.

### Plain Android AudioRecord — USE THIS

The glasses are Android and `AudioRecord` simply works, bypassing the bridge entirely:

| Source | Achieved | Notes |
|---|---|---|
| `MIC` | 15853 Hz | use this |
| `VOICE_COMMUNICATION` | 15861 Hz | also fine |
| `DEFAULT` | 15861 Hz | also fine |
| `VOICE_RECOGNITION` | 12592 Hz | underruns, returns all zeros — avoid |

16 kHz mono PCM16, `minBufferSize` 1280. Needs `RECORD_AUDIO`, granted over USB. Verified by
recording 5.00s to app-private storage while playing a 1 kHz tone through the glasses' speaker:
1 kHz energy 4.0 versus 0.4 at 3 kHz and 0.0 at 500 Hz, peak rising 8 -> 148 with the tone on.

**Caveat:** with the glasses unworn, 83% of samples are exactly zero and peak is 148/32767. That is
the signature of aggressive noise gating, not of a dead mic. Real speech amplitude while worn is
still unmeasured.

`tinycap` does not work (`/dev/snd/pcmC0D0c` absent); that rules out raw ALSA, not AudioRecord.

## Consequences for the assistant

The chosen architecture (glasses capture, phone brain) is viable via `AudioRecord` on the glasses,
with PCM shipped over our existing BLE link. The iOS `RGCxrClient` route is a proven fallback that
would move capture to the phone but makes us dependent on the Rokid app being connected.

## Transport budget: why assistant audio must be compressed

Measured against our negotiated MTU of 185 (about 182 usable bytes per packet):

| Payload | Rate | Verdict over this link |
|---|---|---|
| Raw PCM 16kHz mono 16-bit | 32000 B/s | too fast |
| BLE @30ms x4 packets | 24266 B/s | below raw PCM |
| BLE @30ms x6 packets | 36400 B/s | no headroom |
| BLE @15ms x4 packets | 48533 B/s | clears it, but iOS rarely grants 15ms |
| Opus 16-24kbps | 2000-3000 B/s | fits easily |
| AAC 32kbps | 4000 B/s | fits easily |

So raw PCM is not viable glasses->phone over BLE, and the transport is built encoding-agnostic:
4-byte big-endian length + UTF-8 JSON, chunked to 180 bytes, over characteristic `6E400002`
(`CHAR_WRITE`). The encoder choice is still open.

## Assistant audio path: proven end to end

Microphone -> Opus -> BLE -> phone, verified on device 2026-09-14.

| Stage | Measured |
|---|---|
| Capture | 318720 B in 10.0s = 15943 Hz (target 16000) |
| Opus encode | c2.android.opus.encoder, 24kbps configured |
| Transport | 50 batches of 200ms, 0 dropped |
| Phone received | 498 frames, 15 KB, reassembled and counted in the UI |

498 of an expected 500 frames (20ms each over 10s) arrived, so the path is
effectively lossless at this rate. ~1.5 KB/s against a link budget of ~24 KB/s.

Note the bitrate above was measured on quiet input and is NOT representative of
speech; Opus is variable-bitrate and real speech costs more. The headroom is
large enough that this does not change the design.

Two bugs were required to get here, both in our own code:

- `writeCh` was declared, cleared and read but never assigned: the edit meant to
  resolve it used a string replace with no assertion, silently matched nothing,
  and every send hit a null handle.
- Android permits one outstanding GATT operation, so writing chunks in a loop
  failed on every chunk after the first. Writes are now queued and pumped from
  `onCharacteristicWrite`.
