package com.example.overlay

import com.example.character.NazimState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bridge between the live session / on-screen control engine and the roaming XENO
 * overlay (the real 3D avatar that leaves the app and operates your phone).
 *
 *  - [taps]: the accessibility tap path calls [emitTap] with absolute SCREEN pixel coordinates;
 *    [XenoOverlayService] collects them and the avatar **walks / runs** to that spot and plays a
 *    "press" (Punch) animation, so you watch a little person operate your phone.
 *  - [conversationState] / [amplitude]: mirrored from the live session so the roaming avatar shows
 *    the same expression and lip-syncs to XENO's voice while it is not actively walking.
 *  - [roamActive]: true while the overlay is up, so the gesture path can briefly lead the real tap
 *    (let the avatar arrive and "hit" the target before the tap actually lands).
 */
object OverlayBus {

    data class Tap(val x: Int, val y: Int)

    private val _taps = MutableSharedFlow<Tap>(extraBufferCapacity = 32)
    val taps = _taps.asSharedFlow()

    /** Live conversational state of XENO, mirrored into the roaming avatar when it is idle. */
    private val _conversationState = MutableStateFlow(NazimState.IDLE)
    val conversationState: StateFlow<NazimState> = _conversationState.asStateFlow()

    /** Live mic/voice amplitude (0..1) for the roaming avatar's lip-sync. */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /** True while [XenoOverlayService] is running (the avatar is on screen). */
    @Volatile
    var roamActive: Boolean = false
        private set

    /** Called from the accessibility tap path with absolute screen coordinates. */
    fun emitTap(x: Int, y: Int) {
        _taps.tryEmit(Tap(x, y))
    }

    /** Mirror the live session's avatar state onto the roaming overlay. */
    fun setConversationState(state: NazimState) {
        _conversationState.value = state
    }

    /** Mirror the live session's amplitude (lip-sync) onto the roaming overlay. */
    fun setAmplitude(amp: Float) {
        _amplitude.value = amp.coerceIn(0f, 1f)
    }

    /** Set by [XenoOverlayService] on create/destroy. */
    fun setRoamActive(active: Boolean) {
        roamActive = active
    }
}
