package com.example.vision

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.Executors

/**
 * Streams the device camera to Gemini Live as throttled JPEG frames, so Nazim can SEE the
 * world in front of the phone.
 *
 * CameraX needs a [LifecycleOwner]; rather than depend on an Activity (the ViewModel has no
 * lifecycle), this source OWNS a tiny [LifecycleRegistry] and drives it RESUMED while active /
 * CREATED when stopped — so the camera binds without leaking any UI. Frames arrive on a
 * background analysis thread, are throttled to [minFrameIntervalMs], down-scaled + JPEG-encoded,
 * and handed to the sink (which forwards them to the live client).
 */
class CameraVisionSource(
    private val minFrameIntervalMs: Long = 1000L,
    private val jpegQuality: Int = 55,
    private val maxDimension: Int = 1024
) : LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var provider: ProcessCameraProvider? = null
    @Volatile private var lastSentMs = 0L
    @Volatile private var sink: ((ByteArray) -> Unit)? = null

    /** Start streaming. [onFrame] is invoked on a background thread with a JPEG frame. */
    fun start(context: Context, lensFront: Boolean, onFrame: (ByteArray) -> Unit) {
        sink = onFrame
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                registry.currentState = Lifecycle.State.RESUMED
                val cp = future.get()
                provider = cp
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::onAnalyze)
                val selector = if (lensFront) CameraSelector.DEFAULT_FRONT_CAMERA
                               else CameraSelector.DEFAULT_BACK_CAMERA
                cp.unbindAll()
                cp.bindToLifecycle(this, selector, analysis)
            } catch (t: Throwable) {
                Log.w(TAG, "camera bind failed: ${t.message}")
            }
        }, main)
    }

    private fun onAnalyze(proxy: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastSentMs < minFrameIntervalMs) return
            lastSentMs = now
            val bmp = proxy.toBitmap()
            val jpeg = JpegEncoder.encode(bmp, maxDimension, jpegQuality)
            sink?.invoke(jpeg)
        } catch (t: Throwable) {
            Log.w(TAG, "camera frame failed: ${t.message}")
        } finally {
            proxy.close()
        }
    }

    fun stop() {
        sink = null
        try { provider?.unbindAll() } catch (_: Throwable) {}
        provider = null
        try { registry.currentState = Lifecycle.State.CREATED } catch (_: Throwable) {}
    }

    private companion object { const val TAG = "CameraVisionSource" }
}
