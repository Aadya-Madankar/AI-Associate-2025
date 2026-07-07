package com.example.vision

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Downscales + JPEG-encodes a frame before it is sent to Gemini. Vision frames don't need
 * to be high-res — capping the long edge keeps each frame small (faster, cheaper, lower
 * latency) while staying legible enough for the model to read the scene/screen.
 */
object JpegEncoder {

    fun encode(bitmap: Bitmap, maxDimension: Int, quality: Int): ByteArray {
        val scaled = downscale(bitmap, maxDimension)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        if (scaled !== bitmap) scaled.recycle()
        return out.toByteArray()
    }

    private fun downscale(bmp: Bitmap, maxDim: Int): Bitmap {
        val largest = maxOf(bmp.width, bmp.height)
        if (largest <= maxDim || largest == 0) return bmp
        val scale = maxDim.toFloat() / largest
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }
}
