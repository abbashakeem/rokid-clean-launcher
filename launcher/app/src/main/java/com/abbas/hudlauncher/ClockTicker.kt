package com.abbas.hudlauncher

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ClockFace(val time: String, val dayAbbrev: String, val dayMonth: String)

/**
 * Fires once per second on the wall-clock boundary. "hh:mm a" forces 12-hour output
 * regardless of the device's 24-hour setting. Date parts render as "WED" and "31 Dec".
 */
class ClockTicker(private val onTick: (ClockFace) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("hh:mm a", Locale.US)
    private val dayFmt = SimpleDateFormat("EEE", Locale.US)
    private val dateFmt = SimpleDateFormat("d MMM", Locale.US)

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            onTick(ClockFace(timeFmt.format(now), dayFmt.format(now).uppercase(Locale.US), dateFmt.format(now)))
            handler.postDelayed(this, 1000L - (now.time % 1000L))
        }
    }

    fun start() { handler.removeCallbacks(tick); handler.post(tick) }
    fun stop() = handler.removeCallbacks(tick)
}
