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

        var bytes = 0L
        var peak = 0
        val samples = java.util.concurrent.atomic.AtomicLong(0)
        val nonZero = java.util.concurrent.atomic.AtomicLong(0)
        val sumSq = java.util.concurrent.atomic.AtomicReference(0.0)
        fun addSq(v: Double) { sumSq.set(sumSq.get() + v) }
        val t0 = System.currentTimeMillis()
        val started = MicCapture.start(
            ctx,
            onPcm = { buf, n ->
                bytes += n
                var k = 0
                while (k + 1 < n) {
                    val v = kotlin.math.abs(
                        ((buf[k + 1].toInt() shl 8) or (buf[k].toInt() and 0xFF)).toShort().toInt())
                    if (v > peak) peak = v
                    samples.incrementAndGet()
                    if (v != 0) nonZero.incrementAndGet()
                    addSq(v.toDouble() * v)
                    k += 2
                }
            },
            onStop = { why ->
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                val hz = if (secs > 0) (bytes / 2 / secs).toInt() else 0
                val n = samples.get()
                val rms = if (n > 0) kotlin.math.sqrt(sumSq.get() / n) else 0.0
                val pctNz = if (n > 0) 100.0 * nonZero.get() / n else 0.0
                Log.d(TAG, "RESULT ($why): ${bytes}B in ${"%.2f".format(secs)}s = $hz Hz")
                Log.d(TAG, "LEVEL peak=$peak rms=${"%.1f".format(rms)} nonzero=${"%.1f".format(pctNz)}% " +
                           "peak_dBFS=${"%.1f".format(20 * kotlin.math.log10((peak.coerceAtLeast(1)).toDouble() / 32767.0))}")
            },
        )
        if (!started) { Log.w(TAG, "capture did not start"); return }
        Log.d(TAG, "TALK NOW - capturing 10s")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ MicCapture.stop() }, 10_000)
    }
}
