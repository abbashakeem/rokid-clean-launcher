package com.abbas.hudlauncher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Settings edited on the backend's /settings page; polled by the glasses. */
data class LauncherConfig(
    val calendarSource: String = "api",      // api | rokid | both
    val weatherSource: String = "api",       // api | rokid
    val maxEvents: Int = 4,
    val idleOffSeconds: Int = 5,
    val autoDim: Boolean = true,
    val brightnessDay: Int = 140,
    val brightnessNight: Int = 60,
    val navCard: String = "smart",           // off | always | smart
    val navOffSeconds: Int = 10,
    val navWakeDistanceM: Int = 300,
    val marqueeTitles: Boolean = true,
    val navMuteVoice: Boolean = false,
    val navVolumeNudge: Boolean = false,
    val navTakeOver: Boolean = true,
    val navMapTint: Boolean = true,
    val autoUpdate: Boolean = true,
    /** App-picker entry the spare bottom-bar button opens; blank opens the picker itself. */
    val shortcutApp: String = "",
) {
    companion object {
        fun fromJson(j: JSONObject) = LauncherConfig(
            calendarSource = j.optString("calendar_source", "api"),
            weatherSource = j.optString("weather_source", "api"),
            maxEvents = j.optInt("max_events", 4),
            idleOffSeconds = j.optInt("idle_off_seconds", 5),
            autoDim = j.optBoolean("auto_dim", true),
            brightnessDay = j.optInt("brightness_day", 140),
            brightnessNight = j.optInt("brightness_night", 60),
            navCard = j.optString("nav_card", "smart"),
            navOffSeconds = j.optInt("nav_off_seconds", 10),
            navWakeDistanceM = j.optInt("nav_wake_distance_m", 300),
            marqueeTitles = j.optBoolean("marquee_titles", true),
            navMuteVoice = j.optBoolean("nav_mute_voice", false),
            navVolumeNudge = j.optBoolean("nav_volume_nudge", false),
            navTakeOver = j.optBoolean("nav_take_over", true),
            navMapTint = j.optBoolean("nav_map_tint", true),
            autoUpdate = j.optBoolean("auto_update", true),
            shortcutApp = j.optString("shortcut_app", ""),
        )
    }
}

class ConfigRepository(context: Context) {
    private val prefs = context.getSharedPreferences("hud", Context.MODE_PRIVATE)
    var current: LauncherConfig = prefs.getString(KEY, null)?.let { runCatching { LauncherConfig.fromJson(JSONObject(it)) }.getOrNull() } ?: LauncherConfig()
        private set

    suspend fun refresh(): LauncherConfig = withContext(Dispatchers.IO) {
        try {
            val body = HudApi.get("/config")
            prefs.edit().putString(KEY, body).apply()
            current = LauncherConfig.fromJson(JSONObject(body))
        } catch (e: Exception) { Log.w(TAG, "config unavailable, keeping ${if (prefs.contains(KEY)) "cached" else "defaults"}: ${e.message}") }
        current
    }

    companion object { private const val TAG = "Config"; private const val KEY = "launcher_config" }
}
