package com.abbas.hudlauncher.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.abbas.hudlauncher.R

/**
 * Runs the BLE scan for the iPhone companion app in a foreground service.
 *
 * Scanning from Application context gets throttled or silently dropped by Android; the working
 * reference implementation for these glasses (RokidKeyboard) uses a foreground service, so we do too.
 */
class BleScanService : Service() {

    private var central: BleCentral? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        central = BleCentral(this).also { c ->
            c.onMessage = { type, data -> onMessage?.invoke(type, data) }
            c.startScan()
        }
        Log.d(TAG, "scan service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        central?.stopScan()
        central?.disconnect()
        central = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone link", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_home)
            .setContentTitle("HUD phone link")
            .build()
    }

    companion object {
        private const val TAG = "BleScanService"
        private const val CHANNEL = "hud_phone_link"
        private const val NOTIF_ID = 9

        /** Set by the app before starting, so decoded messages reach the launcher. */
        @Volatile var onMessage: ((type: String, data: org.json.JSONObject) -> Unit)? = null

        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, BleScanService::class.java)) }
            catch (e: Exception) { Log.w(TAG, "cannot start: ${e.message}") }
        }
    }
}
