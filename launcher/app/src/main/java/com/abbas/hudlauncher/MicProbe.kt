package com.abbas.hudlauncher

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SparseArray
import com.rokid.cxr.CXRServiceBridge

/**
 * One-shot experiment: can a third-party app on these glasses hold the microphone open?
 *
 * Rokid exposes capture through [CXRServiceBridge], which reaches the system CXRService over a
 * native flora socket. Findings so far: the socket accepts us with no SELinux denial, codecs 1-3
 * and modes 1-6 are valid, and the mic opens - but a close request leaves our process ~4ms later
 * and the service tears the record down after one buffer. This build uses the real types (no
 * reflection Proxy) and inspects the bridge's two callback maps to find who is closing it.
 *
 * Trigger:  adb shell am broadcast -a com.abbas.hudlauncher.MIC_TEST
 */
object MicProbe {
    @Volatile var ctx: android.content.Context? = null
    private const val TAG = "MicProbe"
    private var bridge: CXRServiceBridge? = null

    private class Cb(val label: String) : CXRServiceBridge.AudioRecordCallback {
        val bytes = java.util.concurrent.atomic.AtomicLong(0)
        val buffers = java.util.concurrent.atomic.AtomicLong(0)
        var peak = 0
        override fun onStart(a: Int, b: Int) { Log.d(TAG, "$label onStart($a, $b) thread=${Thread.currentThread().name}") }
        override fun onData(buf: ByteArray, n: Int) {
            val c = buffers.incrementAndGet(); bytes.addAndGet(n.toLong())
            var k = 0
            while (k + 1 < minOf(n, buf.size)) {
                val v = kotlin.math.abs(((buf[k + 1].toInt() shl 8) or (buf[k].toInt() and 0xFF)).toShort().toInt())
                if (v > peak) peak = v; k += 2
            }
            if (c <= 3L || c % 50L == 0L) Log.d(TAG, "$label onData #$c len=$n total=${bytes.get()} peak=$peak")
        }
        override fun onStop() { Log.d(TAG, "$label onStop after ${buffers.get()} buffers ${bytes.get()} bytes") }
    }

    fun run(context: android.content.Context? = null) {
        if (context != null) ctx = context
        try { runInternal() } catch (t: Throwable) { Log.w(TAG, "probe failed: ${t::class.java.simpleName}: ${t.message}") }
    }

    private fun maps(b: CXRServiceBridge): String = try {
        val f1 = CXRServiceBridge::class.java.getDeclaredField("pendingAudioRecordCallbacks").apply { isAccessible = true }
        val f2 = CXRServiceBridge::class.java.getDeclaredField("audioRecordCallbacks").apply { isAccessible = true }
        val p = f1.get(b) as SparseArray<*>; val a = f2.get(b) as SparseArray<*>
        fun keys(s: SparseArray<*>) = (0 until s.size()).map { s.keyAt(it) }
        "pending=${keys(p)} active=${keys(a)}"
    } catch (t: Throwable) { "maps unreadable: ${t.message}" }

    /** Put the callback back in the bridge's pending map so a repeated id callback is a no-op. */
    private fun reseed(b: CXRServiceBridge, cb: CXRServiceBridge.AudioRecordCallback) {
        try {
            val f = CXRServiceBridge::class.java.getDeclaredField("pendingAudioRecordCallbacks").apply { isAccessible = true }
            val idF = CXRServiceBridge::class.java.getDeclaredField("audioRecordId").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val pending = f.get(b) as SparseArray<Any>
            val id = idF.getInt(b)
            synchronized(CXRServiceBridge::class.java.getDeclaredField("audioRecordCallbacks").apply { isAccessible = true }.get(b)!!) {
                if (pending.get(id) == null) pending.put(id, cb)
            }
        } catch (t: Throwable) { Log.w(TAG, "reseed failed: ${t.message}") }
    }

