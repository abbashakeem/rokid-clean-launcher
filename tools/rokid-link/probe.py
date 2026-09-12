#!/usr/bin/env python3
"""
Read-only probe of the Rokid glasses' BLE link, to establish what an iPhone app would need.

The glasses run a GATT *server* (BtGattServerManager in the assist server APK) advertising a custom
service with write and notify characteristics. That is the standard BLE accessory shape, which iOS
CoreBluetooth supports natively and which needs no MFi certification, unlike the classic SPP port
the glasses also expose.

This script only scans, connects, enumerates and listens. It never writes to the glasses, so it
cannot disturb the phone's link.

    python3 probe.py scan          # list nearby devices, flag likely glasses
    python3 probe.py listen <addr> # connect, enumerate, dump decoded Caps notifications
"""
import asyncio
import sys

from caps import Caps

SERVICE = "00009400-0000-1000-8000-00805f9b34fb"
CHAR_RW = "00009401-0000-1000-8000-00805f9b34fb"
CHAR_WRITE = "00009402-0000-1000-8000-00805f9b34fb"
CHAR_NOTIFY = "00009403-0000-1000-8000-00805f9b34fb"
CHAR_NOTIFY2 = "00009404-0000-1000-8000-00805f9b34fb"


async def scan() -> None:
    from bleak import BleakScanner
    print("scanning 10s…")
    devices = await BleakScanner.discover(timeout=10.0, return_adv=True)
    for addr, (dev, adv) in sorted(devices.items(), key=lambda kv: -(kv[1][1].rssi or -999)):
        uuids = [u.lower() for u in (adv.service_uuids or [])]
        hit = SERVICE in uuids
        name = dev.name or adv.local_name or "?"
        looks_rokid = hit or "rokid" in name.lower() or "glass" in name.lower()
        mark = "  <-- Rokid GATT service" if hit else ("  <-- name looks like glasses" if looks_rokid else "")
        if looks_rokid or uuids:
            print(f"{addr}  rssi={adv.rssi:>4}  {name:<24} {uuids}{mark}")


async def listen(address: str) -> None:
    from bleak import BleakClient
    frames = 0

    def on_notify(char, data: bytearray) -> None:
        nonlocal frames
        frames += 1
        head = bytes(data[:12]).hex(' ')
        try:
            caps = Caps.from_bytes(bytes(data))
            print(f"[{char.uuid[4:8]}] {len(data):>4}B  {caps}")
        except Exception as exc:
            print(f"[{char.uuid[4:8]}] {len(data):>4}B  raw {head}  ({exc})")

    async with BleakClient(address, timeout=20.0) as client:
        print(f"connected: {client.is_connected}")
        for service in client.services:
            print(f"service {service.uuid}")
            for c in service.characteristics:
                print(f"   char {c.uuid}  {','.join(c.properties)}")
        for uuid in (CHAR_NOTIFY, CHAR_NOTIFY2):
            try:
                await client.start_notify(uuid, on_notify)
                print(f"subscribed {uuid}")
            except Exception as exc:
                print(f"cannot subscribe {uuid}: {exc}")
        print("listening 60s (no writes are sent)…")
        await asyncio.sleep(60)
        print(f"{frames} frames")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "scan"
    asyncio.run(scan() if cmd == "scan" else listen(sys.argv[2]))
