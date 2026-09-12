package com.abbas.hudlauncher.adb

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One ADB packet: a 24-byte little-endian header and an optional payload. */
class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0),
) {
    fun write(out: OutputStream) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        header.putInt(payload.sumOf { it.toInt() and 0xFF })   // adb's checksum is a plain byte sum
        header.putInt(command.inv())
        out.write(header.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    val text: String get() = String(payload).trimEnd(NUL)

    companion object {
        const val HEADER_SIZE = 24
        private const val NUL = '\u0000'

        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        private const val VERSION = 0x01000000
        private const val MAX_PAYLOAD = 256 * 1024

        fun read(input: InputStream): AdbMessage {
            val data = DataInputStream(input)
            val header = ByteArray(HEADER_SIZE)
            data.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buf.int
            val arg0 = buf.int
            val arg1 = buf.int
            val length = buf.int
            buf.int                                            // checksum, not verified
            val magic = buf.int
            require(magic == command.inv()) { "bad ADB header magic" }
            require(length in 0..MAX_PAYLOAD) { "implausible ADB payload length $length" }
            val payload = ByteArray(length)
            if (length > 0) data.readFully(payload)
            return AdbMessage(command, arg0, arg1, payload)
        }

        fun connect() = AdbMessage(A_CNXN, VERSION, MAX_PAYLOAD, ("host::hud-launcher" + NUL).toByteArray())
        fun auth(type: Int, body: ByteArray) = AdbMessage(A_AUTH, type, 0, body)
        fun open(localId: Int, destination: String) =
            AdbMessage(A_OPEN, localId, 0, (destination + NUL).toByteArray())
        fun okay(localId: Int, remoteId: Int) = AdbMessage(A_OKAY, localId, remoteId)
        fun close(localId: Int, remoteId: Int) = AdbMessage(A_CLSE, localId, remoteId)
    }
}
