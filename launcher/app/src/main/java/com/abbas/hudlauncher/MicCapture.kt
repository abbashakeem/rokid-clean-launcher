package com.abbas.hudlauncher

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Microphone capture on the glasses, for the assistant.
 *
 * Uses the plain Android API rather than Rokid's `CXRServiceBridge`. The bridge's
 * `openAudioRecord` is permitted for a third-party app and its codec/mode values were mapped
 * empirically (codec 1-3, mode 1-6), but it delivers exactly one 640-byte frame per open on every
 * combination and then closes; chained re-opening recovers only about 4% of the sample rate. See
 * docs/CXR_SCOPING.md. `AudioRecord` sustains ~15.9kHz indefinitely, so that is what we use.
 *
 * Source choice is measured, not assumed: MIC, VOICE_COMMUNICATION and DEFAULT all sustain, while
 * VOICE_RECOGNITION underruns and returns all zeros on this firmware.
 *
 * Needs RECORD_AUDIO:
 *   adb shell pm grant com.abbas.hudlauncher android.permission.RECORD_AUDIO
 *
 * Everything is best-effort and swallows Throwable: this runs inside the HOME app, so a microphone
 * failure must degrade to "no audio", never to a crash.
 */
object MicCapture {
    private const val TAG = "MicCapture"

    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS = 16

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false

    val isRecording: Boolean get() = running

    /** True when the permission is held; capture will fail without it. */
    fun hasPermission(ctx: android.content.Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Start capturing. [onPcm] is called on the capture thread with 16-bit little-endian mono PCM;
     * the array is reused between calls, so copy anything you keep. [onStop] reports why it ended.
     */
    fun start(
        ctx: android.content.Context,
        onPcm: (ByteArray, Int) -> Unit,
        onStop: (String) -> Unit = {},
    ): Boolean {
        if (running) { Log.d(TAG, "already recording"); return true }
        if (!hasPermission(ctx)) { Log.w(TAG, "RECORD_AUDIO not granted"); onStop("no permission"); return false }

        val minBuf = try {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        } catch (t: Throwable) { -1 }
        if (minBuf <= 0) { Log.w(TAG, "unsupported capture config"); onStop("unsupported"); return false }

        running = true
        thread = Thread({ loop(minBuf, onPcm, onStop) }, "hud-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(500) } }
        thread = null
    }

    private fun loop(minBuf: Int, onPcm: (ByteArray, Int) -> Unit, onStop: (String) -> Unit) {
        var rec: AudioRecord? = null
        var reason = "stopped"
        try {
            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                reason = "init failed (state=${rec.state})"; Log.w(TAG, reason); return
            }
            rec.startRecording()
            Log.d(TAG, "capturing ${SAMPLE_RATE}Hz mono, buffer=${minBuf * 4}")

            val buf = ByteArray(minBuf)
            var total = 0L
            val t0 = System.currentTimeMillis()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { reason = "read returned $n"; Log.w(TAG, reason); break }
                total += n
                try { onPcm(buf, n) } catch (t: Throwable) { Log.w(TAG, "consumer threw: ${t.message}") }
            }
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            if (secs > 0) Log.d(TAG, "captured ${total}B in ${"%.1f".format(secs)}s (${(total / 2 / secs).toInt()} Hz)")
        } catch (t: Throwable) {
            reason = "${t::class.java.simpleName}: ${t.message}"
            Log.w(TAG, "capture failed: $reason")
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            running = false
            runCatching { onStop(reason) }
        }
    }
}
