package com.abbas.hudlauncher

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Weather(
    val city: String,
    val temp: Int,
    val feelsLike: Int,
    val condition: String,
    val iconCode: String,   // OpenWeatherMap code such as 04d
    val isMock: Boolean,
    val tempMin: Int? = null,
    val tempMax: Int? = null,
    val sunriseMs: Long = 0,
    val sunsetMs: Long = 0,
    val fromRokid: Boolean = false,
) {
    /** Third line: feels-like for the backend source, the day's range for the Rokid feed. */
    fun thirdLine(): String = if (fromRokid && tempMin != null && tempMax != null) "$tempMin° ~ $tempMax°" else "Feels like $feelsLike°"

    /** Map OWM icon codes to bundled vector drawables. */
    val iconRes: Int
        get() {
            val night = iconCode.endsWith("n")
            return when (iconCode.take(2)) {
                "01" -> if (night) R.drawable.wx_clear_night else R.drawable.wx_clear_day
                "02" -> if (night) R.drawable.wx_partly_night else R.drawable.wx_partly_day
                "03", "04" -> R.drawable.wx_clouds
                "09", "10" -> R.drawable.wx_rain
                "11" -> R.drawable.wx_storm
                "13" -> R.drawable.wx_snow
                "50" -> R.drawable.wx_mist
                else -> R.drawable.wx_clouds
            }
        }
}

class WeatherRepository {
    /** Rokid weatherId table (from its launcher) collapsed to our icon codes and labels. */
    fun fromRokid(w: RokidWeather): Weather {
        val id = w.weatherId
        // Rokid's table has no day/night variants; use the clock for the clear/partly icons
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val n = if (hour < 6 || hour >= 19) "n" else "d"
        val (code, label) = when (id) {
            in 1..5 -> "01$n" to "Clear"
            6, 7 -> "02$n" to "Mostly clear"
            in 8..12, in 80..82 -> "03d" to "Cloudy"
            13, 14, 36, 85 -> "04d" to "Overcast"
            in 15..23, in 51..57, in 66..70, 78, 86, in 91..93 -> "10d" to "Rain"
            in 37..45, in 87..90 -> "11d" to "Thunderstorm"
            in 46..50, in 58..63, in 71..77, 24, 25, 94 -> "13d" to "Snow"
            in 26..35, 79, 83, 84 -> "50d" to "Fog"
            else -> "03d" to "Weather"
        }
        return Weather(city = w.address, temp = w.temp.toInt(), feelsLike = w.temp.toInt(), condition = label, iconCode = code,
            isMock = false, tempMin = w.tempLow.toInt(), tempMax = w.tempHigh.toInt(), fromRokid = true)
    }

    private val mock = listOf(
        Weather("Melbourne", 22, 21, "Clear", "01d", true),
        Weather("Melbourne", 18, 16, "Clouds", "04d", true),
        Weather("Melbourne", 15, 13, "Rain", "10d", true),
    )
    private var mockIndex = 0

    suspend fun fetch(): Weather = withContext(Dispatchers.IO) {
        try {
            val j = JSONObject(HudApi.get("/get-weather"))
            Weather(
                city = j.optString("city", ""),
                temp = j.getInt("temp"),
                feelsLike = j.optInt("feels_like", j.getInt("temp")),
                condition = j.optString("description", j.optString("condition", "")),
                iconCode = j.optString("icon", "01d"),
                isMock = false,
                tempMin = j.optInt("temp_min", j.getInt("temp")),
                tempMax = j.optInt("temp_max", j.getInt("temp")),
                sunriseMs = parseIsoMs(j.optString("sunrise")),
                sunsetMs = parseIsoMs(j.optString("sunset")),
            )
        } catch (e: Exception) {
            Log.w(TAG, "weather unavailable, using mock: ${e.message}")
            mock[mockIndex++ % mock.size]
        }
    }

    private fun parseIsoMs(s: String): Long {
        if (s.isBlank()) return 0
        val n = s.replace("Z", "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2").replace(Regex("\\.\\d+"), "")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
        return try { fmt.parse(n)?.time ?: 0 } catch (e: Exception) { 0 }
    }

    companion object { private const val TAG = "Weather" }
}
