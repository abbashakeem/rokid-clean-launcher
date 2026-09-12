package com.abbas.hudlauncher

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fires [onTick] once per second, aligned to the wall-clock second boundary.
 * The explicit "hh:mm a" pattern forces 12-hour output regardless of the
 * device's 24-hour setting (DateFormat.is24HourFormat is deliberately ignored).
 */
class ClockTicker(private val onTick: (time: String, date: String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("hh:mm a", Locale.US)
    private val dateFmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            onTick(timeFmt.format(now), dateFmt.format(now))
            val delay = 1000L - (now.time % 1000L)
            handler.postDelayed(this, delay)
        }
    }

    fun start() { handler.removeCallbacks(tick); handler.post(tick) }
    fun stop() = handler.removeCallbacks(tick)
}
