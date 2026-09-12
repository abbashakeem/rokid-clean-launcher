package com.abbas.hudlauncher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class AgendaEvent(val title: String, val start: Date, val allDay: Boolean) {
    /** "09:30 AM  Standup", "All day  Holiday", or "Tue 2:00 PM  Dentist" for later days. */
    fun display(): String {
        val time = when {
            allDay -> "All day"
            isToday(start) -> TIME_FMT.format(start)
            else -> DAY_TIME_FMT.format(start)
        }
        return "$time  $title"
    }

    companion object {
        private val TIME_FMT = SimpleDateFormat("hh:mm a", Locale.US)
        private val DAY_TIME_FMT = SimpleDateFormat("EEE hh:mm a", Locale.US)
        private fun isToday(d: Date): Boolean {
            val a = Calendar.getInstance().apply { time = d }
            val b = Calendar.getInstance()
            return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
    }
}

data class Agenda(val events: List<AgendaEvent>, val fromCache: Boolean)

class CalendarRepository(context: Context) {
    private val prefs = context.getSharedPreferences("hud", Context.MODE_PRIVATE)

    suspend fun fetch(): Agenda = withContext(Dispatchers.IO) {
        try {
            val body = HudApi.get("/get-calendar?limit=${Config.MAX_EVENTS}")
            prefs.edit().putString(KEY_CACHE, body).apply()
            Agenda(parse(body), fromCache = false)
        } catch (e: Exception) {
            Log.w(TAG, "calendar unavailable, using cache: ${e.message}")
            val cached = prefs.getString(KEY_CACHE, null)
            Agenda(if (cached != null) parse(cached) else emptyList(), fromCache = true)
        }
    }

    private fun parse(body: String): List<AgendaEvent> {
        val arr: JSONArray = JSONObject(body).getJSONArray("events")
        val now = Date()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .mapNotNull { o ->
                val start = parseIso(o.getString("start_time")) ?: return@mapNotNull null
                val end = parseIso(o.getString("end_time")) ?: start
                if (end.before(now)) return@mapNotNull null // cache may be stale
                AgendaEvent(o.getString("title"), start, o.optBoolean("all_day", false))
            }
            .sortedBy { it.start }
            .take(Config.MAX_EVENTS)
    }

    /** Accepts 2030-01-01T09:00:00Z, +00:00, or +0000 offsets (API 28-safe, no java.time). */
    private fun parseIso(s: String): Date? {
        val normalised = s.replace("Z", "+0000")
            .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
            .replace(Regex("\\.\\d+"), "")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return try { fmt.parse(normalised) } catch (e: Exception) {
            Log.w(TAG, "bad date $s"); null
        }
    }

    companion object {
        private const val TAG = "Calendar"
        private const val KEY_CACHE = "calendar_json"
    }
}
