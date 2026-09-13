package com.abbas.hudlauncher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Real display-off, done safely.
 *
 * Turning the panel off uses [HudLockService] (the system lock-screen action), which the system
 * then wakes normally on the next touch or power press. There is NO direct power/brightness
 * manipulation, so it cannot leave the device in an unwakeable state the way the first version did.
 *
 * If the lock service is not enabled, display-off degrades to a no-op and the screen simply stays
 * on (FLAG_KEEP_SCREEN_ON) — never an unwakeable blank.
 */
class DisplaySleeper(private val activity: Activity, var idleMs: Long, private val content: View) {
    private val handler = Handler(Looper.getMainLooper())
    private val offRunnable = Runnable { turnOff("idle") }
    private var holding = false

    fun onResume() {
        keepOn(true)
        content.visibility = View.VISIBLE
        touch()
    }

    fun onPause() {
        handler.removeCallbacks(offRunnable)
    }

    /** Any interaction restarts the idle timer. */
    fun touch() {
        content.visibility = View.VISIBLE
        if (holding) return
        handler.removeCallbacks(offRunnable)
        if (idleMs > 0) handler.postDelayed(offRunnable, idleMs)
    }

    /** Nav wants the panel up and kept on until [release]. */
    fun wake() { holding = false; touch() }
    fun holdOn() { holding = true; handler.removeCallbacks(offRunnable) }
    fun release() { holding = false; touch() }

    /** Double tap: turn the display off now. */
    fun sleepNow() = turnOff("double tap")

    private fun turnOff(why: String) {
        handler.removeCallbacks(offRunnable)
        if (HudLockService.available) {
            Log.d(TAG, "display off ($why)")
            HudLockService.turnScreenOff()
        } else {
            Log.d(TAG, "display-off requested ($why) but lock service not enabled; staying on")
        }
    }

    private fun keepOn(on: Boolean) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val TAG = "Sleeper"
    }
}
