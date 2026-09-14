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

    /**
     * Compare every plausible audio source and input device, because plain AudioRecord on MIC
     * captured room noise but not the wearer's voice.
     *
     * These glasses have four microphones and Rokid's own capture path applies beamforming toward
     * the wearer (their bridge exposes a rokidBF flag for exactly this). AudioRecord may be handing
     * us a raw, un-beamformed mic instead. Each source is recorded to its own file so the audio can
     * be listened to, not just measured - measuring is what misled me into calling noise "speech".
     */
    fun run(context: android.content.Context? = null) {
        val ctx = context ?: return
        Thread { sweep(ctx) }.start()
    }

    private fun sweep(ctx: android.content.Context) {
        val rate = 16000
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)

        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val inputs = am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
        Log.d(TAG, "input devices: " + inputs.joinToString { "${it.type}/${it.productName}" })

        val sources = listOf(
            android.media.MediaRecorder.AudioSource.MIC to "MIC",
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMM",
            android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOG",
            android.media.MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
            android.media.MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
        )
        for ((src, name) in sources) {
            var rec: android.media.AudioRecord? = null
            try {
                rec = android.media.AudioRecord(src, rate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
                if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "$name: unavailable"); rec.release(); continue
                }
                val f = java.io.File(ctx.filesDir, "src_$name.pcm")
                val os = java.io.BufferedOutputStream(java.io.FileOutputStream(f))
                rec.startRecording()
                Log.d(TAG, ">>> $name : KEEP TALKING (5s)")
                val buf = ByteArray(minBuf)
                var peak = 0; var total = 0L; var sumSq = 0.0
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 5_000) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    os.write(buf, 0, n); total += n
                    var k = 0
                    while (k + 1 < n) {
                        val v = ((buf[k + 1].toInt() shl 8) or (buf[k].toInt() and 0xFF)).toShort().toInt()
                        val a = kotlin.math.abs(v); if (a > peak) peak = a
                        sumSq += v.toDouble() * v; k += 2
                    }
                }
                rec.stop(); os.flush(); os.close()
                val samples = total / 2
                val rms = if (samples > 0) kotlin.math.sqrt(sumSq / samples) else 0.0
                Log.d(TAG, "$name RESULT peak=$peak rms=${"%.0f".format(rms)} bytes=$total")
            } catch (t: Throwable) {
                Log.w(TAG, "$name threw: ${t::class.java.simpleName}: ${t.message}")
            } finally { try { rec?.release() } catch (_: Throwable) {} }
        }
        Log.d(TAG, "SWEEP COMPLETE")
    }
}
