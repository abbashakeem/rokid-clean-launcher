package com.abbas.hudlauncher

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Reads/writes the system screen brightness (0..255, manual mode).
 * Needs WRITE_SETTINGS, granted once over USB:
 *   adb shell appops set com.abbas.hudlauncher WRITE_SETTINGS allow
 */
class BrightnessController(private val context: Context) {
    val canWrite: Boolean get() = Settings.System.canWrite(context)

    fun get(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
    } catch (e: Exception) { 128 }

    /** Returns true if the value was written. */
    fun set(value: Int): Boolean {
        val v = value.coerceIn(MIN, MAX)
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        } catch (e: Exception) {
            Log.w("Brightness", "cannot write brightness: ${e.message}"); false
        }
    }

    companion object {
        const val MIN = 10      // never fully black: the display would look "off"
        const val MAX = 255
        const val STEP = 25
        fun percent(v: Int) = ((v - MIN) * 100 / (MAX - MIN)).coerceIn(0, 100)
    }
}
