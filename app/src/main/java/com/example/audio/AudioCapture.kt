package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Captures microphone audio as PCM16 mono @ 16kHz for streaming to Gemini Live.
 *
 * Reads on a dedicated background thread. For each buffer read it delivers a
 * defensive ByteArray copy via [onPcm] and a normalized RMS amplitude (0..1)
 * via [onAmplitude].
 *
 * Requires the RECORD_AUDIO permission to be granted by the UI before [start].
 */
class AudioCapture {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // Target ~100ms of audio per read (16000 samples/s * 2 bytes * 0.1s).
        private const val MIN_FRAME_BYTES = SAMPLE_RATE * 2 / 10

        // Full-scale value for 16-bit signed PCM, used to normalize RMS to 0..1.
        private const val PCM16_FULL_SCALE = 32768.0
    }

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var recordThread: Thread? = null

    @Volatile
    private var recording = false

    fun isRecording(): Boolean = recording

    @SuppressLint("MissingPermission")
    fun start(onPcm: (ByteArray) -> Unit, onAmplitude: (Float) -> Unit) {
        if (recording) return

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING)
        // getMinBufferSize can return ERROR / ERROR_BAD_VALUE; clamp to a sane floor.
        val safeMin = if (minBuffer <= 0) MIN_FRAME_BYTES else minBuffer
        val bufferSize = maxOf(safeMin, MIN_FRAME_BYTES)

        val record = try {
            buildRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, bufferSize)
                ?: buildRecorder(MediaRecorder.AudioSource.MIC, bufferSize)
        } catch (t: Throwable) {
            try {
                buildRecorder(MediaRecorder.AudioSource.MIC, bufferSize)
            } catch (t2: Throwable) {
                null
            }
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            try {
                record?.release()
            } catch (_: Throwable) {
            }
            return
        }

        recorder = record
        recording = true

        try {
            record.startRecording()
        } catch (t: Throwable) {
            recording = false
            recorder = null
            try {
                record.release()
            } catch (_: Throwable) {
            }
            return
        }

        val thread = Thread({
            val frame = ByteArray(bufferSize)
            while (recording) {
                val read = try {
                    record.read(frame, 0, frame.size)
                } catch (t: Throwable) {
                    break
                }

                if (read > 0) {
                    val chunk = frame.copyOf(read)
                    onPcm(chunk)
                    onAmplitude(computeRms(chunk, read))
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                    break
                }
            }
        }, "AudioCapture")
        thread.isDaemon = true
        recordThread = thread
        thread.start()
    }

    fun stop() {
        if (!recording && recorder == null) return
        recording = false

        val thread = recordThread
        recordThread = null
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val record = recorder
        recorder = null
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: Throwable) {
            }
            try {
                record.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun buildRecorder(source: Int, bufferSize: Int): AudioRecord? {
        return try {
            val rec = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING, bufferSize)
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                rec
            } else {
                rec.release()
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Normalized RMS amplitude (0..1) for a little-endian PCM16 buffer. */
    private fun computeRms(buffer: ByteArray, lengthBytes: Int): Float {
        val sampleCount = lengthBytes / 2
        if (sampleCount <= 0) return 0f

        var sumSquares = 0.0
        var i = 0
        while (i + 1 < lengthBytes) {
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
}
