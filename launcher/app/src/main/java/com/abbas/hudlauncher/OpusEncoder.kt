package com.abbas.hudlauncher

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Opus encoder for assistant audio, using the glasses' own `c2.android.opus.encoder`.
 *
 * Compression is not optional here. Raw 16kHz mono PCM is 32KB/s, and the BLE link to the phone
 * tops out around 24KB/s in theory and less in practice (see docs/CXR_SCOPING.md), so raw audio
 * cannot be shipped. Opus at 16-24kbps is 2-3KB/s, roughly a tenth of the available budget.
 *
 * Runs its own drain thread: MediaCodec hands encoded frames back asynchronously, and blocking the
 * capture thread on them would stall the microphone.
 *
 * Best-effort throughout. This lives in the HOME app, so a codec failure must degrade to "no
 * encoding" rather than take the launcher down.
 */
class OpusEncoder(
    private val sampleRate: Int = MicCapture.SAMPLE_RATE,
    private val channels: Int = MicCapture.CHANNELS,
    private val bitrate: Int = 24_000,
) {
    private var codec: MediaCodec? = null
    @Volatile private var running = false
    private var drain: Thread? = null

    val isRunning: Boolean get() = running

    /** [onFrame] receives each encoded Opus frame on the drain thread; the array is reused. */
    fun start(onFrame: (ByteArray, Int) -> Unit): Boolean {
        if (running) return true
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                // Opus only accepts certain frame durations; 20ms matches what the mic delivers.
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, sampleRate / 50 * 2 * channels)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            running = true
            drain = Thread({ drainLoop(c, onFrame) }, "opus-drain").apply { start() }
            Log.d(TAG, "started: ${sampleRate}Hz ch=$channels ${bitrate / 1000}kbps via ${c.name}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "start failed: ${t::class.java.simpleName}: ${t.message}")
            runCatching { codec?.release() }
            codec = null; running = false
            false
        }
    }

    /** Feed PCM from the microphone. Safe to call from the capture thread; never blocks on output. */
    fun feed(pcm: ByteArray, len: Int) {
        val c = codec ?: return
        if (!running) return
        try {
            val idx = c.dequeueInputBuffer(0)
            if (idx < 0) return                       // encoder busy; drop rather than stall capture
            val buf: ByteBuffer = c.getInputBuffer(idx) ?: return
            buf.clear()
            val n = minOf(len, buf.remaining())
            buf.put(pcm, 0, n)
            c.queueInputBuffer(idx, 0, n, System.nanoTime() / 1000, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "feed failed: ${t.message}")
        }
    }

    private fun drainLoop(c: MediaCodec, onFrame: (ByteArray, Int) -> Unit) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 20_000)
                if (idx < 0) continue
                val out = c.getOutputBuffer(idx)
                if (out != null && info.size > 0 &&
                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val bytes = ByteArray(info.size)
                    out.position(info.offset); out.get(bytes, 0, info.size)
                    try { onFrame(bytes, info.size) } catch (t: Throwable) { Log.w(TAG, "consumer: ${t.message}") }
                }
                c.releaseOutputBuffer(idx, false)
            }
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "drain failed: ${t::class.java.simpleName}: ${t.message}")
        }
    }

    fun stop() {
        running = false
        runCatching { drain?.join(500) }
        drain = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    companion object { private const val TAG = "OpusEncoder" }
}
