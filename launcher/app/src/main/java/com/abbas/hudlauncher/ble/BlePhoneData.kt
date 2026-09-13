package com.abbas.hudlauncher.ble

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Events pushed from the iPhone app over BLE, persisted so they survive our frequent restarts.
 * Kept deliberately separate from the backend/Rokid calendar sources; the agenda merges all three.
 */
object BlePhoneData {
    private const val PREFS = "hud"
    private const val KEY = "ble_calendar"

    @Volatile private var cache: JSONArray? = null

    fun storeCalendar(context: Context, events: JSONArray) {
        cache = events
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, events.toString()).apply()
    }

    /** Raw event objects: {title, start (epoch ms or ISO), end, allDay, location}. */
    fun events(context: Context): JSONArray {
        cache?.let { return it }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return JSONArray()
        return try { JSONArray(raw).also { cache = it } } catch (e: Exception) { JSONArray() }
    }

    fun hasData(context: Context): Boolean = events(context).length() > 0
}
