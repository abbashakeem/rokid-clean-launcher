package com.abbas.hudlauncher

import android.app.Application
import android.content.Intent
import android.util.Log

/**
 * Keeps the Rokid assist-server client alive for the whole process (not just while the home screen
 * is resumed), so navigation/weather/schedule feeds keep flowing in the background, and takes the
 * foreground when a route starts if the user chose our card over Rokid's navigation page.
 */
class HudApp : Application() {
    lateinit var scenes: RokidScenes; private set
    lateinit var configRepo: ConfigRepository; private set
    lateinit var ble: com.abbas.hudlauncher.ble.BleServer; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        configRepo = ConfigRepository(this)
        scenes = RokidScenes(this)
        scenes.onNavAny = { takeOverIfNeeded() }
        scenes.bind()

        // our own BLE service for the iPhone companion app; independent of Rokid's Bluetooth.
        // Wrapped so a Bluetooth failure can never crash the launcher (it is our HOME app).
        ble = com.abbas.hudlauncher.ble.BleServer(this)
        ble.onMessage = { type, data ->
            when (type) {
                "calendar" -> data.optJSONArray("events")?.let {
                    com.abbas.hudlauncher.ble.BlePhoneData.storeCalendar(this, it)
                    Log.d(TAG, "BLE calendar: ${it.length()} events")
                }
                else -> Log.d(TAG, "BLE message: $type")
            }
        }
        try { ble.start() } catch (e: Exception) { Log.w(TAG, "BLE start failed: ${e.message}") }

        // This firmware can't advertise, so we also scan as a central for the iPhone app acting as
        // the peripheral. Diagnostic logging on for now to confirm the central role works.
        com.abbas.hudlauncher.ble.BleScanService.onMessage = { type, data ->
            when (type) {
                "calendar" -> data.optJSONArray("events")?.let {
                    com.abbas.hudlauncher.ble.BlePhoneData.storeCalendar(this, it)
                    Log.d(TAG, "BLE calendar from phone: ${it.length()} events")
                }
                else -> Log.d(TAG, "BLE message: $type")
            }
        }
        com.abbas.hudlauncher.ble.BleScanService.start(this)
    }

    private var lastTakeOver = 0L

    /** Rokid's server opens its own nav page on Nav_Start; bring the HUD back on top of it. */
    private fun takeOverIfNeeded() {
        val cfg = configRepo.current
        if (!cfg.navTakeOver || cfg.navCard == "off") return
        if (MainActivity.isInForeground) return
        val now = System.currentTimeMillis()
        if (now - lastTakeOver < 15_000) return          // don't fight the user if they went to Rokid's page on purpose
        lastTakeOver = now
        Log.d(TAG, "navigation started -> bringing HUD to front")
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    companion object {
        private const val TAG = "HudApp"
        lateinit var instance: HudApp; private set
    }
}
