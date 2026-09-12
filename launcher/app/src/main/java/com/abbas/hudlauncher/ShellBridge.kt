package com.abbas.hudlauncher

import android.content.Context
import android.util.Log
import com.abbas.hudlauncher.adb.AdbClient
import com.abbas.hudlauncher.adb.AdbKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs commands as the shell user by speaking ADB to this device's own adbd over loopback.
 *
 * What it buys us, none of which a normal app can do:
 *  - installing our own updates without the system's confirmation dialog
 *  - granting the permissions that otherwise need a USB session
 *
 * Everything degrades quietly: if adbd is not listening or our key has not been accepted, callers
 * fall back to the interactive path. Set up once over USB with `adb tcpip 5555`, then accept the
 * key prompt on the glasses the first time.
 */
object ShellBridge {
    private const val TAG = "ShellBridge"

    /** Permissions the launcher would otherwise need a cable to obtain. */
    private val GRANTS = listOf(
        "appops set %s WRITE_SETTINGS allow",
        "appops set %s GET_USAGE_STATS allow",
        "appops set %s SYSTEM_ALERT_WINDOW allow",
        "appops set %s REQUEST_INSTALL_PACKAGES allow",
        "pm grant %s android.permission.BLUETOOTH_CONNECT",
        "cmd notification allow_listener %s/.HudNotificationListener",
        "settings put secure enabled_accessibility_services %s/.HudAccessibilityService",
        "settings put secure accessibility_enabled 1",
    )

    @Volatile private var available: Boolean? = null

    /** True once we have completed a handshake at least once this boot. */
    fun availability(): Boolean? = available

    private fun <T> withClient(context: Context, block: (AdbClient) -> T): T? {
        val client = AdbClient(AdbKey(context))
        return try {
            client.connect()
            available = true
            block(client)
        } catch (e: AdbClient.NotAuthorisedException) {
            available = false
            Log.i(TAG, "key offered; accept the prompt on the glasses to enable shell access")
            null
        } catch (e: Exception) {
            available = false
            Log.d(TAG, "shell bridge unavailable: ${e.message}")
            null
        } finally {
            client.close()
        }
    }

    suspend fun run(context: Context, command: String): String? = withContext(Dispatchers.IO) {
        withClient(context) { it.shell(command) }
    }

    /** Installs an APK without the system installer prompt. Returns true on success. */
    suspend fun installApk(context: Context, path: String): Boolean = withContext(Dispatchers.IO) {
        val output = withClient(context) { it.shell("pm install -r -d \"$path\"") } ?: return@withContext false
        val ok = output.contains("Success", ignoreCase = true)
        if (!ok) Log.w(TAG, "silent install failed: ${output.take(200)}")
        ok
    }

    /** Applies every permission the launcher needs. Returns the number of commands that ran. */
    suspend fun selfGrant(context: Context): Int = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        withClient(context) { client ->
            GRANTS.count { template ->
                val command = if (template.contains("%s")) template.format(pkg) else template
                try {
                    client.shell(command); true
                } catch (e: Exception) {
                    Log.w(TAG, "grant failed: $command"); false
                }
            }
        } ?: 0
    }
}
