package com.abbas.hudlauncher

import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
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
    private lateinit var amPmView: TextView
    private lateinit var dateStrip: TextView
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
    private lateinit var scenes: RokidScenes
    private lateinit var media: MediaWatcher
    private lateinit var musicPill: View
    private lateinit var musicState: ImageView
    private lateinit var musicText: TextView
    private lateinit var musicProgress: View
    private var nowPlaying: NowPlaying? = null
    private lateinit var batteryIcon: BatteryView
    private lateinit var batteryText: TextView
    private lateinit var wifiIcon: WifiView
    private var statusJob: Job? = null
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level >= 0) {
                val pct = level * 100 / scale
                batteryIcon.level = pct
                batteryIcon.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                batteryText.text = "$pct%"
            }
        }
    }
    private lateinit var headerViews: List<View>
    private var weatherJob: Job? = null
    private var calendarJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideSystemBars()

        clockView = findViewById(R.id.clock)
        amPmView = findViewById(R.id.ampm)
        dateStrip = findViewById(R.id.date_strip)
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
        batteryIcon = findViewById(R.id.battery_icon)
        batteryText = findViewById(R.id.battery_text)
        wifiIcon = findViewById(R.id.wifi_icon)
        brightnessPanel = findViewById(R.id.brightness_panel)
        brightnessFill = findViewById(R.id.brightness_fill)
        brightnessValue = findViewById(R.id.brightness_value)

        calendarRepo = CalendarRepository(this)
        brightness = BrightnessController(this)
        sleeper = DisplaySleeper(this, Config.IDLE_OFF_MS, findViewById(R.id.band))
        scenes = RokidScenes(this)
        appPicker = AppPicker(this, findViewById(R.id.app_picker), findViewById(R.id.app_rows),
            findViewById(R.id.app_picker_title), scenes)
        musicPill = findViewById(R.id.music_pill)
        musicState = findViewById(R.id.music_state)
        musicText = findViewById(R.id.music_text)
        musicProgress = findViewById(R.id.music_progress)
        musicPill.setOnClickListener { media.togglePlayPause() }
        media = MediaWatcher(this) { np -> runOnUiThread { renderMusic(np) } }
        headerViews = listOf(clockView, amPmView, dateStrip, wxCity, wxTemp, wxCondition, wxFeels, wxIcon)
        // no click sounds on the touchpad bar
        window.decorView.isSoundEffectsEnabled = false
        listOf(btnBrightness, btnHome, btnApps).forEach { it.isSoundEffectsEnabled = false }
        ticker = ClockTicker { face ->
            clockView.text = face.time
            amPmView.text = face.amPm
            dateStrip.text = face.dateStrip
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
        ReturnWatch.disarm(this)        // we are back in front; nothing to watch for
        scenes.bind()
        media.start()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        statusJob = lifecycleScope.launch {
            while (isActive) { updateWifi(); media.refresh(); delay(Config.STATUS_REFRESH_MS) }
        }
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
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        statusJob?.cancel()
        media.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        scenes.unbind()
    }

    /** Rokid shows a now-playing pill in the bar while Bluetooth music is active; ours replaces Home. */
    private fun renderMusic(np: NowPlaying?) {
        nowPlaying = np
        val hadFocus = btnHome.isFocused || musicPill.isFocused
        if (np == null) {
            musicPill.visibility = View.GONE
            btnHome.visibility = View.VISIBLE
            if (hadFocus) btnHome.requestFocus()
            return
        }
        btnHome.visibility = View.GONE
        musicPill.visibility = View.VISIBLE
        musicState.setImageResource(if (np.playing) R.drawable.ic_pause else R.drawable.ic_play)
        musicText.text = if (np.artist.isBlank()) np.title else "${np.title}  ·  ${np.artist}"
        musicText.isSelected = true   // starts the marquee
        musicProgress.post {
            val track = musicProgress.parent as View
            val frac = if (np.durationMs > 0) (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
            musicProgress.layoutParams = musicProgress.layoutParams.apply { width = (track.width * frac).toInt() }
            musicProgress.requestLayout()
        }
        if (hadFocus) musicPill.requestFocus()
    }

    @Suppress("DEPRECATION")
    private fun updateWifi() {
        // networkId/SSID need location permission on API 29+, so check connectivity via the active network
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiIcon.level = if (!onWifi) -1 else WifiManager.calculateSignalLevel(wm.connectionInfo.rssi, 5).coerceIn(0, 4)
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
            row.findViewById<TextView>(R.id.col_when).text = ev.whenText(getString(R.string.all_day))
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

    /**
     * Intercept before the focused view sees the key: otherwise a focused ImageButton eats
     * ENTER itself (click on key-up), so a tap in the app picker would re-trigger the Apps button.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val handledCodes = setOf(
            KeyEvent.KEYCODE_NOTIFICATION, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        )
        if (code !in handledCodes) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handleKey(code)
        return true
    }

    private fun handleKey(keyCode: Int) {
        Log.d(TAG, "key $keyCode picker=${appPicker.isOpen} panel=${brightnessPanel.visibility == View.VISIBLE}")
        sleeper.touch()
        val panelOpen = brightnessPanel.visibility == View.VISIBLE
        when (keyCode) {
            KeyEvent.KEYCODE_NOTIFICATION -> Unit                                   // touch start: only wakes/resets idle
            KeyEvent.KEYCODE_BACK -> when {                                          // double tap
                appPicker.isOpen -> closeAppPicker()
                panelOpen -> hideBrightness()
                else -> sleeper.sleepNow()
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> when {          // single tap
                appPicker.isOpen -> appPicker.launchSelected()
                panelOpen -> hideBrightness()
                else -> currentFocus?.performClick()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> onSwipe(+1, panelOpen)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> onSwipe(-1, panelOpen)
        }
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
            val buttons = listOf(btnBrightness, if (musicPill.visibility == View.VISIBLE) musicPill else btnHome, btnApps)
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
