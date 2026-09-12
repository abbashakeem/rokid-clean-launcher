package com.abbas.hudlauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

    // header
    private lateinit var clockView: TextView
    private lateinit var dateDay: TextView
    private lateinit var dateNum: TextView
    private lateinit var wxCity: TextView
    private lateinit var wxTemp: TextView
    private lateinit var wxCondition: TextView
    private lateinit var wxFeels: TextView
    private lateinit var wxIcon: ImageView
    private lateinit var wxViews: List<View>
    // agenda + bar
    private lateinit var agendaView: LinearLayout
    private lateinit var barView: View
    private lateinit var btnBrightness: ImageButton
    private lateinit var btnHome: ImageButton
    private lateinit var btnApps: ImageButton
    // brightness panel
    private lateinit var brightnessPanel: View
    private lateinit var brightnessFill: View
    private lateinit var brightnessValue: TextView

    private lateinit var ticker: ClockTicker
    private val weatherRepo = WeatherRepository()
    private lateinit var calendarRepo: CalendarRepository
    private lateinit var brightness: BrightnessController
    private var weatherJob: Job? = null
    private var calendarJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        clockView = findViewById(R.id.clock)
        dateDay = findViewById(R.id.date_day)
        dateNum = findViewById(R.id.date_num)
        wxCity = findViewById(R.id.wx_city)
        wxTemp = findViewById(R.id.wx_temp)
        wxCondition = findViewById(R.id.wx_condition)
        wxFeels = findViewById(R.id.wx_feels)
        wxIcon = findViewById(R.id.wx_icon)
        wxViews = listOf(wxCity, wxTemp, wxCondition, wxFeels, wxIcon)
        agendaView = findViewById(R.id.agenda)
        barView = findViewById(R.id.bar)
        btnBrightness = findViewById(R.id.btn_brightness)
        btnHome = findViewById(R.id.btn_home)
        btnApps = findViewById(R.id.btn_apps)
        brightnessPanel = findViewById(R.id.brightness_panel)
        brightnessFill = findViewById(R.id.brightness_fill)
        brightnessValue = findViewById(R.id.brightness_value)

        calendarRepo = CalendarRepository(this)
        brightness = BrightnessController(this)
        ticker = ClockTicker { face ->
            clockView.text = face.time
            dateDay.text = face.dayAbbrev
            dateNum.text = face.dayMonth
        }

        btnBrightness.setOnClickListener { showBrightness() }
        btnHome.setOnClickListener { refreshNow() }
        btnApps.setOnClickListener { openRokidLauncher() }
        btnHome.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        ticker.start()
        weatherJob = lifecycleScope.launch {
            while (isActive) { renderWeather(weatherRepo.fetch()); delay(Config.WEATHER_REFRESH_MS) }
        }
        calendarJob = lifecycleScope.launch {
            while (isActive) { renderAgenda(calendarRepo.fetch()); delay(Config.CALENDAR_REFRESH_MS) }
        }
    }

    override fun onPause() {
        super.onPause()
        ticker.stop(); weatherJob?.cancel(); calendarJob?.cancel()
    }

    private fun refreshNow() {
        weatherJob?.cancel(); calendarJob?.cancel()
        weatherJob = lifecycleScope.launch { renderWeather(weatherRepo.fetch()) }
        calendarJob = lifecycleScope.launch { renderAgenda(calendarRepo.fetch()) }
    }

    // ---------- rendering ----------

    private fun renderWeather(w: Weather) {
        wxCity.text = w.city
        wxTemp.text = "${w.temp}°"
        wxCondition.text = w.condition
        wxFeels.text = getString(R.string.feels_like, w.feelsLike)
        wxIcon.setImageResource(w.iconRes)
        wxViews.forEach { it.alpha = if (w.isMock) 0.5f else 1f }
    }

    private fun renderAgenda(agenda: Agenda) {
        agendaView.removeAllViews()
        val inflater = LayoutInflater.from(this)
        if (agenda.events.isEmpty()) {
            val row = inflater.inflate(R.layout.row_event, agendaView, false)
            row.findViewById<TextView>(R.id.col_title).text =
                getString(if (agenda.fromCache) R.string.calendar_offline else R.string.no_events)
            agendaView.addView(row)
        }
        for (ev in agenda.events) {
            val row = inflater.inflate(R.layout.row_event, agendaView, false)
            row.findViewById<TextView>(R.id.col_day).text = ev.dayAbbrev
            row.findViewById<TextView>(R.id.col_time).text =
                if (ev.allDay) getString(R.string.all_day) else ev.timeText
            row.findViewById<TextView>(R.id.col_title).text = ev.title
            agendaView.addView(row)
        }
        agendaView.alpha = if (agenda.fromCache) 0.6f else 1f
    }

    // ---------- bottom bar actions ----------

    private fun openRokidLauncher() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(ROKID_LAUNCHER_PKG, ROKID_LAUNCHER_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (e: Exception) {
            Log.w(TAG, "Rokid launcher not found: ${e.message}")
            Toast.makeText(this, "Rokid launcher not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBrightness() {
        if (!brightness.canWrite) {
            Toast.makeText(this, R.string.brightness_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        barView.visibility = View.INVISIBLE
        brightnessPanel.visibility = View.VISIBLE
        renderBrightness(brightness.get())
    }

    private fun hideBrightness() {
        brightnessPanel.visibility = View.GONE
        barView.visibility = View.VISIBLE
        btnBrightness.requestFocus()
    }

    private fun adjustBrightness(delta: Int) {
        val v = (brightness.get() + delta).coerceIn(BrightnessController.MIN, BrightnessController.MAX)
        if (brightness.set(v)) renderBrightness(v)
    }

    private fun renderBrightness(v: Int) {
        val pct = BrightnessController.percent(v)
        brightnessValue.text = getString(R.string.brightness_pct, pct)
        brightnessFill.post {
            val track = brightnessFill.parent as View
            brightnessFill.layoutParams = brightnessFill.layoutParams.apply { width = track.width * pct / 100 }
            brightnessFill.requestLayout()
        }
    }

    // ---------- touchpad keys (ROKID PSOC-TP: DPAD, ENTER, BACK) ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.d(TAG, "keyDown $keyCode panel=${brightnessPanel.visibility == View.VISIBLE}")
        if (brightnessPanel.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> { adjustBrightness(-BrightnessController.STEP); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> { adjustBrightness(+BrightnessController.STEP); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { hideBrightness(); return true }
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) return true   // launcher swallows Back
        return super.onKeyDown(keyCode, event)              // DPAD moves focus between bar buttons
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val TAG = "HudMain"
        private const val ROKID_LAUNCHER_PKG = "com.rokid.os.sprite.launcher"
        private const val ROKID_LAUNCHER_ACTIVITY = "com.rokid.os.sprite.launcher.main.SpriteMainActivity"
    }
}
