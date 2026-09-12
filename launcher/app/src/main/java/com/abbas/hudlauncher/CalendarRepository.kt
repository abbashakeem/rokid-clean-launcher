package com.abbas.hudlauncher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class AgendaEvent(val title: String, val start: Date, val allDay: Boolean) {
    /** "Sun  6:00 AM" (two spaces) or "Fri  all day" for the fixed mono column. */
    fun whenText(allDayLabel: String): String =
        DAY_FMT.format(start) + "  " + if (allDay) allDayLabel else TIME_FMT.format(start)

    companion object {
        private val DAY_FMT = SimpleDateFormat("EEE", Locale.US)
        private val TIME_FMT = SimpleDateFormat("h:mm a", Locale.US)
    }
}

data class Agenda(val events: List<AgendaEvent>, val fromCache: Boolean)

class CalendarRepository(context: Context) {
    private val prefs = context.getSharedPreferences("hud", Context.MODE_PRIVATE)

    /** source: api | rokid | both. Rokid entries come from the phone via the assist server. */
    suspend fun fetch(source: String, rokid: List<RokidSchedule>, maxEvents: Int): Agenda = withContext(Dispatchers.IO) {
        val now = Date()
        val rokidEvents = if (source == "api") emptyList() else rokid
            .filter { it.scheduleTime > now.time - 60 * 60 * 1000 }
            .map { AgendaEvent(it.title, Date(it.scheduleTime), allDay = false) }
        if (source == "rokid") return@withContext Agenda(order(rokidEvents, maxEvents), fromCache = false)
        try {
            val body = HudApi.get("/get-calendar?limit=${maxEvents * 2}")
            prefs.edit().putString(KEY_CACHE, body).apply()
            Agenda(order(parse(body) + rokidEvents, maxEvents), fromCache = false)
        } catch (e: Exception) {
            Log.w(TAG, "calendar unavailable, using cache: ${e.message}")
            val cached = prefs.getString(KEY_CACHE, null)
            Agenda(order((if (cached != null) parse(cached) else emptyList()) + rokidEvents, maxEvents), fromCache = true)
        }
    }

    private fun order(events: List<AgendaEvent>, max: Int): List<AgendaEvent> =
        (events.filter { it.allDay }.sortedBy { it.start } + events.filter { !it.allDay }.sortedBy { it.start })
            .distinctBy { it.title to it.start.time / 60000 }.take(max)

    private fun parse(body: String): List<AgendaEvent> {
        val arr: JSONArray = JSONObject(body).getJSONArray("events")
        val now = Date()
        val events = (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
            val start = parseIso(o.getString("start_time")) ?: return@mapNotNull null
            val end = parseIso(o.getString("end_time")) ?: start
            if (end.before(now)) return@mapNotNull null
            AgendaEvent(o.getString("title"), start, o.optBoolean("all_day", false))
        }
        return events
    }

    /** Accepts 2030-01-01T09:00:00Z, +10:00 or +1000 offsets (API 28-safe, no java.time). */
    private fun parseIso(s: String): Date? {
        val normalised = s.replace("Z", "+0000")
            .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
            .replace(Regex("\\.\\d+"), "")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return try { fmt.parse(normalised) } catch (e: Exception) { Log.w(TAG, "bad date $s"); null }
    }

    companion object {
        private const val TAG = "Calendar"
        private const val KEY_CACHE = "calendar_json"
    }
}
