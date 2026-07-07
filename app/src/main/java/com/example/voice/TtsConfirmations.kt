package com.example.voice

import android.content.Context
import com.example.utils.TextToSpeechHelper
import kotlinx.coroutines.flow.StateFlow

/**
 * Speaks short confirmations and results in **command mode** using the on-device
 * [TextToSpeechHelper].
 *
 * In [VoiceMode.CONVERSATION] the companion talks through Gemini Live's native audio; but
 * while a hands-free task runs ([VoiceMode.COMMAND]) the Live socket may be closed or busy,
 * so spoken feedback ("Done.", "I can't do that on a banking screen", "Setting a 10 minute
 * timer") comes from Android TTS instead (ARCHITECTURE.md §3.5: *"in agent/command mode reuse
 * Android TextToSpeechHelper"*).
 *
 * This is a thin, intent-named wrapper: it owns a [TextToSpeechHelper] (or borrows one passed
 * in), exposes its readiness/speaking flows, and provides verbs for the common confirmation
 * cases so call sites read declaratively. Nazim's narration tone (a slightly slower, warmer
 * cadence) is applied by default.
 *
 * ### Lifecycle
 * If constructed from a [Context] it creates and **owns** the underlying helper, and
 * [shutdown] must be called when the owning [VoiceService] is destroyed. If handed an existing
 * helper, ownership stays with the caller and [shutdown] is a no-op (so a single TTS engine
 * can be shared app-wide without being torn down here).
 */
class TtsConfirmations private constructor(
    private val tts: TextToSpeechHelper,
    private val ownsHelper: Boolean
) {

    /** Creates and owns a fresh [TextToSpeechHelper] bound to [context]'s application context. */
    constructor(context: Context) : this(
        TextToSpeechHelper(context.applicationContext),
        ownsHelper = true
    )

    /** Wraps an existing [TextToSpeechHelper] without taking ownership of its lifecycle. */
    constructor(helper: TextToSpeechHelper) : this(helper, ownsHelper = false)

    /** True once the TTS engine has finished initializing and can speak. */
    val isInitialized: StateFlow<Boolean> get() = tts.isInitialized

    /** True while an utterance is actively being spoken. */
    val isSpeaking: StateFlow<Boolean> get() = tts.isSpeaking

    /**
     * Speak [text] in Nazim's narration voice (a touch slower and lower for a calm,
     * assistant-like cadence). Interrupts any current utterance ([TextToSpeechHelper] uses
     * `QUEUE_FLUSH`), which is what we want for terse status updates. No-op until the engine
     * is initialized.
     */
    fun say(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        tts.speak(trimmed, rate = NARRATION_RATE, pitch = NARRATION_PITCH)
    }

    /** "Confirm" an action verbally before/while running it, e.g. say("Setting a timer"). */
    fun confirmAction(description: String) = say(description)

    /** Speak a successful result/summary at the end of a task. */
    fun reportSuccess(summary: String) {
        say(if (summary.isBlank()) DEFAULT_DONE else summary)
    }

    /**
     * Speak a failure/blocked outcome. Callers must pass only **non-sensitive** copy here
     * (e.g. "I can't do that on a secure screen") — never raw screen text, typed input, OTPs,
     * or amounts, since this plays out loud on the device speaker.
     */
    fun reportFailure(reason: String) {
        say(if (reason.isBlank()) DEFAULT_FAILURE else reason)
    }

    /** Standard line spoken when the kill switch / STOP halts a running task. */
    fun reportStopped() = say(STOPPED)

    /** Halt any in-progress speech immediately (e.g. on barge-in or kill switch). */
    fun stop() = tts.stop()

    /**
     * Release the TTS engine **iff** this wrapper created it. If a shared helper was injected,
     * this is a no-op and the owner remains responsible for shutdown. Call from
     * `VoiceService.onDestroy()`.
     */
    fun shutdown() {
        if (ownsHelper) tts.shutdown()
    }

    private companion object {
        // Slightly slower & lower than default for a calm, narration-style delivery.
        const val NARRATION_RATE = 0.95f
        const val NARRATION_PITCH = 0.95f

        const val DEFAULT_DONE = "Done."
        const val DEFAULT_FAILURE = "Sorry, I couldn't do that."
        const val STOPPED = "Stopped."
    }
}
