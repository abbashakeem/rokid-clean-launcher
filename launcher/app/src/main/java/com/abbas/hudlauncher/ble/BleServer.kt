package com.abbas.hudlauncher.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Our own BLE GATT server on the glasses. The iPhone companion app connects to this directly,
 * independent of Rokid's Bluetooth. We own both ends, so the framing is trivial: a 4-byte
 * big-endian length then UTF-8 JSON, chunked across writes and reassembled here.
 *
 * Needs BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT (API 31+), granted over USB.
 */
class BleServer(private val context: Context) {

    /** Called on the main-agnostic GATT thread with each complete decoded message. */
    var onMessage: ((type: String, data: org.json.JSONObject) -> Unit)? = null

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var server: BluetoothGattServer? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null

    // reassembly, per connected central
    private val inbound = HashMap<String, ByteBuffer>()
    private val expected = HashMap<String, Int>()

    val isSupported: Boolean
        get() = try { manager.adapter?.isMultipleAdvertisementSupported == true } catch (e: Exception) { false }

    /**
     * Best-effort; never throws. This device's framework can require the legacy BLUETOOTH permission
     * for the advertiser, so any failure here must degrade to "no BLE" rather than crash the app.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        try { startInternal() } catch (e: Throwable) { Log.w(TAG, "BLE unavailable: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun startInternal() {
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) { Log.w(TAG, "bluetooth off"); return }
        if (server != null) return
        advertiser = adapter.bluetoothLeAdvertiser ?: run { Log.w(TAG, "no advertiser"); return }

        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val write = BluetoothGattCharacteristic(
            CHAR_WRITE,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notify = BluetoothGattCharacteristic(
            CHAR_NOTIFY,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        val info = BluetoothGattCharacteristic(
            CHAR_INFO,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        svc.addCharacteristic(write)
        svc.addCharacteristic(notify)
        svc.addCharacteristic(info)
        notifyChar = notify

        server = manager.openGattServer(context, callback).also { it.addService(svc) }
        advertise()
        Log.d(TAG, "server started, multipleAdv=$isSupported")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try { advertiser?.stopAdvertising(advCallback) } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    @SuppressLint("MissingPermission")
    private fun advertise() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE))
            .build()
        val adv = advertiser
        Log.d(TAG, "startAdvertising, advertiser=${adv != null}")
        adv?.startAdvertising(settings, data, advCallback)

        // The legacy path can wedge on this device ("advertiser not finished registration"), so also
        // try the modern advertising-set API, which registers through a different code path.
        try {
            val setParams = android.bluetooth.le.AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(true)
                .setScannable(true)
                .setInterval(android.bluetooth.le.AdvertisingSetParameters.INTERVAL_MEDIUM)
                .setTxPowerLevel(android.bluetooth.le.AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build()
            adv?.startAdvertisingSet(setParams, data, null, null, null, setCallback)
        } catch (e: Throwable) {
            Log.w(TAG, "advertisingSet unavailable: ${e.message}")
        }
    }

    private val setCallback = object : android.bluetooth.le.AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(
            set: android.bluetooth.le.AdvertisingSet?, txPower: Int, status: Int,
        ) {
            Log.d(TAG, if (status == 0) "ADVERTISING SET OK (tx=$txPower)" else "advertising set failed: status=$status")
        }
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { Log.d(TAG, "ADVERTISING OK") }
        override fun onStartFailure(errorCode: Int) {
            val why = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers (Rokid holds the slot)"
                ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                else -> "code $errorCode"
            }
            Log.w(TAG, "ADVERTISE FAILED: $why")
        }
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (characteristic.uuid == CHAR_WRITE) accept(device.address, value)
            if (responseNeeded) server?.sendResponse(device, requestId, 0, offset, null)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, 0, offset, null)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val body = if (characteristic.uuid == CHAR_INFO) INFO.toByteArray() else ByteArray(0)
            server?.sendResponse(device, requestId, 0, offset, body.copyOfRange(offset.coerceAtMost(body.size), body.size))
        }
    }

    /** Reassemble the 4-byte length + JSON stream for one central. */
    private fun accept(address: String, chunk: ByteArray) {
        var buf = inbound[address]
        if (buf == null) {
            if (chunk.size < 4) return
            val len = ByteBuffer.wrap(chunk, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            if (len <= 0 || len > MAX_MESSAGE) { Log.w(TAG, "bad length $len"); return }
            expected[address] = len
            buf = ByteBuffer.allocate(len)
            inbound[address] = buf
            buf.put(chunk, 4, chunk.size - 4)
        } else {
            buf.put(chunk, 0, minOf(chunk.size, buf.remaining()))
        }
        if (!buf.hasRemaining()) {
            val json = String(buf.array(), Charsets.UTF_8)
            inbound.remove(address); expected.remove(address)
            dispatch(json)
        }
    }

    private fun dispatch(json: String) {
        try {
            val o = org.json.JSONObject(json)
            onMessage?.invoke(o.optString("type"), o.optJSONObject("data") ?: org.json.JSONObject())
        } catch (e: Exception) { Log.w(TAG, "bad json: ${e.message}") }
    }

    companion object {
        private const val TAG = "BleServer"
        private const val MAX_MESSAGE = 256 * 1024
        private const val INFO = """{"app":"hud-launcher","proto":1}"""

        // Nordic UART Service UUIDs. A private UUID also works in theory, but NUS is known-good on
        // this firmware (RokidKeyboard uses it for the same iPhone-to-glasses link).
        val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-77656b657962")
        val CHAR_WRITE: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-77656b657962")
        val CHAR_NOTIFY: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-77656b657962")
        val CHAR_INFO: UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-77656b657962")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
