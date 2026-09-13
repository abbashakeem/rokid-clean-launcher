package com.abbas.hudlauncher

import android.util.Log

/**
 * One-shot experiment: can a third-party app on these glasses open the microphone?
 *
 * Rokid exposes capture through `CXRServiceBridge`, which reaches the system CXRService over a
 * native flora socket rather than a normal AIDL bind, so whether we are allowed to use it cannot be
 * settled by reading the SDK - only by asking the device. Everything here is best-effort and
 * swallows Throwable: a missing .so, a refused socket or an SELinux denial must never take down the
 * launcher, which is the HOME app.
 *
 * Trigger:  adb shell am broadcast -a com.abbas.hudlauncher.MIC_TEST
 */
object MicProbe {
    private const val TAG = "MicProbe"
    private var bridge: Any? = null

    fun run() {
        try { runInternal() } catch (t: Throwable) { Log.w(TAG, "probe failed: ${t::class.java.simpleName}: ${t.message}") }
    }

    private fun runInternal() {
        val bridgeCls = Class.forName("com.rokid.cxr.CXRServiceBridge")
        val paramCls = Class.forName("com.rokid.cxr.CXRServiceBridge\$AudioRecordParam")
        val cbCls = Class.forName("com.rokid.cxr.CXRServiceBridge\$AudioRecordCallback")

        val b = bridge ?: bridgeCls.getDeclaredConstructor().newInstance().also { bridge = it }

        // The bridge expects the app to announce itself before it will service requests.
        try { bridgeCls.getMethod("appLaunch").invoke(b); Log.d(TAG, "appLaunch() ok") }
        catch (t: Throwable) { Log.w(TAG, "appLaunch failed: ${t.message}") }

        val param = paramCls.getDeclaredConstructor(Int::class.java, Boolean::class.java, Boolean::class.java)
            .newInstance(0, true, true)
        val open = bridgeCls.getMethod("openAudioRecord", Int::class.java, Int::class.java, paramCls, cbCls)
        val close = bridgeCls.getMethod("closeAudioRecord", cbCls)
        val h = android.os.Handler(android.os.Looper.getMainLooper())

        // Codec 1 with any mode 1-6 opens the mic (the service builds a chain ending at
        // AudioRecord8.pcm). The sweep above tore each capture down within ~50ms, so hold ONE
        // capture open and measure whether audio actually sustains: count buffers, total bytes,
        // and peak amplitude (the mic DSP gates when the glasses are not worn, which would show
        // as near-zero amplitude rather than as missing data).
        val bytes = java.util.concurrent.atomic.AtomicLong(0)
        val buffers = java.util.concurrent.atomic.AtomicLong(0)
        val peak = java.util.concurrent.atomic.AtomicLong(0)
        val cb = java.lang.reflect.Proxy.newProxyInstance(
            cbCls.classLoader, arrayOf(cbCls)
        ) { _, method, args ->
            when (method.name) {
                "onStart" -> Log.d(TAG, "onStart(${args?.get(0)}, ${args?.get(1)})")
                "onData" -> {
                    val buf = args?.get(0) as? ByteArray
                    val n = (args?.get(1) as? Int) ?: 0
                    val c = buffers.incrementAndGet()
                    bytes.addAndGet(n.toLong())
                    if (buf != null) {
                        var mx = 0
                        var k = 0
                        while (k + 1 < minOf(n, buf.size)) {
                            val v = kotlin.math.abs(((buf[k + 1].toInt() shl 8) or (buf[k].toInt() and 0xFF)).toShort().toInt())
                            if (v > mx) mx = v
                            k += 2
                        }
                        if (mx > peak.get()) peak.set(mx.toLong())
                    }
                    if (c <= 3L || c % 50L == 0L) Log.d(TAG, "onData #$c len=$n total=${bytes.get()} peak=${peak.get()}")
                }
                "onStop" -> Log.d(TAG, "onStop after ${buffers.get()} buffers, ${bytes.get()} bytes")
            }
            null
        }
        Log.d(TAG, "SUSTAINED capture: codec=1 mode=1, holding 8s")
        val t0 = System.currentTimeMillis()
        try { open.invoke(b, 1, 1, param, cb) }
        catch (t: Throwable) { Log.w(TAG, "open threw: ${t.message}") }
        h.postDelayed({
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            val bps = if (secs > 0) (bytes.get() / secs).toInt() else 0
            Log.d(TAG, "FINAL buffers=${buffers.get()} bytes=${bytes.get()} over ${secs}s = $bps B/s " +
                       "(16-bit mono => ${bps / 2} Hz) peak=${peak.get()}")
            try { close.invoke(b, cb) } catch (_: Throwable) {}
        }, 8_000)
    }
}
