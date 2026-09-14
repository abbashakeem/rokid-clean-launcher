package com.abbas.hudlauncher

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Single still capture from the glasses camera, for the assistant's "what am I looking at".
 *
 * Written as a probe first because the contention question cannot be answered by reading: the
 * camera log shows Rokid's assistserver as the only historical client, all sessions closed, and
 * there is exactly ONE camera device. Whether a third-party app may open it is something only the
 * device can say.
 *
 * Deliberately small: 640x480 JPEG is roughly 40-60KB, about two to three seconds over the BLE
 * link to the phone. The sensor is 4032x3024 and a full frame would take minutes.
 */
object CameraProbe {
    private const val TAG = "CameraProbe"
    const val WIDTH = 640
    const val HEIGHT = 480

    /** Captures one JPEG. [done] gets the bytes, or null with a reason logged. */
    fun capture(ctx: Context, done: (ByteArray?) -> Unit) {
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CAMERA not granted"); done(null); return
        }
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = try {
            mgr.cameraIdList.firstOrNull { cid ->
                mgr.getCameraCharacteristics(cid)
                    .get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT
            } ?: mgr.cameraIdList.firstOrNull()
        } catch (t: Throwable) { Log.w(TAG, "id list failed: ${t.message}"); null }
        if (id == null) { Log.w(TAG, "no camera id"); done(null); return }
        Log.d(TAG, "opening camera $id")

        val thread = HandlerThread("hud-cam").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 1)
        var finished = false
        fun finish(bytes: ByteArray?, why: String) {
            if (finished) return
            finished = true
            Log.d(TAG, "finish: $why (${bytes?.size ?: 0} bytes)")
            try { reader.close() } catch (_: Throwable) {}
            thread.quitSafely()
            done(bytes)
        }

        reader.setOnImageAvailableListener({ r ->
            try {
                r.acquireLatestImage()?.use { img ->
                    val buf = img.planes[0].buffer
                    val out = ByteArray(buf.remaining())
                    buf.get(out)
                    finish(out, "image received")
                }
            } catch (t: Throwable) { finish(null, "read failed: ${t.message}") }
        }, handler)

        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    Log.d(TAG, "camera opened")
                    try {
                        device.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                        req.addTarget(reader.surface)
                                        req.set(CaptureRequest.JPEG_QUALITY, 80.toByte())
                                        session.capture(req.build(), null, handler)
                                    } catch (t: Throwable) { finish(null, "capture failed: ${t.message}") }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    finish(null, "session configure failed")
                                }
                            }, handler)
                    } catch (t: Throwable) { finish(null, "session failed: ${t.message}") }
                }
                override fun onDisconnected(device: CameraDevice) { finish(null, "disconnected") }
                override fun onError(device: CameraDevice, error: Int) {
                    // ERROR_CAMERA_IN_USE (1) / MAX_IN_USE (2) would mean Rokid holds it.
                    finish(null, "error $error")
                }
            }, handler)
        } catch (t: Throwable) { finish(null, "openCamera threw: ${t::class.java.simpleName}: ${t.message}") }

        handler.postDelayed({ finish(null, "timeout") }, 8_000)
    }
}
