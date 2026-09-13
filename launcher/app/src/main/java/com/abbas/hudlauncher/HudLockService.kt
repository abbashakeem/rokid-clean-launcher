package com.abbas.hudlauncher

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal accessibility service whose ONLY job is to turn the display off on request, via the
 * system lock-screen action. That is the one way a normal app can really power the panel down while
 * letting the system wake it back on touch or the power button.
 *
 * Deliberately does NOT filter key events and declares no key-event capability, so unlike the
 * earlier attempt it cannot intercept or swallow the touchpad. It also ignores all accessibility
 * events. It is inert except when [turnScreenOff] is called.
 *
 * Enable once over USB:
 *   adb shell settings put secure enabled_accessibility_services com.abbas.hudlauncher/.HudLockService
 *   adb shell settings put secure accessibility_enabled 1
 */
class HudLockService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) { /* inert */ }
    override fun onInterrupt() {}

    companion object {
        private const val TAG = "HudLock"
        @Volatile private var instance: HudLockService? = null

        /** True when the service is connected and display-off is available. */
        val available: Boolean get() = instance != null

        /** Powers the display off; the system wakes it normally on the next touch or power press. */
        fun turnScreenOff(): Boolean {
            val svc = instance ?: return false
            return try {
                svc.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } catch (e: Exception) {
                Log.w(TAG, "lock failed: ${e.message}"); false
            }
        }
    }
}
