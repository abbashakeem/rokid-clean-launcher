package com.abbas.hudlauncher

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Keeps the HUD reliably visible and wakeable.
 *
 * An earlier version force-slept the panel (userActivityTimeout reflection, screenBrightness=0,
 * short system timeouts) to mimic Rokid's display-off. On the real glasses that produced a sleep
 * state the touchpad could not wake, bricking input. So this now does the one safe thing: hold the
 * screen on with FLAG_KEEP_SCREEN_ON while the HUD is in front. Auto display-off and on-demand sleep
 * are deliberately no-ops until they can be done with a primitive that wakes reliably on touch.
 *
 * The API is unchanged so callers (nav wake/hold, double-tap) keep compiling; the risky methods
 * simply keep the screen on instead of manipulating power.
 */
class DisplaySleeper(private val activity: Activity, var idleMs: Long, private val content: View) {

    fun onResume() {
        keepOn(true)
        show()
    }

    fun onPause() {
        // nothing to tear down; the window flag clears with the window
    }

    /** Any interaction: ensure the content is visible. No timer, so nothing can blank us. */
    fun touch() = show()

    /** Nav asked to bring the panel up; it is already on. */
    fun wake() = show()

    fun holdOn() = keepOn(true)
    fun release() = keepOn(true)

    /** Display-off is disabled for safety; keep the HUD visible instead of an unwakeable sleep. */
    fun sleepNow() {
        Log.d(TAG, "sleepNow ignored: auto display-off is disabled to keep input wakeable")
        show()
    }

    private fun show() {
        if (content.visibility != View.VISIBLE) content.visibility = View.VISIBLE
        val a = activity.window.attributes
        if (a.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            activity.window.attributes = a.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
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
