package com.abbas.hudlauncher

import android.util.Log

/**
 * Debug trigger for [MicCapture]: records for 5s and reports throughput and peak level.
 *
 * Earlier versions of this drove Rokid's `CXRServiceBridge` directly, mapping its codec and mode
 * values against the service's own error logs. That path is a dead end (one 640-byte frame per
 * open, regardless of parameters); the findings are written up in docs/CXR_SCOPING.md. Capture now
 * goes through the plain Android API in [MicCapture].
 *
 * Trigger:  adb shell am broadcast -a com.abbas.hudlauncher.MIC_TEST
 * Fires again while recording to stop early.
 */
object MicProbe {
    private const val TAG = "MicProbe"

    fun run(context: android.content.Context? = null) {
        val ctx = context ?: return
        if (MicCapture.isRecording) { MicCapture.stop(); Log.d(TAG, "stopped by second trigger"); return }

        // End to end: microphone -> Opus. Measures whether the encoder actually runs on this
        // firmware and what bitrate it really produces, since compression is what makes the BLE
        // link viable at all.
        var pcmBytes = 0L
        var opusBytes = 0L
        var frames = 0L
        var peak = 0
        val enc = OpusEncoder()
        val t0 = System.currentTimeMillis()

        val encOk = enc.start { _, n -> opusBytes += n; frames++ }
        if (!encOk) Log.w(TAG, "encoder unavailable - measuring raw capture only")

        val started = MicCapture.start(
            ctx,
            onPcm = { buf, n ->
                pcmBytes += n
                if (encOk) enc.feed(buf, n)
                var k = 0
                while (k + 1 < n) {
                    val v = kotlin.math.abs(
                        ((buf[k + 1].toInt() shl 8) or (buf[k].toInt() and 0xFF)).toShort().toInt())
                    if (v > peak) peak = v
                    k += 2
                }
            },
            onStop = { why ->
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                enc.stop()
                val pcmRate = if (secs > 0) (pcmBytes / secs).toInt() else 0
                val opusRate = if (secs > 0) (opusBytes / secs).toInt() else 0
                Log.d(TAG, "RESULT ($why) over ${"%.2f".format(secs)}s  peak=$peak")
                Log.d(TAG, "  PCM  ${pcmBytes}B = $pcmRate B/s")
                Log.d(TAG, "  OPUS ${opusBytes}B = $opusRate B/s in $frames frames" +
                           (if (opusBytes > 0) "  ratio=${"%.1f".format(pcmBytes.toDouble() / opusBytes)}x" else ""))
                Log.d(TAG, "  BLE budget ~24000 B/s -> " +
                           (if (opusRate in 1 until 24000) "FITS" else if (opusRate == 0) "no encoded output" else "TOO BIG"))
            },
        )
        if (!started) { enc.stop(); Log.w(TAG, "capture did not start"); return }
        Log.d(TAG, "TALK NOW - capturing 10s and encoding")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ MicCapture.stop() }, 10_000)
    }
}
