package com.example.audio

import kotlin.math.min
import kotlin.math.sqrt

// Full-scale value for 16-bit signed PCM, used to normalize RMS to 0..1.
private const val PCM16_FULL_SCALE = 32768.0

/**
 * Normalized RMS amplitude (0..1) for a little-endian PCM16 buffer.
 *
 * Shared by [AudioCapture] (mic input) and [AudioStreamPlayer] (model audio output) —
 * both previously carried a byte-identical private copy of this logic.
 */
internal fun pcm16Rms(buffer: ByteArray, length: Int): Float {
    val sampleCount = length / 2
    if (sampleCount <= 0) return 0f

    var sumSquares = 0.0
    var i = 0
    while (i + 1 < length) {
        val lo = buffer[i].toInt() and 0xFF
        val hi = buffer[i + 1].toInt() // sign-extended high byte
        val sample = (hi shl 8) or lo
        val norm = sample / PCM16_FULL_SCALE
        sumSquares += norm * norm
        i += 2
    }

    val rms = sqrt(sumSquares / sampleCount)
    return min(1.0, rms).toFloat()
}
