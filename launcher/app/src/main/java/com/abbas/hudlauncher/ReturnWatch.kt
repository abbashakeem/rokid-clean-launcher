package com.abbas.hudlauncher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Brings the HUD back after a Rokid scene/page closes.
 *
 * Rokid's assist server force-stops the foreground third-party app (us) when it opens a scene, then
 * shows Rokid's home and the scene page on top. Double-tap finishes the page and Rokid's home is left
 * showing. The system re-binds our notification listener within ~300 ms of the kill, so the watcher
 * lives there: armed through SharedPreferences (survives the force-stop), it polls usage events and
 * starts MainActivity once Rokid's home resumes *after* the page was shown.
 *
 * Grants (once, over USB):
 *   adb shell appops set com.abbas.hudlauncher GET_USAGE_STATS allow
 *   adb shell appops set com.abbas.hudlauncher SYSTEM_ALERT_WINDOW allow   (activity start from background)
 */
object ReturnWatch {
    private const val TAG = "ReturnWatch"
    private const val PREFS = "hud"
    private const val KEY_ARMED_AT = "return_watch_armed_at"
    private const val POLL_MS = 400L
    private const val MAX_WATCH_MS = 15 * 60 * 1000L
    private const val ROKID_PKG = "com.rokid.os.sprite.launcher"
    private const val ROKID_HOME = "com.rokid.os.sprite.launcher.main.SpriteMainActivity"

    private val handler = Handler(Looper.getMainLooper())
    private var polling: Runnable? = null

    fun arm(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_ARMED_AT, System.currentTimeMillis()).commit()   // synchronous: we get force-stopped right after
    }

    fun disarm(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ARMED_AT).apply()
        polling?.let { handler.removeCallbacks(it) }; polling = null
    }

    /** Called when the notification listener (re)connects; starts polling if a scene launch is pending. */
    fun resumeIfArmed(ctx: Context) {
        val armedAt = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ARMED_AT, 0L)
        if (armedAt == 0L || System.currentTimeMillis() - armedAt > MAX_WATCH_MS) { disarm(ctx); return }
        polling?.let { handler.removeCallbacks(it) }
        Log.d(TAG, "watching for Rokid home after scene (armed ${System.currentTimeMillis() - armedAt} ms ago)")
        val app = ctx.applicationContext
        polling = object : Runnable {
            override fun run() {
                when (check(app, armedAt)) {
                    Outcome.RETURN -> {
                        Log.d(TAG, "Rokid home resumed after page -> back to HUD")
                        disarm(app)
                        app.startActivity(Intent(app, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                    Outcome.DONE -> disarm(app)
                    Outcome.WAIT -> if (System.currentTimeMillis() - armedAt < MAX_WATCH_MS) handler.postDelayed(this, POLL_MS) else disarm(app)
                }
            }
        }.also { handler.post(it) }
    }

    private enum class Outcome { WAIT, RETURN, DONE }

    /** Replays foreground events since arming: page shown, then Rokid home resumed = return. */
    private fun check(ctx: Context, since: Long): Outcome {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = try { usm.queryEvents(since, System.currentTimeMillis()) } catch (e: Exception) { return Outcome.WAIT }
        val ev = UsageEvents.Event()
        var sawPage = false
        var outcome = Outcome.WAIT
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            when {
                ev.packageName == ctx.packageName && ev.timeStamp > since + 1000 -> outcome = Outcome.DONE   // already home
                ev.packageName == ROKID_PKG && ev.className != ROKID_HOME -> { sawPage = true; outcome = Outcome.WAIT }
                ev.packageName == ROKID_PKG && ev.className == ROKID_HOME && sawPage -> outcome = Outcome.RETURN
            }
        }
        return outcome
    }
}
