package com.abbas.hudlauncher.adb

import android.util.Log
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A minimal ADB client that talks to adbd on this same device over the loopback address, which
 * gives the launcher shell privileges without root. The technique is described in Rokid-Nexus.
 *
 * Requires a one-time setup over USB:
 *   adb tcpip 5555                                  (or setprop persist.adb.tcp.port 5555)
 * and, on the first connection, accepting the key fingerprint prompt on the glasses.
 */
class AdbClient(private val key: AdbKey, private val port: Int = DEFAULT_PORT) : Closeable {
    private var socket: Socket? = null
    private var nextLocalId = 1

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /** Performs the CNXN/AUTH handshake. Throws if adbd is absent or rejects our key. */
    fun connect(timeoutMs: Int = CONNECT_TIMEOUT_MS) {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(LOOPBACK, port), timeoutMs)
        s.soTimeout = READ_TIMEOUT_MS
        socket = s
        val out = s.getOutputStream()
        val input = BufferedInputStream(s.getInputStream())

        AdbMessage.connect().write(out)
        var sentSignature = false
        while (true) {
            val msg = AdbMessage.read(input)
            when (msg.command) {
                AdbMessage.A_CNXN -> {
                    Log.d(TAG, "connected: ${msg.text}")
                    return
                }
                AdbMessage.A_AUTH -> {
                    if (msg.arg0 != AdbMessage.AUTH_TOKEN) throw IllegalStateException("unexpected AUTH ${msg.arg0}")
                    if (!sentSignature) {
                        sentSignature = true
                        AdbMessage.auth(AdbMessage.AUTH_SIGNATURE, key.signToken(msg.payload)).write(out)
                    } else {
                        // Our key is not authorised yet: offering it raises the prompt on the glasses.
                        AdbMessage.auth(AdbMessage.AUTH_RSAPUBLICKEY, key.publicKeyForAdb()).write(out)
                        throw NotAuthorisedException()
                    }
                }
                else -> throw IllegalStateException("unexpected command during handshake")
            }
        }
    }

    /** Runs one shell command and returns everything it printed. */
    fun shell(command: String): String {
        val s = socket ?: throw IllegalStateException("not connected")
        val out = s.getOutputStream()
        val input = BufferedInputStream(s.getInputStream())
        val localId = nextLocalId++
        AdbMessage.open(localId, "shell:$command").write(out)

        val collected = StringBuilder()
        var remoteId = 0
        while (true) {
            val msg = try {
                AdbMessage.read(input)
            } catch (e: Exception) {
                break                                   // stream ended
            }
            when (msg.command) {
                AdbMessage.A_OKAY -> remoteId = msg.arg0
                AdbMessage.A_WRTE -> {
                    collected.append(String(msg.payload))
                    remoteId = msg.arg0
                    AdbMessage.okay(localId, remoteId).write(out)
                }
                AdbMessage.A_CLSE -> {
                    AdbMessage.close(localId, remoteId).write(out)
                    return collected.toString()
                }
            }
        }
        return collected.toString()
    }

    override fun close() {
        try { socket?.close() } catch (e: Exception) { /* already gone */ }
        socket = null
    }

    /** Thrown on the first connection, while the user has yet to accept our key on the glasses. */
    class NotAuthorisedException : Exception("ADB key not yet authorised on this device")

    companion object {
        private const val TAG = "AdbClient"
        private const val LOOPBACK = "127.0.0.1"
        const val DEFAULT_PORT = 5555
        private const val CONNECT_TIMEOUT_MS = 2000
        private const val READ_TIMEOUT_MS = 15000
    }
}
