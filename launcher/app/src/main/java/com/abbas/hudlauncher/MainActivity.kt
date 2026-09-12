package com.abbas.hudlauncher

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var clockView: TextView
    private lateinit var weatherView: TextView
    private lateinit var dateView: TextView
    private lateinit var eventViews: List<TextView>

    private lateinit var ticker: ClockTicker
    private val weatherRepo = WeatherRepository()
    private lateinit var calendarRepo: CalendarRepository

    private var weatherJob: Job? = null
    private var calendarJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        clockView = findViewById(R.id.clock)
        weatherView = findViewById(R.id.weather)
        dateView = findViewById(R.id.date)
        eventViews = listOf(findViewById(R.id.event1), findViewById(R.id.event2), findViewById(R.id.event3))

        calendarRepo = CalendarRepository(this)
        ticker = ClockTicker { time, date ->
            clockView.text = time
            dateView.text = date
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        ticker.start()
        weatherJob = lifecycleScope.launch {
            while (isActive) {
                val w = weatherRepo.fetch()
                weatherView.text = w.text
                weatherView.alpha = if (w.isMock) 0.6f else 1f
                delay(Config.WEATHER_REFRESH_MS)
            }
        }
        calendarJob = lifecycleScope.launch {
            while (isActive) {
                render(calendarRepo.fetch())
                delay(Config.CALENDAR_REFRESH_MS)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ticker.stop()
        weatherJob?.cancel()
        calendarJob?.cancel()
    }

    private fun render(agenda: Agenda) {
        eventViews.forEachIndexed { i, view ->
            val ev = agenda.events.getOrNull(i)
            if (ev == null) {
                view.visibility = if (i == 0) View.VISIBLE else View.GONE
                if (i == 0) view.text = getString(
                    if (agenda.fromCache) R.string.calendar_offline else R.string.no_events
                )
            } else {
                view.visibility = View.VISIBLE
                view.text = ev.display()
            }
            view.alpha = if (agenda.fromCache) 0.6f else 1f
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // A launcher must swallow Back so the user can't leave the home screen.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* intentionally empty */ }
}
