package com.example.utils

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.File

class AudioPlayerHelper(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    interface AudioPlayListener {
        fun onStart()
        fun onComplete()
        fun onError(msg: String)
        fun onAmplitude(amplitude: Float) // real-time amplitude monitoring
    }

    private var amplitudePoller: Thread? = null
    private var isPlayingActive = false

    fun playAudio(audioBytes: ByteArray, listener: AudioPlayListener) {
        try {
            stopAudio()
            
            // Decoded wav data is playable natively by MediaPlayer
            val tempFile = File(context.cacheDir, "gemini_voice_output.wav")
            if (tempFile.exists()) {
                tempFile.delete()
            }
            tempFile.writeBytes(audioBytes)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnPreparedListener {
                    it.start()
                    startAmplitudePolling(listener)
                    listener.onStart()
                }
                setOnCompletionListener {
                    stopAmplitudePolling()
                    listener.onComplete()
                    it.release()
                    mediaPlayer = null
                }
                setOnErrorListener { _, what, extra ->
                    stopAmplitudePolling()
                    listener.onError("MediaPlayer error: what=$what extra=$extra")
                    false
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("AudioPlayerHelper", "Failed to play audio: ${e.message}", e)
            listener.onError(e.localizedMessage ?: "Playback error")
        }
    }

    private fun startAmplitudePolling(listener: AudioPlayListener) {
        isPlayingActive = true
        amplitudePoller = Thread {
            // Standard wave simulation based on current play position & frequency
            var t = 0f
            while (isPlayingActive) {
                val isPlaying = try {
                    mediaPlayer?.isPlaying == true
                } catch (e: Exception) {
                    false
                }
                if (!isPlaying) break
                
                // Simulate responsive audio energy waveforms for speaking avatar sync
                val amp = 0.3f + 0.6f * kotlin.math.abs(kotlin.math.sin(t))
                listener.onAmplitude(amp)
                t += 0.25f
                try {
                    Thread.sleep(60)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply { start() }
    }

    private fun stopAmplitudePolling() {
        isPlayingActive = false
        amplitudePoller?.interrupt()
        amplitudePoller = null
    }

    fun stopAudio() {
        stopAmplitudePolling()
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioPlayerHelper", "Failed to stop media player: ${e.message}")
        } finally {
            mediaPlayer = null
        }
    }

    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }
    }
}
