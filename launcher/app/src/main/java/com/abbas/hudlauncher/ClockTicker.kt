package com.abbas.hudlauncher

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ClockFace(val time: String, val amPm: String, val dateStrip: String)

/**
 * Fires once per second on the wall-clock boundary. "hh:mm a" forces 12-hour output
 * regardless of the device's 24-hour setting. The date strip renders as "SAT 12 SEP".
 */
class ClockTicker(private val onTick: (ClockFace) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("h:mm", Locale.US)     // no leading zero, 12-hour
    private val amPmFmt = SimpleDateFormat("a", Locale.US)
    private val dateFmt = SimpleDateFormat("EEE d MMM", Locale.US)

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            onTick(ClockFace(timeFmt.format(now), amPmFmt.format(now), dateFmt.format(now).uppercase(Locale.US)))
            handler.postDelayed(this, 1000L - (now.time % 1000L))
        }
    }

    fun start() { handler.removeCallbacks(tick); handler.post(tick) }
    fun stop() = handler.removeCallbacks(tick)
}
