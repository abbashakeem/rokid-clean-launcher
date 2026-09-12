package com.abbas.hudlauncher

/** Values come from local.properties via BuildConfig; see local.properties.example. */
object Config {
    val baseUrl: String = BuildConfig.HUD_BASE_URL.trimEnd('/')
    const val apiKey: String = BuildConfig.HUD_API_KEY

    const val WEATHER_REFRESH_MS = 10 * 60 * 1000L
    const val CALENDAR_REFRESH_MS = 5 * 60 * 1000L
    const val MAX_EVENTS = 4

    /** Display off after this much idle time, like Rokid's launcher. */
    const val IDLE_OFF_MS = 5_000L
    /** A swipe arrives as several D-pad keys; act on one per this window. */
    const val SWIPE_DEBOUNCE_MS = 300L
}
