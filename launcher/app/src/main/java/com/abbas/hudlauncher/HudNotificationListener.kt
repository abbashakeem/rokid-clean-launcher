package com.abbas.hudlauncher

import android.service.notification.NotificationListenerService

/**
 * Holding notification-listener access lets MediaSessionManager hand us the active media sessions
 * (the Bluetooth music session), and the system keeps this service bound and restarts our process
 * if it is killed, which is what [ReturnWatch] relies on. Grant once over USB:
 *   adb shell cmd notification allow_listener com.abbas.hudlauncher/.HudNotificationListener
 */
class HudNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        ReturnWatch.resumeIfArmed(this)
    }
}
