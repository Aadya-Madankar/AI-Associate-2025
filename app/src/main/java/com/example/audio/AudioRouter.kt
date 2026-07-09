package com.example.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Routes XENO's voice output between the **loud** built-in speaker (speakerphone) and the **quiet**
 * built-in earpiece, so the user can toggle "big speakers" ↔ "small speaker".
 *
 * Uses the modern `AudioManager.setCommunicationDevice` API (Android 12+/API 31, which this app
 * requires). The playback [AudioStreamPlayer] already tags its track as `USAGE_VOICE_COMMUNICATION`,
 * so the communication device selected here is where the voice comes out. Every call is guarded —
 * a device that isn't present (e.g. a tablet with no earpiece) is a graceful no-op.
 */
class AudioRouter(context: Context) {

    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Route the voice to the loud speaker ([loud] = true) or the quiet earpiece ([loud] = false). */
    fun setLoud(loud: Boolean) {
        val wanted = if (loud) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val device = am.availableCommunicationDevices.firstOrNull { it.type == wanted }
            if (device != null) am.setCommunicationDevice(device) else am.clearCommunicationDevice()
        }
    }

    /** Release the forced route (call when the session ends). */
    fun reset() {
        runCatching {
            am.clearCommunicationDevice()
            am.mode = AudioManager.MODE_NORMAL
        }
    }
}
