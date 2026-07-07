package com.example.voice

import kotlin.math.min
import kotlin.math.sqrt

/**
 * A pluggable "is the user trying to wake Nazim?" detector.
 *
 * In steady state the [VoiceService] keeps the mic open but the Gemini Live WebSocket
 * **closed** (ARCHITECTURE.md §3.5): every PCM frame from the shared [com.example.audio.AudioCapture]
 * is fed to a [WakeWordGate], and only when the gate fires do we open a session and switch
 * to [VoiceMode.CONVERSATION]/[VoiceMode.COMMAND].
 *
 * This is deliberately a tiny interface so the placeholder [EnergyWakeWordGate] can later be
 * swapped for an on-device keyword spotter (Porcupine / Vosk) **without changing any caller**.
 * A real implementation would buffer frames and run `process(short[])` on its model; the
 * energy stub below approximates "someone started talking" purely from loudness so the build
 * has no external dependency.
 *
 * ### Threading
 * [accept] is invoked from the [com.example.audio.AudioCapture] reader thread (one daemon
 * thread). Implementations must be cheap and non-blocking; they must not touch the UI. The
 * [onWake] callback supplied to [VoiceService] is responsible for hopping back to the main
 * thread. Implementations are not required to be safe against concurrent [accept] calls from
 * multiple threads (there is only ever one capture thread), but [reset] may be called from a
 * different thread and so is expected to be benign to race with [accept].
 */
interface WakeWordGate {

    /**
     * Human-readable label for the wake phrase / mechanism (e.g. "Hey Nazim", "energy").
     * Surfaced in logs and the idle-state UI so the user knows what triggers a listen.
     */
    val keyword: String

    /**
     * Offer one PCM16, mono, little-endian audio frame (the exact format emitted by
     * [com.example.audio.AudioCapture], 16 kHz) to the detector.
     *
     * @param pcm        the raw frame bytes. Treated as read-only; never retained.
     * @param amplitude  the normalized RMS (0..1) the capture layer already computed for this
     *                   frame, supplied so energy-based gates need not recompute it.
     * @return `true` exactly on the frame where the wake condition first becomes satisfied.
     *         Returns `false` on every other frame, including subsequent frames of the same
     *         utterance, until [reset] re-arms the gate.
     */
    fun accept(pcm: ByteArray, amplitude: Float): Boolean

    /**
     * Re-arm the gate so the next qualifying audio can fire [accept] again. Call after a wake
     * has been consumed (a session opened) and after that session ends, so the gate does not
     * immediately re-fire on the tail of the same speech.
     */
    fun reset()
}

/**
 * Dependency-free placeholder [WakeWordGate] that fires on a short burst of sustained speech
 * energy rather than recognizing an actual phrase.
 *
 * It is intentionally **not** a real wake word: it exists so the voice pipeline compiles and
 * runs end-to-end before a keyword-spotting model is integrated. The heuristic is a simple
 * hysteresis — it fires once at least [framesToTrigger] consecutive frames exceed
 * [energyThreshold], then disarms until a quiet stretch ([framesToReset] frames below the
 * threshold) re-arms it. This avoids machine-gun re-triggering on a single sentence while the
 * mic is hot.
 *
 * Swap this for a Porcupine/Vosk-backed gate by implementing [WakeWordGate] and changing the
 * single construction site in [VoiceService]; no other code changes.
 *
 * @param energyThreshold normalized RMS (0..1) a frame must exceed to count as "speech".
 * @param framesToTrigger consecutive loud frames required to fire (debounce against blips).
 * @param framesToReset   consecutive quiet frames required to auto re-arm after a fire.
 */
class EnergyWakeWordGate(
    private val energyThreshold: Float = DEFAULT_ENERGY_THRESHOLD,
    private val framesToTrigger: Int = DEFAULT_FRAMES_TO_TRIGGER,
    private val framesToReset: Int = DEFAULT_FRAMES_TO_RESET
) : WakeWordGate {

    init {
        require(energyThreshold in 0f..1f) { "energyThreshold must be in 0..1" }
        require(framesToTrigger >= 1) { "framesToTrigger must be >= 1" }
        require(framesToReset >= 1) { "framesToReset must be >= 1" }
    }

    override val keyword: String = "energy"

    @Volatile private var loudRun = 0
    @Volatile private var quietRun = 0

    /** True once we have fired and are waiting for silence before re-arming. */
    @Volatile private var fired = false

    override fun accept(pcm: ByteArray, amplitude: Float): Boolean {
        // Prefer the amplitude the capture layer already computed; fall back to computing it
        // from the frame if the caller passed a sentinel (negative) value.
        val energy = if (amplitude in 0f..1f) amplitude else rms(pcm)

        if (fired) {
            // Disarmed: count down the quiet tail before we allow a new wake.
            if (energy < energyThreshold) {
                if (++quietRun >= framesToReset) reset()
            } else {
                quietRun = 0
            }
            return false
        }

        return if (energy >= energyThreshold) {
            quietRun = 0
            if (++loudRun >= framesToTrigger) {
                fired = true
                loudRun = 0
                true
            } else {
                false
            }
        } else {
            loudRun = 0
            false
        }
    }

    override fun reset() {
        loudRun = 0
        quietRun = 0
        fired = false
    }

    /** Normalized RMS (0..1) for a little-endian PCM16 buffer. Mirrors AudioCapture. */
    private fun rms(buffer: ByteArray): Float {
        val sampleCount = buffer.size / 2
        if (sampleCount <= 0) return 0f
        var sumSquares = 0.0
        var i = 0
        while (i + 1 < buffer.size) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val norm = sample / PCM16_FULL_SCALE
            sumSquares += norm * norm
            i += 2
        }
        return min(1.0, sqrt(sumSquares / sampleCount)).toFloat()
    }

    private companion object {
        const val DEFAULT_ENERGY_THRESHOLD = 0.18f
        const val DEFAULT_FRAMES_TO_TRIGGER = 3
        const val DEFAULT_FRAMES_TO_RESET = 6
        const val PCM16_FULL_SCALE = 32768.0
    }
}
