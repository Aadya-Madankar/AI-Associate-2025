package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue

/**
 * Streams PCM16 mono @ 24kHz audio out through an [AudioTrack] in MODE_STREAM.
 *
 * Incoming chunks are enqueued and drained on a single worker thread so callers
 * never block on the audio device. [clear] supports barge-in: it drops any
 * queued audio and flushes the track so the AI stops talking immediately.
 *
 * Per-chunk normalized RMS amplitude (0..1) is reported via [onAmplitude].
 */
class AudioStreamPlayer {

    companion object {
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // Sentinel chunk used to wake the worker thread for shutdown.
        private val POISON = ByteArray(0)
    }

    var onAmplitude: ((Float) -> Unit)? = null

    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return

        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING)
        val bufferSize = if (minBuffer <= 0) SAMPLE_RATE * 2 / 5 else minBuffer * 2

        val newTrack = try {
            buildTrack(bufferSize)
        } catch (t: Throwable) {
            null
        }

        if (newTrack == null || newTrack.state != AudioTrack.STATE_INITIALIZED) {
            try {
                newTrack?.release()
            } catch (_: Throwable) {
            }
            return
        }

        track = newTrack
        running = true
        queue.clear()

        try {
            newTrack.play()
        } catch (t: Throwable) {
            running = false
            track = null
            try {
                newTrack.release()
            } catch (_: Throwable) {
            }
            return
        }

        val thread = Thread({
            while (running) {
                val chunk = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    break
                }

                if (chunk === POISON || chunk.isEmpty()) continue

                val activeTrack = track ?: continue
                try {
                    var offset = 0
                    while (offset < chunk.size && running) {
                        val written = activeTrack.write(chunk, offset, chunk.size - offset)
                        if (written <= 0) break
                        offset += written
                    }
                } catch (t: Throwable) {
                    // Track may have been flushed/stopped concurrently; ignore.
                }
            }
        }, "AudioStreamPlayer")
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    fun write(pcm: ByteArray) {
        if (!running || pcm.isEmpty()) return
        // Report amplitude immediately so the avatar reacts as audio arrives.
        onAmplitude?.invoke(pcm16Rms(pcm, pcm.size))
        queue.offer(pcm)
    }

    /** Barge-in: discard queued audio and flush the device so playback stops now. */
    fun clear() {
        queue.clear()
        val activeTrack = track ?: return
        try {
            activeTrack.pause()
            activeTrack.flush()
            if (running) {
                activeTrack.play()
            }
        } catch (_: Throwable) {
        }
        onAmplitude?.invoke(0f)
    }

    fun stop() {
        if (!running && track == null) return
        running = false

        queue.clear()
        queue.offer(POISON)

        val thread = worker
        worker = null
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt()
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val activeTrack = track
        track = null
        if (activeTrack != null) {
            try {
                if (activeTrack.playState != AudioTrack.PLAYSTATE_STOPPED) {
                    activeTrack.pause()
                    activeTrack.flush()
                    activeTrack.stop()
                }
            } catch (_: Throwable) {
            }
            try {
                activeTrack.release()
            } catch (_: Throwable) {
            }
        }

        onAmplitude?.invoke(0f)
    }

    private fun buildTrack(bufferSize: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_ENCODING)
            .build()

        return AudioTrack(
            attributes,
            format,
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }
}
