package com.abbas.hudlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * When we open a Rokid scene or page, it runs inside Rokid's launcher task on top of Rokid's home
 * activity, so its double-tap `finish()` lands on Rokid's home instead of ours. This service watches
 * foreground events and, as soon as Rokid's home resumes after our launch, brings our launcher back.
 *
 * Needs two one-time grants over USB:
 *   adb shell appops set com.abbas.hudlauncher GET_USAGE_STATS allow      (read foreground events)
 *   adb shell appops set com.abbas.hudlauncher SYSTEM_ALERT_WINDOW allow  (start an activity from background)
 */
class ReturnWatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var lastQuery = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - startedAt > MAX_WATCH_MS) { stopSelf(); return }
            if (rokidHomeResumedSince(lastQuery)) {
                Log.d(TAG, "Rokid home resumed after our scene -> back to HUD")
                startActivity(Intent(applicationContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                stopSelf(); return
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        startedAt = System.currentTimeMillis()
        lastQuery = startedAt + GRACE_MS      // ignore events from the launch itself
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, GRACE_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() { handler.removeCallbacks(poll); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun rokidHomeResumedSince(since: Long): Boolean {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = try { usm.queryEvents(since, now) } catch (e: Exception) { return false }
        val ev = UsageEvents.Event()
        var hit = false
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                hit = ev.packageName == ROKID_PKG && ev.className == ROKID_HOME
                if (ev.packageName == packageName) hit = false   // we are already back
            }
        }
        lastQuery = now - 200
        return hit
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "HUD return watcher", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_home).setContentTitle("HUD launcher").build()
    }

    companion object {
        private const val TAG = "ReturnWatch"
        private const val CHANNEL = "hud_return"
        private const val NOTIF_ID = 7
        private const val POLL_MS = 400L
        private const val GRACE_MS = 1500L
        private const val MAX_WATCH_MS = 15 * 60 * 1000L
        const val ROKID_PKG = "com.rokid.os.sprite.launcher"
        const val ROKID_HOME = "com.rokid.os.sprite.launcher.main.SpriteMainActivity"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, ReturnWatchService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ReturnWatchService::class.java))
        fun hasUsageAccess(ctx: Context): Boolean {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            return usm.queryEvents(now - 60_000, now).hasNextEvent() || usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86_400_000, now).isNotEmpty()
        }
    }
}
