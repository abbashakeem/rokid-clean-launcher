package com.abbas.hudlauncher.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Connects to the iPhone companion app, which acts as the BLE peripheral.
 *
 * Roles are flipped from the obvious design because this firmware cannot advertise from a
 * third-party app: `startAdvertising` never completes even with a clean Bluetooth stack, a free
 * advertiser slot and permissions granted. Scanning as a central does work, but only once location
 * permission is held and location services are on — the firmware reports SDK 32 yet does not define
 * Android 12's Bluetooth runtime permissions, so it enforces the legacy model where BLE scans
 * silently return nothing without location.
 */
class BleCentral(private val context: Context) {

    /** Called with each complete decoded message from the phone. */
    var onMessage: ((type: String, data: org.json.JSONObject) -> Unit)? = null

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var inbound: ByteBuffer? = null

    @SuppressLint("MissingPermission")
    fun startScan() {
        try {
            val scanner = manager.adapter?.bluetoothLeScanner ?: run { Log.w(TAG, "no scanner"); return }
            if (scanning) return
            // No hardware filter: offloaded 128-bit UUID filtering is unreliable on this chipset,
            // and iOS may put the service UUID in the scan response rather than the advert. Match in
            // software instead (see onScanResult).
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, callback)
            scanning = true
            Log.d(TAG, "scanning for companion app")
        } catch (e: Throwable) {
            Log.w(TAG, "scan failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try { manager.adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) {}
        scanning = false
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private val seen = HashSet<String>()

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rec = result.scanRecord
            val uuids = (rec?.serviceUuids ?: emptyList()).map { it.uuid }
            val name = rec?.deviceName ?: result.device.name ?: ""
            if (seen.add(result.device.address)) {
                Log.d(TAG, "saw ${result.device.address} name='$name' uuids=$uuids")
            }
            if (!uuids.contains(BleServer.SERVICE)) return
            Log.d(TAG, "found companion ${result.device.address} rssi=${result.rssi}")
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed code=$errorCode"); scanning = false }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "connected to companion")
                g.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "companion disconnected")
                inbound = null
                try { g.close() } catch (_: Exception) {}
                gatt = null
                startScan()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(BleServer.SERVICE) ?: run { Log.w(TAG, "service missing"); return }
            val notify = svc.getCharacteristic(BleServer.CHAR_NOTIFY) ?: run { Log.w(TAG, "notify char missing"); return }
            g.setCharacteristicNotification(notify, true)
            notify.getDescriptor(BleServer.CCCD)?.let { d ->
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
            Log.d(TAG, "subscribed; waiting for data")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            accept(ch.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            accept(value)
        }
    }

    /** Reassemble the 4-byte big-endian length + JSON stream. */
    private fun accept(chunk: ByteArray) {
        var buf = inbound
        if (buf == null) {
            if (chunk.size < 4) return
            val len = ByteBuffer.wrap(chunk, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            if (len <= 0 || len > MAX_MESSAGE) { Log.w(TAG, "bad length $len"); return }
            buf = ByteBuffer.allocate(len)
            inbound = buf
            buf.put(chunk, 4, chunk.size - 4)
        } else {
            buf.put(chunk, 0, minOf(chunk.size, buf.remaining()))
        }
        if (!buf.hasRemaining()) {
            val json = String(buf.array(), Charsets.UTF_8)
            inbound = null
            try {
                val o = org.json.JSONObject(json)
                onMessage?.invoke(o.optString("type"), o.optJSONObject("data") ?: org.json.JSONObject())
            } catch (e: Exception) { Log.w(TAG, "bad json: ${e.message}") }
        }
    }

    companion object {
        private const val TAG = "BleCentral"
        private const val MAX_MESSAGE = 256 * 1024
    }
}
