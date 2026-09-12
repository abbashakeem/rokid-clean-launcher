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
) {
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
            )
        } catch (e: Exception) {
            Log.w(TAG, "weather unavailable, using mock: ${e.message}")
            mock[mockIndex++ % mock.size]
        }
    }

    companion object { private const val TAG = "Weather" }
}
