package com.abbas.hudlauncher

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Weather(val text: String, val isMock: Boolean)

class WeatherRepository {
    private val mock = listOf("22°C · Clear", "18°C · Cloudy", "15°C · Rain", "25°C · Sunny")
    private var mockIndex = 0

    suspend fun fetch(): Weather = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(HudApi.get("/get-weather"))
            Weather(json.getString("text"), isMock = false)
        } catch (e: Exception) {
            Log.w(TAG, "weather unavailable, using mock: ${e.message}")
            val text = mock[mockIndex % mock.size]
            mockIndex++
            Weather(text, isMock = true)
        }
    }

    companion object { private const val TAG = "Weather" }
}
