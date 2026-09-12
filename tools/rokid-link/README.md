# Rokid link: what an iPhone app would have to speak

Groundwork for talking to the glasses directly, without the Rokid companion app.

## Transport

The glasses are a BLE **peripheral**. `BtGattServerManager` in the assist server APK
(`/product/app/RokidSpriteAssistServer`) opens a GATT server and advertises:

| UUID | role |
|---|---|
| `00009400-0000-1000-8000-00805f9b34fb` | service |
| `00009401-…` | read + write |
| `00009402-…` | write (phone → glasses) |
| `00009403-…` | notify (glasses → phone) |
| `00009404-…` | notify, second channel |
| `00002902-…` | standard CCCD, to enable notifications |

This is the ordinary BLE accessory shape, which iOS CoreBluetooth supports directly. **No MFi
certification is needed.** The glasses also expose a classic SPP port (`00001101-…`, via
`listenUsingRfcommWithServiceRecord`), which iOS cannot use without MFi — so BLE is the way in.

Separately, the glasses speak Apple's own ANCS (`7905F431-B5CE-4E99-A40F-4B1E122D00D0`) to read
iPhone notifications, which is why notification mirroring is not part of the custom protocol.

## Framing: Caps

Payloads are wrapped in Rokid's `Caps` serialisation (`com.rokid.cxr.Caps`, native in the APK but
open elsewhere: `yodaos-project/node-caps`, and the Python port in `Miniontoby/pyrokid_cxr_clientm`).

    uint32   total size, big-endian
    uint8    version (5)
    uleb128  member count
    uint8[]  one type code per member
    ...      values in order, no padding or alignment

    'i' int32  SLEB128     'u' uint32 ULEB128     'f' float  4B little-endian
    'l' int64  SLEB128     'k' uint64 ULEB128     'd' double 8B little-endian
    'S' string ULEB128 length + UTF-8
    'B' binary ULEB128 length + raw bytes
    'O' object a nested frame          'V' void (no payload)

`caps.py` implements this and self-tests, including against the sample frame published with the
Python port. Run `python3 caps.py`.

## Above the framing

The message bodies are the ones the launcher already handles through the assist server's binder:
`{"cmd":"Sys","caps0":"<sub-command>","caps1":<payload>}` and the `Nav_*` sub-commands. See
`docs/SETUP.md`.

## Probe

`probe.py` scans for the glasses and, given an address, connects, enumerates the GATT table and
prints decoded Caps notifications. It never writes, so it cannot disturb the phone's link.

    python3 probe.py scan
    python3 probe.py listen <address>

macOS needs Bluetooth permission for whichever terminal runs it: System Settings → Privacy &
Security → Bluetooth. Without it CoreBluetooth aborts the process (exit 134).

## Unknowns

- Whether the glasses accept a second BLE central while the phone is connected.
- Whether anything above Caps is authenticated. The GATT write handler calls `caps.parse(value)`
  with no visible licence check, unlike the CXR SDK path, which validates a client secret.
- How frames larger than the BLE MTU are split.
