package com.abbas.hudlauncher

import android.service.notification.NotificationListenerService

/**
 * Empty listener: holding notification-listener access is what lets MediaSessionManager hand us
 * the active media sessions (the Bluetooth music session). Grant once over USB:
 *   adb shell cmd notification allow_listener com.abbas.hudlauncher/.HudNotificationListener
 */
class HudNotificationListener : NotificationListenerService()
