package com.abbas.hudlauncher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Display-off behaviour matching Rokid's launcher: off after [idleMs] idle, or on demand.
 *
 * Rokid Glasses ship with device-admin disabled and no system-level power permission for
 * third-party apps, so we use what a normal app can:
 *  1. WindowManager.LayoutParams.userActivityTimeout (hidden field, set by reflection). While our
 *     window is focused, the power manager uses it instead of the system screen timeout.
 *  2. Fallback: write the system screen-off timeout (WRITE_SETTINGS is already granted for brightness).
 *  3. On demand: drop the window brightness to zero and hide the content, which reads as "off" on
 *     the waveguide, then let (1) put the panel to sleep for real a moment later.
 */
class DisplaySleeper(private val activity: Activity, private val idleMs: Long, private val content: View) {
    private val handler = Handler(Looper.getMainLooper())
    private val blankRunnable = Runnable { blank() }
    private var blanked = false
    private var reflectionWorks = true

    fun onResume() {
        unblank()
        applyWindowTimeout(idleMs)
        touch()
    }

    fun onPause() {
        handler.removeCallbacks(blankRunnable)
    }

    /** Any interaction restarts the idle timer and restores the content if it was blanked. */
    fun touch() {
        unblank()
        handler.removeCallbacks(blankRunnable)
        handler.postDelayed(blankRunnable, idleMs)
    }

    /** Double tap: look off immediately, then the short window timeout switches the panel off. */
    fun sleepNow() {
        handler.removeCallbacks(blankRunnable)
        blank()
        applyWindowTimeout(SLEEP_SOON_MS)
    }

    private fun blank() {
        if (blanked) return
        blanked = true
        content.visibility = View.INVISIBLE
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0f }
    }

    private fun unblank() {
        if (!blanked) return
        blanked = false
        content.visibility = View.VISIBLE
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        applyWindowTimeout(idleMs)
    }

    private fun applyWindowTimeout(ms: Long) {
        if (reflectionWorks) {
            try {
                val lp = activity.window.attributes
                WindowManager.LayoutParams::class.java.getField("userActivityTimeout").setLong(lp, ms)
                activity.window.attributes = lp
                return
            } catch (e: Throwable) {
                reflectionWorks = false
                Log.w(TAG, "userActivityTimeout not accessible (${e.javaClass.simpleName}); using system setting")
            }
        }
        try {
            Settings.System.putInt(activity.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms.toInt())
        } catch (e: Exception) {
            Log.w(TAG, "cannot write screen timeout: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Sleeper"
        private const val SLEEP_SOON_MS = 500L
    }
}