    private fun pendingMap(b: CXRServiceBridge): SparseArray<Any>? = try {
        @Suppress("UNCHECKED_CAST")
        (CXRServiceBridge::class.java.getDeclaredField("pendingAudioRecordCallbacks")
            .apply { isAccessible = true }.get(b) as SparseArray<Any>)
    } catch (t: Throwable) { Log.w(TAG, "pendingMap: ${t.message}"); null }

    /**
     * Keep every plausible client id populated in the bridge's pending map.
     *
     * onAudioRecordId(clientId, serviceId) closes the record whenever the pending map has no entry
     * for clientId. The native layer delivers that callback more than once, roughly 3ms apart: the
     * first moves the callback into the active map and REMOVES the pending entry, so the second
     * finds nothing and tears the capture down after a single 640-byte frame. A main-thread timer
     * cannot reliably fill a 3ms window, so pre-fill a range of ids and top it back up from inside
     * the callbacks themselves, which run on the bridge's own thread at exactly the right moment.
     */
    private fun fill(b: CXRServiceBridge, cb: CXRServiceBridge.AudioRecordCallback) {
        val p = pendingMap(b) ?: return
        synchronized(p) { for (id in 0..1024) if (p.get(id) == null) p.put(id, cb) }
    }

    private fun runInternal() {
        // WORKAROUND PATH: the Rokid bridge reliably yields exactly one 20ms frame per open, so
        // try the plain Android capture API instead. These are Android glasses and AudioFlinger is
        // running; the earlier tinycap failure only ruled out raw ALSA (/dev/snd), not AudioRecord.
        Thread {
            try { plainAudioRecord() } catch (t: Throwable) { Log.w(TAG, "AudioRecord failed: ${t::class.java.simpleName}: ${t.message}") }
        }.start()
    }

    /**
     * Records to /sdcard/hud_mic.pcm while playing a 1kHz tone through the glasses' own speaker.
     *
     * The sustained-capture test proved the stream runs at ~15.9kHz, but peak amplitude was 4-8 of
     * 32767, which is indistinguishable from a muted input. Playing a known tone into the room and
     * checking whether it appears in the recording separates "the pipe is open" from "the mic
     * hears anything".
     */
    private fun plainAudioRecord() {
        val rate = 16000
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        Log.d(TAG, "minBufferSize=$minBuf")

        // 1kHz sine, looped, at a high-but-not-clipping level
        val toneLen = rate
        val tone = ByteArray(toneLen * 2)
        for (n in 0 until toneLen) {
            val v = (kotlin.math.sin(2.0 * Math.PI * 1000.0 * n / rate) * 12000).toInt().toShort()
            tone[n * 2] = (v.toInt() and 0xFF).toByte()
            tone[n * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        val track = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(tone.size)
            .setTransferMode(android.media.AudioTrack.MODE_STATIC).build()
        track.write(tone, 0, tone.size)
        track.setLoopPoints(0, toneLen, -1)
        track.play()
        Log.d(TAG, "tone playing (1kHz)")

        val rec = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, rate,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) { Log.w(TAG, "not initialised"); return }
        // App-private storage: /sdcard is EPERM under scoped storage and needs no permission here.
        val out = java.io.File(ctx?.filesDir ?: java.io.File("/data/local/tmp"), "hud_mic.pcm")
        rec.startRecording()
        val buf = ByteArray(minBuf)
        var total = 0L; var peak = 0
        val t0 = System.currentTimeMillis()
        java.io.FileOutputStream(out).use { fos ->
            while (System.currentTimeMillis() - t0 < 5_000) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                fos.write(buf, 0, n); total += n
                var k = 0
                while (k + 1 < n) {
                    val v = kotlin.math.abs(((buf[k + 1].toInt() shl 8) or (buf[k].toInt() and 0xFF)).toShort().toInt())
                    if (v > peak) peak = v; k += 2
                }
            }
        }
        rec.stop(); rec.release()
        try { track.stop(); track.release() } catch (_: Throwable) {}
        Log.d(TAG, "WROTE ${out.length()} bytes, peak=$peak -> ${out.absolutePath}")
    }
}
