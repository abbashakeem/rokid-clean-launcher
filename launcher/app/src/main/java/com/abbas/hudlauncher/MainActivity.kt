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
import android.os.Handler
import android.os.Looper
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
    private lateinit var btnMessages: ImageButton
    /// Warns that display-off is unavailable, rather than the glasses silently never sleeping.
    private lateinit var lockWarning: ImageView
    private lateinit var btnBrightness: ImageButton
    private lateinit var phoneLink: ImageView
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
    private lateinit var configRepo: ConfigRepository
    private lateinit var updates: UpdateChecker
    private val cfg: LauncherConfig get() = configRepo.current
    private var configJob: Job? = null
    private var lastWeather: Weather? = null
    // navigation card
    private lateinit var navCard: View
    private lateinit var navIcon: ImageView
    private lateinit var navDistance: TextView
    private lateinit var navRoad: TextView
    private lateinit var navSummary: TextView
    private lateinit var navMap: ImageView
    private lateinit var navClock: TextView
    private lateinit var navAmPm: TextView
    private lateinit var navDate: TextView
    private lateinit var navWxTemp: TextView
    private lateinit var navWxIcon: ImageView
    private lateinit var navWxCond: TextView
    private lateinit var navMuted: View
    private lateinit var navMutedText: View
    private var navSavedVolume = -1
    private var navLastStepKey: String? = null
    private var navWokeNear = false
    private var navHolding = false
    private val navHandler = Handler(Looper.getMainLooper())
    private val navSleepRunnable = Runnable { navHolding = false; sleeper.release() }
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
    /**
     * Restart the idle timer whenever the panel comes back on.
     *
     * Waking the display does not reliably run onResume: the activity is often never paused when
     * the screen goes off, so nothing reschedules the idle countdown and the HUD then stays lit
     * forever. SCREEN_ON is the one signal that always fires, so hang the timer off that.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "screen on; restarting idle timer")
                    sleeper.touch()
                }
                Intent.ACTION_SCREEN_OFF -> sleeper.onPause()
            }
        }
    }

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
        btnMessages = findViewById(R.id.btn_messages)
        btnBrightness = findViewById(R.id.btn_brightness)
        phoneLink = findViewById(R.id.phone_link)
        lockWarning = findViewById(R.id.lock_warning)
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
        configRepo = HudApp.instance.configRepo
        navCard = findViewById(R.id.nav_card)
        navIcon = findViewById(R.id.nav_icon)
        navDistance = findViewById(R.id.nav_distance)
        navRoad = findViewById(R.id.nav_road)
        navSummary = findViewById(R.id.nav_summary)
        navMap = findViewById(R.id.nav_map)
        navClock = findViewById(R.id.nav_clock)
        navAmPm = findViewById(R.id.nav_ampm)
        navDate = findViewById(R.id.nav_date)
        navWxTemp = findViewById(R.id.nav_wx_temp)
        navWxIcon = findViewById(R.id.nav_wx_icon)
        navWxCond = findViewById(R.id.nav_wx_cond)
        navMuted = findViewById(R.id.nav_muted)
        navMutedText = findViewById(R.id.nav_muted_text)
        scenes = HudApp.instance.scenes
        scenes.onWeather = { if (cfg.weatherSource == "rokid") renderWeather(weatherRepo.fromRokid(it)) }
        scenes.onSchedule = { if (cfg.calendarSource != "api") lifecycleScope.launch { renderAgenda(calendarRepo.fetch(cfg.calendarSource, it, cfg.maxEvents)) } }
        scenes.onNavStart = { onNavStart(it) }
        scenes.onNavUpdate = { onNavUpdate(it) }
        scenes.onNavStop = { onNavStop() }
        scenes.onNavMap = { mode, png -> onNavMap(mode, png) }
        scenes.onPhoneLink = { renderPhoneLink() }
        updates = UpdateChecker(this)
        appPicker = AppPicker(this, findViewById(R.id.app_picker), findViewById(R.id.app_rows),
            findViewById(R.id.app_picker_title), scenes, extraEntries = {
                // an update entry appears at the front of the carousel only when one is downloaded
                updates.pending?.let { u ->
                    listOf(AppEntry("Update to ${u.versionName}", R.drawable.ic_update) {
                        lifecycleScope.launch { updates.installPreferSilent() }
                    })
                } ?: emptyList()
            })
        musicPill = findViewById(R.id.music_pill)
        musicState = findViewById(R.id.music_state)
        musicText = findViewById(R.id.music_text)
        musicProgress = findViewById(R.id.music_progress)
        musicPill.setOnClickListener { media.togglePlayPause() }
        media = MediaWatcher(this) { np -> runOnUiThread { renderMusic(np) } }
        // debug nav feed lives for the whole activity, like the real binder callbacks
        if (BuildConfig.DEBUG) registerReceiver(debugNavReceiver, IntentFilter("com.abbas.hudlauncher.DEBUG_NAV"), Context.RECEIVER_EXPORTED)
        if (BuildConfig.DEBUG) registerReceiver(micTestReceiver, IntentFilter("com.abbas.hudlauncher.MIC_TEST"), Context.RECEIVER_EXPORTED)
        if (BuildConfig.DEBUG) registerReceiver(camTestReceiver, IntentFilter("com.abbas.hudlauncher.CAM_TEST"), Context.RECEIVER_EXPORTED)
        headerViews = listOf(clockView, amPmView, dateStrip, wxCity, wxTemp, wxCondition, wxFeels, wxIcon)
        // no click sounds on the touchpad bar
        window.decorView.isSoundEffectsEnabled = false
        listOf(btnMessages, btnBrightness, btnHome, btnApps).forEach { it.isSoundEffectsEnabled = false }
        ticker = ClockTicker { face ->
            clockView.text = face.time
            amPmView.text = face.amPm
            dateStrip.text = face.dateStrip
            navClock.text = face.time
            navAmPm.text = face.amPm
            navDate.text = face.dateStrip
        }

        btnMessages.setOnClickListener { onSpareButton() }
        btnBrightness.setOnClickListener { showBrightness() }
        btnHome.setOnClickListener { refreshNow() }
        btnApps.setOnClickListener { openAppPicker() }
        btnHome.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        sleeper.idleMs = cfg.idleOffSeconds * 1000L
        configJob = lifecycleScope.launch {
            while (isActive) {
                val before = cfg
                val after = configRepo.refresh()
                if (after != before) applyConfig()
                if (after.autoUpdate && updates.pending == null) updates.check()
                delay(Config.CONFIG_REFRESH_MS)
            }
        }
        if (appPicker.isOpen) appPicker.close()
        if (scenes.navActive && cfg.navCard != "off") navCardWanted = true
        applyVisibility()
        ticker.start()
        sleeper.onResume()
        setSystemClickSounds(false)
        ReturnWatch.disarm(this)        // we are back in front; nothing to watch for
        isInForeground = true
        // we are cold-started every time a Rokid scene force-stops us, so let the first frame draw
        // before binding services and registering receivers
        window.decorView.post {
            if (!isInForeground) return@post
            scenes.bind()
            media.start()
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        }
        statusJob = lifecycleScope.launch {
            while (isActive) { updateWifi(); renderPhoneLink(); media.refresh(); delay(Config.STATUS_REFRESH_MS) }
        }
        weatherJob = lifecycleScope.launch {
            while (isActive) { refreshWeather(); applyAutoDim(); delay(Config.WEATHER_REFRESH_MS) }
        }
        calendarJob = lifecycleScope.launch {
            while (isActive) { renderAgenda(calendarRepo.fetch(cfg.calendarSource, scenes.schedule, cfg.maxEvents)); delay(Config.CALENDAR_REFRESH_MS) }
        }
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
        ticker.stop(); weatherJob?.cancel(); calendarJob?.cancel(); configJob?.cancel()
        sleeper.onPause()
        setSystemClickSounds(true)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        statusJob?.cancel()
        media.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) try { unregisterReceiver(debugNavReceiver) } catch (_: Exception) {}
        if (BuildConfig.DEBUG) try { unregisterReceiver(micTestReceiver) } catch (_: Exception) {}
        if (BuildConfig.DEBUG) try { unregisterReceiver(camTestReceiver) } catch (_: Exception) {}
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

    /** Lit when the Rokid app link (GATT) is up, or as a fallback when the phone's Bluetooth audio is connected. */
    @Suppress("MissingPermission")
    private fun renderPhoneLink() {
        val audioLinked = try {
            val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
            // The glasses are the audio *sink* / HFP *client*; those profile ids are hidden constants.
            val profiles = intArrayOf(A2DP_SINK, HEADSET_CLIENT, android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET)
            val states = profiles.map { it to bt?.getProfileConnectionState(it) }
            bt != null && bt.isEnabled && states.any { it.second == android.bluetooth.BluetoothProfile.STATE_CONNECTED }
        } catch (e: Exception) { Log.w(TAG, "bt query failed: $e"); false }
        val linked = scenes.phoneLinked || audioLinked
        Log.d(TAG, "phone link: gatt=${scenes.phoneLinked} audio=$audioLinked")
        phoneLink.alpha = if (linked) 1f else 0.3f

        // Display-off degrades to a no-op when the accessibility service is not enabled, which has
        // happened repeatedly after package installs. Show it instead of leaving the wearer to
        // wonder why the glasses never sleep.
        //   adb shell settings put secure enabled_accessibility_services \
        //     com.abbas.hudlauncher/.HudLockService
        //   adb shell settings put secure accessibility_enabled 1
        lockWarning.visibility = if (HudLockService.available) View.GONE else View.VISIBLE
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
        weatherJob = lifecycleScope.launch { configRepo.refresh(); applyConfig(); refreshWeather() }
        calendarJob = lifecycleScope.launch { renderAgenda(calendarRepo.fetch(cfg.calendarSource, scenes.schedule, cfg.maxEvents)) }
    }

    private suspend fun refreshWeather() {
        if (cfg.weatherSource == "rokid" || cfg.calendarSource != "api") scenes.requestPhoneData()
        val rokid = scenes.weather
        if (cfg.weatherSource == "rokid" && rokid != null) { renderWeather(weatherRepo.fromRokid(rokid)); return }
        val api = weatherRepo.fetch()
        // the phone's weather may have arrived while the backend request was in flight; it wins
        val late = scenes.weather
        if (cfg.weatherSource == "rokid" && late != null) renderWeather(weatherRepo.fromRokid(late)) else renderWeather(api)
    }

    /** Re-apply settings that are not read on the fly. */
    private fun applyConfig() {
        sleeper.idleMs = cfg.idleOffSeconds * 1000L
        sleeper.touch()
        applyAutoDim()
        lifecycleScope.launch { refreshWeather(); renderAgenda(calendarRepo.fetch(cfg.calendarSource, scenes.schedule, cfg.maxEvents)) }
        if (cfg.navCard == "off") onNavStop()
        applyVisibility()
        if (scenes.navActive && navCard.visibility == View.VISIBLE) { if (cfg.navMuteVoice) applyNavMute(true) else applyNavMute(false) }
    }

    /** Auto-dim: day/night panel brightness from the backend weather's sunrise/sunset. */
    private fun applyAutoDim() {
        if (!cfg.autoDim) return
        val w = lastWeather ?: return
        if (w.sunriseMs == 0L || w.sunsetMs == 0L) return
        val nowMs = System.currentTimeMillis()
        // sunrise/sunset are for today (UTC instants); compare time-of-day so stale values still work
        val day = java.util.Calendar.getInstance()
        fun tod(ms: Long): Int { val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }; return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE) }
        val now = tod(nowMs); val rise = tod(w.sunriseMs); val set = tod(w.sunsetMs)
        val isDay = now in rise..set
        val target = if (isDay) cfg.brightnessDay else cfg.brightnessNight
        if (brightness.get() != target && brightness.canWrite) { brightness.set(target); Log.d(TAG, "auto-dim -> $target (${if (isDay) "day" else "night"})") }
    }

    // ---------- rendering ----------

    private fun renderWeather(w: Weather) {
        lastWeather = if (w.isMock) lastWeather else w
        wxCity.text = w.city
        wxTemp.text = "${w.temp}°"
        wxCondition.text = w.condition
        wxFeels.text = w.thirdLine()
        wxIcon.setImageResource(w.iconRes)
        navWxTemp.text = "${w.temp}°"
        navWxIcon.setImageResource(w.iconRes)
        navWxCond.text = w.condition
        wxViews.forEach { it.alpha = if (w.isMock) 0.6f else 1f }   // mock data reads dimmer
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
            row.findViewById<TextView>(R.id.col_title).apply {
                text = ev.title
                if (cfg.marqueeTitles) { ellipsize = android.text.TextUtils.TruncateAt.MARQUEE; isSingleLine = true; marqueeRepeatLimit = -1; isSelected = true }
            }
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

    /**
     * The spare bottom-bar button. During a route it returns to the navigation card, which is the
     * whole reason it exists: leave the card by accident and you need a way back. Otherwise it
     * opens whichever app has been chosen in settings, falling back to the app picker when nothing
     * is set or the chosen app is gone, so it is never a button that does nothing.
     */
    private fun onSpareButton() {
        if (scenes.navActive && cfg.navCard != "off") { navCardWanted = true; applyVisibility(); return }
        val entry = appPicker.entryFor(cfg.shortcutApp)
        if (entry != null) {
            try { entry.action(); return } catch (e: Exception) {
                Log.w(TAG, "shortcut ${entry.label} failed: ${e.message}")
            }
        }
        openAppPicker()
    }


    /**
     * One place decides what shows: picker > messages > nav card > home. Avoids the overlay bugs
     * you get when each panel restores the header on its own.
     */
    private var navCardWanted = false
    private fun applyVisibility() {
        val picker = appPicker.isOpen
        val nav = navCardWanted && !picker
        val home = !picker && !nav
        navCard.visibility = if (nav) View.VISIBLE else View.GONE
        headerViews.forEach { it.visibility = if (home) View.VISIBLE else View.INVISIBLE }
        agendaView.visibility = if (home) View.VISIBLE else View.INVISIBLE
        // Always present so the bar does not reflow: it is the "back to navigation" control during
        // a route and a shortcut to a chosen app the rest of the time.
        val navActive = scenes.navActive && cfg.navCard != "off"
        btnMessages.visibility = View.VISIBLE
        val shortcut = appPicker.entryFor(cfg.shortcutApp)
        btnMessages.setImageResource(
            if (navActive) R.drawable.nav_straight
            else shortcut?.iconRes ?: R.drawable.ic_shortcut_none)
        btnMessages.contentDescription =
            if (navActive) getString(R.string.btn_navigation) else shortcut?.label ?: getString(R.string.btn_apps)
    }

    /** Like Rokid's app page: header and agenda hidden, carousel centred, bar stays with Apps focused. */
    private fun openAppPicker() {
        btnApps.requestFocus()
        appPicker.open()
        applyVisibility()
    }

    private fun closeAppPicker() {
        appPicker.close()
        applyVisibility()
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
        // Unfiltered, so a key we do not yet handle still shows up. KEYCODE_SPRITE_FUNCTION is
        // claimed by SingleKeyGesture at the policy layer, so it may never reach us at all - this
        // is how we find out rather than inferring from an empty log.
        if (BuildConfig.DEBUG && event.action == KeyEvent.ACTION_DOWN) {
            Log.d(TAG, "dispatchKey code=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})")
        }
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
                navCardWanted && navCard.visibility == View.VISIBLE -> { navCardWanted = false; applyVisibility() }
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
            val buttons = listOf(btnMessages, btnBrightness, if (musicPill.visibility == View.VISIBLE) musicPill else btnHome, btnApps)
            val i = buttons.indexOf(currentFocus).let { if (it < 0) 2 else it }
            buttons[(i + direction).coerceIn(0, buttons.lastIndex)].requestFocus()
        }
    }

    // ---------- navigation card ----------

    /**
     * Debug hook (debug builds only): simulate the phone's navigation feed over adb, e.g.
     *   adb shell am broadcast -a com.abbas.hudlauncher.DEBUG_NAV --ei icon 3 --ei step 350 --es road "Chapel St" --ei remain 4200 --ei secs 780 --ei speed 42
     *   adb shell am broadcast -a com.abbas.hudlauncher.DEBUG_NAV --es cmd stop
     */
    /** Debug-only: one still capture, written to the app's files dir. */
    private val camTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            CameraProbe.capture(this@MainActivity) { bytes ->
                if (bytes == null) { Log.w(TAG, "CAM: capture failed"); return@capture }
                try {
                    java.io.File(filesDir, "shot.jpg").writeBytes(bytes)
                    Log.d(TAG, "CAM: wrote ${bytes.size} bytes to shot.jpg")
                } catch (t: Throwable) { Log.w(TAG, "CAM: write failed: ${t.message}") }
            }
        }
    }

    /** Debug-only: see MicProbe. */
    private val micTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { AssistantMic.toggle(this@MainActivity) }
    }

    private val debugNavReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Log.d(TAG, "debug nav broadcast ${i.extras?.keySet()?.joinToString()}")
            when (i.getStringExtra("cmd")) {
                "stop" -> onNavStop()
                "start" -> { scenes.onNavAny?.invoke(); onNavStart(i.getStringExtra("road") ?: "Destination") }
                "map" -> i.getStringExtra("map64")?.let { onNavMap(i.getStringExtra("mode") ?: "0", android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }
                else -> onNavUpdate(NavUpdate(i.getIntExtra("icon", 9), null, i.getStringExtra("road") ?: "", "",
                    i.getIntExtra("step", 300), i.getIntExtra("remain", 5000), i.getIntExtra("secs", 900), i.getIntExtra("speed", 40)))
            }
        }
    }

    private fun onNavStart(destination: String) {
        if (cfg.navCard == "off") return
        navLastStepKey = null
        navRoad.text = destination
        navDistance.text = ""
        navSummary.text = ""
        navMap.visibility = View.GONE
        showNavCard(true)
        applyNavMute(true)
        wakeForNav()
    }

    private fun onNavMap(mode: String, png: ByteArray) {
        if (cfg.navCard == "off") return
        if (!navCardWanted) showNavCard(true)
        try {
            navMap.colorFilter = if (cfg.navMapTint) MapTint.colorFilter else null
            navMap.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size))
            navMap.visibility = View.VISIBLE
        } catch (e: Exception) { Log.w(TAG, "bad map image: ${e.message}") }
    }

    /**
     * iOS ducks music for the voice prompt and sometimes never restores it. Re-sending our media volume
     * over AVRCP a few seconds after an instruction can snap the phone out of the ducked state.
     */
    private val volumeNudge = Runnable {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val v = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            if (v > 0) { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v - 1, 0); am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0) }
            else { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v + 1, 0); am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0) }
            Log.d(TAG, "volume nudge at $v")
        } catch (e: Exception) { Log.w(TAG, "nudge: ${e.message}") }
    }
    private fun scheduleVolumeNudge() { navHandler.removeCallbacks(volumeNudge); navHandler.postDelayed(volumeNudge, 8_000) }

    /** The guidance voice is generated on the phone and streamed over Bluetooth; mute the glasses' media stream for the route. */
    private fun applyNavMute(navOn: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        try {
            if (navOn && cfg.navMuteVoice) {
                if (navSavedVolume < 0) navSavedVolume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
                navMuted.visibility = View.VISIBLE; navMutedText.visibility = View.VISIBLE
            } else if (navSavedVolume >= 0) {
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, navSavedVolume, 0)
                navSavedVolume = -1
                navMuted.visibility = View.GONE; navMutedText.visibility = View.GONE
            }
        } catch (e: Exception) { Log.w(TAG, "volume: ${e.message}") }
    }

    private fun onNavUpdate(u: NavUpdate) {
        Log.d(TAG, "nav update icon=${u.iconType} step=${u.stepRemainM} road=${u.nextRoadName} mode=${cfg.navCard}")
        if (cfg.navCard == "off") return
        if (!navCardWanted) showNavCard(true)
        if (u.iconPng != null) {
            try { navIcon.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(u.iconPng, 0, u.iconPng.size)) } catch (_: Exception) { navIcon.setImageResource(navIconFor(u.iconType)) }
        } else navIcon.setImageResource(navIconFor(u.iconType))
        navDistance.text = formatDistance(u.stepRemainM)
        navRoad.text = if (u.iconType == 15) "Arrive: ${u.nextRoadName}" else u.nextRoadName.ifBlank { u.curRoadName }
        navRoad.isSelected = true
        val mins = (u.routeRemainS + 59) / 60
        navSummary.text = "${formatDistance(u.routeRemainM)}  ·  ${if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"}  ·  ${u.speedKmh} km/h"

        // Smart mode: wake once for a new instruction, once more when the turn comes within range,
        // then sleep navOffSeconds later. Repeated updates for the same step do not re-wake.
        val stepKey = "${u.iconType}|${u.nextRoadName}"
        val newStep = stepKey != navLastStepKey
        if (newStep) { navLastStepKey = stepKey; navWokeNear = false }
        if (newStep && cfg.navVolumeNudge) scheduleVolumeNudge()
        val hasData = u.stepRemainM > 0 || u.iconType > 0
        when (cfg.navCard) {
            "always" -> { if (!navHolding) { navHolding = true; sleeper.holdOn() }; if (newStep) sleeper.wake() }
            "smart" -> {
                if (newStep && hasData) wakeForNav()
                else if (!navWokeNear && hasData && u.stepRemainM <= cfg.navWakeDistanceM) { navWokeNear = true; wakeForNav() }
            }
        }
    }

    private fun onNavStop() {
        applyNavMute(false)
        showNavCard(false)
        applyVisibility()
        navHandler.removeCallbacks(navSleepRunnable)
        if (navHolding) { navHolding = false; sleeper.release() }
    }

    /** Turn the panel on and keep it on; schedule sleep after navOffSeconds of no further wake. */
    private fun wakeForNav() {
        if (!navHolding) { navHolding = true; sleeper.holdOn() }
        sleeper.wake()
        navHandler.removeCallbacks(navSleepRunnable)
        navHandler.postDelayed(navSleepRunnable, cfg.navOffSeconds * 1000L)
    }

    private fun showNavCard(show: Boolean) {
        navCardWanted = show
        applyVisibility()
    }

    private fun formatDistance(m: Int): String = if (m >= 1000) String.format(java.util.Locale.US, "%.1f km", m / 1000f) else "$m m"

    /** Amap-style icon types used by the Rokid feed; a PNG from the phone takes precedence when present. */
    private fun navIconFor(type: Int): Int = when (type) {
        2 -> R.drawable.nav_left
        3 -> R.drawable.nav_right
        4 -> R.drawable.nav_slight_left
        5 -> R.drawable.nav_slight_right
        6 -> R.drawable.nav_sharp_left
        7 -> R.drawable.nav_sharp_right
        8 -> R.drawable.nav_uturn
        11, 12 -> R.drawable.nav_roundabout
        15 -> R.drawable.nav_destination
        else -> R.drawable.nav_straight
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
        @Volatile var isInForeground = false
        private const val A2DP_SINK = 11        // BluetoothProfile.A2DP_SINK (hidden)
        private const val HEADSET_CLIENT = 16   // BluetoothProfile.HEADSET_CLIENT (hidden)
        private const val ROKID_LAUNCHER_PKG = "com.rokid.os.sprite.launcher"
        private const val ROKID_LAUNCHER_ACTIVITY = "com.rokid.os.sprite.launcher.main.SpriteMainActivity"
    }
}
