package com.abbas.hudlauncher

import android.content.Context
import android.util.Log
import android.util.Base64
import com.abbas.hudlauncher.ble.BleScanService

/**
 * Microphone -> Opus -> phone, the capture half of the assistant.
 *
 * The glasses do capture and compression only; recognition and the model live on the phone. Raw
 * PCM is 30KB/s measured and cannot cross the BLE link, so audio is Opus-encoded first (18x
 * smaller, ~1.7KB/s on quiet input) and framed as JSON messages on the existing link.
 *
 * Frames are batched before sending: one BLE write per 20ms Opus frame would be about 50 writes a
 * second, which wastes connection events. Batching to roughly [BATCH_MS] trades a little latency
 * for far fewer round trips.
 */
object AssistantMic {
    private const val TAG = "AssistantMic"
    private const val BATCH_MS = 200
    private const val FRAMES_PER_BATCH = BATCH_MS / 20

    private val pending = ArrayList<ByteArray>(FRAMES_PER_BATCH)
    private var encoder: OpusEncoder? = null
    @Volatile private var sent = 0L
    @Volatile private var dropped = 0L

    val isActive: Boolean get() = MicCapture.isRecording

    /** Start capturing and streaming to the phone. Returns false if mic or encoder is unavailable. */
    fun start(ctx: Context): Boolean {
        if (isActive) return true
        val enc = OpusEncoder()
        sent = 0; dropped = 0
        synchronized(pending) { pending.clear() }

        if (!enc.start { frame, n -> onEncoded(frame, n) }) {
            Log.w(TAG, "no Opus encoder; not starting")
            return false
        }
        encoder = enc

        val ok = MicCapture.start(
            ctx,
            onPcm = { buf, n -> enc.feed(buf, n) },
            onStop = { why ->
                flush()
                enc.stop()
                encoder = null
                Log.d(TAG, "stopped ($why): sent=$sent batches, dropped=$dropped")
                BleScanService.sendToPhone("audio_end", org.json.JSONObject())
            },
        )
        if (!ok) { enc.stop(); encoder = null; return false }

        BleScanService.sendToPhone("audio_start", org.json.JSONObject()
            .put("codec", "opus").put("rate", MicCapture.SAMPLE_RATE).put("channels", MicCapture.CHANNELS))
        Log.d(TAG, "streaming to phone")
        return true
    }

    fun stop() = MicCapture.stop()

    /** Debug entry point: start streaming, or stop if already running. */
    fun toggle(ctx: Context) {
        if (isActive) { Log.d(TAG, "stopping"); stop(); return }
        if (!start(ctx)) { Log.w(TAG, "could not start"); return }
        Log.d(TAG, "TALK NOW - streaming 10s to the phone")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stop() }, 10_000)
    }

    private fun onEncoded(frame: ByteArray, n: Int) {
        val batch: List<ByteArray>? = synchronized(pending) {
            pending.add(frame.copyOf(n))
            if (pending.size >= FRAMES_PER_BATCH) {
                val out = ArrayList(pending); pending.clear(); out
            } else null
        }
        if (batch != null) send(batch)
    }

    private fun flush() {
        val batch: List<ByteArray>? = synchronized(pending) {
            if (pending.isEmpty()) null else ArrayList(pending).also { pending.clear() }
        }
        if (batch != null) send(batch)
    }

    /** Opus frames are binary, and the link carries JSON, so batches go as base64. */
    private fun send(batch: List<ByteArray>) {
        try {
            val arr = org.json.JSONArray()
            for (f in batch) arr.put(Base64.encodeToString(f, Base64.NO_WRAP))
            val ok = BleScanService.sendToPhone("audio", org.json.JSONObject().put("frames", arr))
            if (ok) sent++ else dropped++
            if ((sent + dropped) % 25L == 0L) Log.d(TAG, "sent=$sent dropped=$dropped")
        } catch (t: Throwable) {
            dropped++
            Log.w(TAG, "send failed: ${t.message}")
        }
    }
}
