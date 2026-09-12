package com.abbas.hudlauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
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
    private lateinit var sleeper: DisplaySleeper
    private lateinit var appPicker: AppPicker
    private lateinit var headerViews: List<View>
    private var weatherJob: Job? = null
    private var calendarJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
        sleeper = DisplaySleeper(this, Config.IDLE_OFF_MS, findViewById(R.id.band))
        appPicker = AppPicker(this, findViewById(R.id.app_picker), findViewById(R.id.app_rows),
            findViewById(R.id.app_picker_title),
            builtIns = listOf(AppEntry(getString(R.string.btn_brightness),
                getDrawable(R.drawable.ic_brightness)!!, action = { showBrightness() })))
        headerViews = listOf(clockView, dateDay, dateNum, wxCity, wxTemp, wxCondition, wxFeels, wxIcon)
        // no click sounds on the touchpad bar
        window.decorView.isSoundEffectsEnabled = false
        listOf(btnBrightness, btnHome, btnApps).forEach { it.isSoundEffectsEnabled = false }
        ticker = ClockTicker { face ->
            clockView.text = face.time
            dateDay.text = face.dayAbbrev
            dateNum.text = face.dayMonth
        }

        btnBrightness.setOnClickListener { showBrightness() }
        btnHome.setOnClickListener { refreshNow() }
        btnApps.setOnClickListener { openAppPicker() }
        btnHome.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (appPicker.isOpen) closeAppPicker()
        ticker.start()
        sleeper.onResume()
        setSystemClickSounds(false)
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
        sleeper.onPause()
        setSystemClickSounds(true)
    }

    /** The tap sound comes from the system sound-effects pool, not our views, so toggle the setting. */
    private fun setSystemClickSounds(enabled: Boolean) {
        try {
            android.provider.Settings.System.putInt(contentResolver,
                android.provider.Settings.System.SOUND_EFFECTS_ENABLED, if (enabled) 1 else 0)
        } catch (e: Exception) { Log.w(TAG, "cannot toggle sound effects: ${e.message}") }
    }

    /** Any touch/key restarts the 5 s idle timer. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        sleeper.touch()
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

    /** Like Rokid's app page: header and agenda hidden, carousel centred, bar stays with Apps focused. */
    private fun openAppPicker() {
        headerViews.forEach { it.visibility = View.INVISIBLE }
        agendaView.visibility = View.INVISIBLE
        btnApps.requestFocus()
        appPicker.open()
    }

    private fun closeAppPicker() {
        appPicker.close()
        headerViews.forEach { it.visibility = View.VISIBLE }
        agendaView.visibility = View.VISIBLE
        btnApps.requestFocus()
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

    // ---------- touchpad keys ----------
    // The ROKID PSOC-TP firmware recognises gestures itself and sends:
    //   touch start -> KEYCODE_NOTIFICATION (83)   single tap -> ENTER
    //   double tap  -> BACK                          swipe fwd -> RIGHT (+ DOWN repeats)
    //   swipe back  -> LEFT (+ UP repeats)

    private var lastSwipeAt = 0L

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.d(TAG, "keyDown $keyCode panel=${brightnessPanel.visibility == View.VISIBLE}")
        sleeper.touch()
        val panelOpen = brightnessPanel.visibility == View.VISIBLE
        if (appPicker.isOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_NOTIFICATION -> return true
                KeyEvent.KEYCODE_BACK -> { closeAppPicker(); return true }          // double tap = back to home
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { appPicker.launchSelected(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> { onSwipe(+1, false); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> { onSwipe(-1, false); return true }
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_NOTIFICATION -> return true                   // touch start: only wakes/resets idle
            KeyEvent.KEYCODE_BACK -> { sleeper.sleepNow(); return true }   // double tap = display off
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (panelOpen) hideBrightness() else currentFocus?.performClick()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> { onSwipe(+1, panelOpen); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> { onSwipe(-1, panelOpen); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) return true
        return super.onKeyUp(keyCode, event)
    }

    /** One swipe arrives as several direction keys; act once per SWIPE_DEBOUNCE_MS. */
    private fun onSwipe(direction: Int, panelOpen: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastSwipeAt < Config.SWIPE_DEBOUNCE_MS) return
        lastSwipeAt = now
        if (appPicker.isOpen) {
            appPicker.move(direction)
        } else if (panelOpen) {
            adjustBrightness(direction * BrightnessController.STEP)
        } else {
            val buttons = listOf(btnBrightness, btnHome, btnApps)
            val i = buttons.indexOf(currentFocus).let { if (it < 0) 1 else it }
            buttons[(i + direction).coerceIn(0, buttons.lastIndex)].requestFocus()
        }
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
