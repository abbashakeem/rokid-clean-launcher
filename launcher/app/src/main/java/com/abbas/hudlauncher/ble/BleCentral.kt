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

    /**
     * Address of the phone we last talked to, remembered across restarts.
     *
     * A backgrounded iOS app drops the local name from its advertisement and moves the service UUID
     * into an overflow area that only Apple centrals can read, so scanning cannot find it. A direct
     * autoConnect to the known address does not depend on the advertisement's contents, which is the
     * only way to get the link back while the companion app is not on screen.
     */
    private var lastAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)
        set(v) { prefs.edit().putString(KEY_ADDRESS, v).apply() }

    private val prefs by lazy { context.getSharedPreferences("hud", Context.MODE_PRIVATE) }
    private var autoConnectTried = false
    /** True only once the link is actually up; an armed autoConnect is pending, not connected. */
    private var connected = false
    /**
     * One-shot guards. iOS peripherals emit a Service Changed indication shortly after connecting,
     * so `onServicesDiscovered` fires twice; the second CCCD write collides with the first still in
     * flight, the stack drops both, `onDescriptorWrite` never arrives and the phone never sees a
     * subscriber. Do each step exactly once per connection.
     */
    private var discoveryStarted = false
    private var subscribed = false

    /**
     * Android silently blacklists an app that starts more than 5 scans per 30 seconds: startScan
     * succeeds, onScanFailed never fires, and zero results arrive. A single long-running scan also
     * goes quiet after the screen sleeps. So restart the scan on a slow cadence that stays well
     * inside the quota, and keep doing it until the phone is found.
     */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rescan = Runnable { restartScan() }
    private var subscribeAttempts = 0
    private var discoveryAttempts = 0
    private val subscribeRetry = Runnable { gatt?.let { trySubscribe(it) } }

    @SuppressLint("MissingPermission")
    fun startScan() {
        try {
            val scanner = manager.adapter?.bluetoothLeScanner ?: run { Log.w(TAG, "no scanner"); return }
            if (scanning) return
            // A filter is mandatory, not an optimisation: Android refuses to run an unfiltered scan
            // while the screen is off ("Cannot start unfiltered scan in screen-off"), and a HUD
            // spends most of its life with the display asleep. Filters are OR-ed, so match either
            // the service UUID or the advertised name in case iOS moves the UUID into the scan
            // response; onScanResult still confirms in software.
            val filters = listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(BleServer.SERVICE)).build(),
                ScanFilter.Builder().setDeviceName(ADV_NAME).build(),
            )
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(filters, settings, callback)
            scanning = true
            Log.d(TAG, "scanning for companion app")
            tryDirectReconnect()
            handler.removeCallbacks(rescan)
            handler.postDelayed(rescan, RESCAN_MS)
        } catch (e: Throwable) {
            Log.w(TAG, "scan failed: ${e.message}")
        }
    }

    /** Bounce the scan so a throttled or silently-dead scanner recovers on its own. */
    private fun restartScan() {
        if (connected) return                          // link is up; nothing to look for
        Log.d(TAG, "no companion yet; restarting scan")
        try { manager.adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) {}
        scanning = false
        seen.clear()
        startScan()
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(rescan)
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
            if (!uuids.contains(BleServer.SERVICE) && name != ADV_NAME) return
                if (connected) return
            if (gatt != null) {
                if (gatt?.device?.address == result.device.address) return   // autoConnect will land
                // The phone rotated its private address, so the armed autoConnect is aimed at an
                // address that no longer exists. Drop it and connect to the one we can actually see.
                Log.d(TAG, "address changed; dropping stale autoConnect")
                try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
                gatt = null
            }
            Log.d(TAG, "found companion ${result.device.address} rssi=${result.rssi}")
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed code=$errorCode"); scanning = false }
    }

    /**
     * Ask the stack to reconnect to the remembered phone whenever it reappears, alongside the scan.
     *
     * autoConnect has no timeout: it stays armed in the background and fires on its own, so this
     * covers the case scanning cannot (a backgrounded companion app). It only works while the phone
     * keeps the same address, so the scan stays running as the fallback for an address rotation.
     */
    @SuppressLint("MissingPermission")
    private fun tryDirectReconnect() {
        if (autoConnectTried || gatt != null) return
        val addr = lastAddress ?: return
        autoConnectTried = true
        try {
            val device = manager.adapter?.getRemoteDevice(addr) ?: return
            Log.d(TAG, "arming autoConnect to $addr")
            gatt = device.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Throwable) {
            Log.w(TAG, "autoConnect failed: ${e.message}")
        }
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
                connected = true
                lastAddress = g.device.address
                stopScan()
                // 512 is rejected by some peripherals; 185 is iOS's own ceiling and is known-good
                // on this firmware (RokidKeyboard uses it). Fall straight through if the request
                // cannot even be queued, otherwise discovery would never start.
                if (!g.requestMtu(185)) startDiscovery(g)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "companion disconnected")
                inbound = null
                connected = false
                discoveryStarted = false
                subscribed = false
                subscribeAttempts = 0
                discoveryAttempts = 0
                autoConnectTried = false
                handler.removeCallbacksAndMessages(null)
                handler.removeCallbacks(subscribeRetry)
                try { g.close() } catch (_: Exception) {}
                gatt = null
                startScan()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "mtu=$mtu status=$status")
            startDiscovery(g)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { Log.w(TAG, "discovery failed status=$status"); return }
            for (svc in g.services) {
                Log.d(TAG, "svc ${svc.uuid}")
                for (ch in svc.characteristics) {
                    Log.d(TAG, "   char ${ch.uuid} props=0x%02x descs=%s"
                        .format(ch.properties, ch.descriptors.map { it.uuid }))
                }
            }
            if (subscribeAttempts > 0) return    // Service Changed re-discovery; already under way
            // A CCCD write issued in this callback is routinely dropped: the stack is still settling
            // the connection (a second MTU callback arrives after it) and the write never completes,
            // so the phone never registers a subscriber and its notifications go nowhere. Delay the
            // first attempt, then retry until onDescriptorWrite actually confirms.
            handler.postDelayed({ trySubscribe(g) }, FIRST_SUBSCRIBE_DELAY_MS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Do NOT stop here: a successful write may have landed on an orphaned instance.
                // Keep cycling through the remaining instances until data actually arrives.
                Log.d(TAG, "CCCD written on instance #${subscribeAttempts - 1}")
            } else {
                Log.w(TAG, "CCCD write FAILED status=$status")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            accept(ch.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            accept(value)
        }
    }

    /**
     * Write the CCCD, retrying until [onDescriptorWrite] confirms it.
     *
     * The peripheral can expose the same service UUID more than once: iOS keeps an earlier
     * registration alive when the app republishes, so the database holds a live instance and an
     * orphaned one. `getService()` returns only the first match, and subscribing to the orphan
     * reports GATT_SUCCESS while the phone never sees a subscriber and sends nothing. So collect
     * every matching instance and subscribe to each in turn; extra subscriptions are harmless.
     */
    @SuppressLint("MissingPermission")
    private fun trySubscribe(g: BluetoothGatt) {
        if (subscribed) return
        val candidates = g.services
            .filter { it.uuid == BleServer.SERVICE }
            .mapNotNull { it.getCharacteristic(BleServer.CHAR_NOTIFY) }
        if (candidates.isEmpty()) { Log.w(TAG, "notify char missing"); return }
        val notify = candidates[subscribeAttempts % candidates.size]
        val cccd = notify.getDescriptor(BleServer.CCCD)
            ?: run { Log.w(TAG, "no CCCD on notify characteristic; cannot subscribe"); return }
        Log.d(TAG, "service instances=${candidates.size}, using #${subscribeAttempts % candidates.size}")
        subscribeAttempts++
        val notifSet = g.setCharacteristicNotification(notify, true)
        val ok = if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }
        Log.d(TAG, "subscribe attempt $subscribeAttempts: setNotification=$notifSet write=$ok")
        if (subscribeAttempts < MAX_SUBSCRIBE_ATTEMPTS) {
            handler.removeCallbacks(subscribeRetry)
            handler.postDelayed(subscribeRetry, SUBSCRIBE_RETRY_MS)
        } else {
            Log.w(TAG, "gave up subscribing after $subscribeAttempts attempts")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery(g: BluetoothGatt) {
        if (discoveryStarted) return
        discoveryStarted = true
        refreshCache(g)
        // refresh() clears the cache asynchronously; discovering immediately after races it and the
        // discovery silently never completes. Give the stack a moment, then retry until it lands.
        handler.postDelayed({ requestDiscovery(g) }, REFRESH_SETTLE_MS)
    }

    @SuppressLint("MissingPermission")
    private fun requestDiscovery(g: BluetoothGatt) {
        if (subscribeAttempts > 0 || subscribed) return
        discoveryAttempts++
        val ok = g.discoverServices()
        Log.d(TAG, "discovery attempt $discoveryAttempts: started=$ok")
        if (discoveryAttempts < MAX_DISCOVERY_ATTEMPTS) {
            handler.postDelayed({ requestDiscovery(g) }, DISCOVERY_RETRY_MS)
        }
    }

    /**
     * Drop Android's cached copy of the remote GATT database before discovering.
     *
     * Without this the stack reuses handles it learned on an earlier connection. When the phone app
     * changes its service definition, the cached handles no longer line up: a CCCD write reports
     * GATT_SUCCESS against a stale handle while the phone never registers a subscriber and its
     * notifications go nowhere. `refresh()` is public in the framework but hidden from the SDK, so
     * it has to be reached reflectively; failing is harmless, we just keep the stale cache.
     */
    private fun refreshCache(g: BluetoothGatt) {
        try {
            val ok = g.javaClass.getMethod("refresh").invoke(g) as? Boolean
            Log.d(TAG, "gatt cache refresh=$ok")
        } catch (e: Throwable) {
            Log.w(TAG, "gatt cache refresh unavailable: ${e.message}")
        }
    }

    /** Reassemble the 4-byte big-endian length + JSON stream. */
    private fun accept(chunk: ByteArray) {
        if (!subscribed) {
            subscribed = true
            handler.removeCallbacks(subscribeRetry)
            Log.d(TAG, "SUBSCRIBED CONFIRMED: data flowing from phone")
        }
        Log.d(TAG, "rx ${chunk.size} bytes")
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
                Log.d(TAG, "message complete: ${json.length} chars")
            } catch (e: Exception) { Log.w(TAG, "bad json: ${e.message}") }
        }
    }

    companion object {
        private const val TAG = "BleCentral"
        private const val MAX_MESSAGE = 256 * 1024
        private const val RESCAN_MS = 25_000L
        private const val FIRST_SUBSCRIBE_DELAY_MS = 700L
        private const val SUBSCRIBE_RETRY_MS = 2_000L
        private const val MAX_SUBSCRIBE_ATTEMPTS = 8
        private const val REFRESH_SETTLE_MS = 700L
        private const val DISCOVERY_RETRY_MS = 2_500L
        private const val MAX_DISCOVERY_ATTEMPTS = 4
        private const val KEY_ADDRESS = "ble_last_address"
        /** CBAdvertisementDataLocalNameKey set by the iPhone companion app. */
        private const val ADV_NAME = "HUD"
    }
}
