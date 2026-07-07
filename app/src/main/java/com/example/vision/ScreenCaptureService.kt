package com.example.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

/**
 * Foreground service (type=mediaProjection) that captures the screen and streams throttled
 * JPEG frames so Nazim can SEE the actual screen pixels — games, video, WebViews, anything the
 * accessibility tree can't read.
 *
 * Android 14+ requires a mediaProjection-type FGS to be in the foreground BEFORE
 * [MediaProjectionManager.getMediaProjection] is called, so [onStartCommand] goes foreground
 * first, then starts the projection. The projection token (resultCode + Intent) comes from the
 * system grant dialog launched by MainActivity; the [frameSink] is set by the ViewModel to
 * forward frames to the live client.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    @Volatile private var lastSentMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? =
            if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (resultCode != 0 && data != null) startProjection(resultCode, data) else stopSelf()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen share", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification =
            (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
             else @Suppress("DEPRECATION") Notification.Builder(this))
                .setContentTitle("Nazim is viewing your screen")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mgr.getMediaProjection(resultCode, data)
            if (proj == null) { stopSelf(); return }
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { teardown() }
            }, null)

            val metrics = resources.displayMetrics
            val w = (metrics.widthPixels * SCALE).toInt().coerceAtLeast(1)
            val h = (metrics.heightPixels * SCALE).toInt().coerceAtLeast(1)
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ r -> onImage(r, w, h) }, null)
            virtualDisplay = proj.createVirtualDisplay(
                "xeno-screen", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        } catch (t: Throwable) {
            Log.w(TAG, "projection failed: ${t.message}")
            stopSelf()
        }
    }

    private fun onImage(reader: ImageReader, w: Int, h: Int) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastSentMs < MIN_INTERVAL_MS) return
            lastSentMs = now
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (bmp.width != w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
            val jpeg = JpegEncoder.encode(cropped, MAX_DIM, QUALITY)
            frameSink?.invoke(jpeg)
            if (cropped !== bmp) cropped.recycle()
            bmp.recycle()
        } catch (t: Throwable) {
            Log.w(TAG, "screen frame failed: ${t.message}")
        } finally {
            image.close()
        }
    }

    private fun teardown() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        virtualDisplay = null; imageReader = null; projection = null
    }

    override fun onDestroy() {
        teardown()
        frameSink = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "xeno_screen_share"
        private const val NOTIF_ID = 4711
        private const val MIN_INTERVAL_MS = 1000L
        private const val SCALE = 0.5f
        private const val MAX_DIM = 1024
        private const val QUALITY = 50
        private const val TAG = "ScreenCaptureService"

        /** Set by the ViewModel to forward screen frames to the live client. */
        @Volatile
        var frameSink: ((ByteArray) -> Unit)? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}
