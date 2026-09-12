package com.abbas.hudlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Event-driven replacement for ReturnWatch's usage-stats polling, and a global "go home" gesture.
 * The idea is borrowed from Rokid-Nexus, which drives its HUD from an accessibility service.
 *
 * Two jobs:
 *  - Window changes arrive the instant they happen, so returning from a Rokid scene is immediate
 *    rather than up to one poll interval late.
 *  - Key events are observed (never consumed) so a triple tap anywhere returns to the HUD. The
 *    touchpad has no home key, so without this there is no way back from a non-Rokid app.
 *
 * Enable once over USB:
 *   adb shell settings put secure enabled_accessibility_services com.abbas.hudlauncher/.HudAccessibilityService
 *   adb shell settings put secure accessibility_enabled 1
 */
class HudAccessibilityService : AccessibilityService() {

    private var tapTimes = LongArray(3)
    private var tapIndex = 0
    private var lastReturnAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Intentionally does not auto-return from Rokid scenes. Scenes run inside Rokid's own home
        // task, so its home activity resumes during the transition INTO a scene; reacting to that
        // pulled our launcher in front of the scene and blanked it. ReturnWatch (usage-stats based,
        // with a grace period) owns scene returns. This service exists only for the triple-tap home
        // gesture below, which nothing else can provide.
    }

    /** Observe only: consuming taps would break Rokid's own double-tap-to-exit. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
            val now = System.currentTimeMillis()
            tapTimes[tapIndex % tapTimes.size] = now
            tapIndex++
            val oldest = tapTimes.min()
            if (tapIndex >= tapTimes.size && now - oldest <= TRIPLE_TAP_MS) {
                tapTimes = LongArray(3); tapIndex = 0
                goHome("triple tap")
            }
        }
        return false
    }

    private fun goHome(why: String) {
        val now = System.currentTimeMillis()
        if (now - lastReturnAt < DEBOUNCE_MS) return
        lastReturnAt = now
        Log.d(TAG, "$why -> HUD")
        ReturnWatch.disarm(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "HudA11y"
        private const val ROKID_PKG = "com.rokid.os.sprite.launcher"
        private const val ROKID_HOME = "com.rokid.os.sprite.launcher.main.SpriteMainActivity"
        private const val TRIPLE_TAP_MS = 900L
        private const val DEBOUNCE_MS = 1500L
    }
}
