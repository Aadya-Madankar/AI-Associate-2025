package com.example.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for which voice loop is active ([VoiceMode]).
 *
 * The mic is owned by exactly one [VoiceService]; only one mode can be live at a time
 * (ARCHITECTURE.md §3.5). This controller holds that mode as an observable [StateFlow] and
 * exposes intent-named transitions so callers never poke the flow directly. The
 * [VoiceService] owns an instance and exposes its [mode] flow; the ViewModel/UI collect it to
 * drive Nazim's expression and the mode pill.
 *
 * The legal transitions are deliberately tiny:
 *
 * ```
 *            startConversation()              startCommand()
 *   IDLE ───────────────────────► CONVERSATION ──┐
 *    ▲  ◄─────────────────────────              │ startCommand()
 *    │         goIdle()                          ▼
 *    └──────────────── goIdle() ──────────── COMMAND
 *                                              ▲ │
 *                                 startCommand()│ │ endCommand()
 *                                              └─┘ (back to the mode we came from)
 * ```
 *
 * A hands-free **command** can be launched from either IDLE (wake word → task) or from an
 * ongoing **conversation** (the user speaks a task mid-chat). [endCommand] therefore returns
 * to whichever mode preceded the command, so finishing a task inside a conversation resumes
 * the conversation rather than dropping the user to IDLE.
 *
 * ### Threading
 * Backed by a [MutableStateFlow], so reads/writes are thread-safe and late collectors see the
 * current value immediately. Transitions may be called from the main thread (UI), the agent
 * coroutine, or the kill switch. They are cheap and non-suspending.
 */
class VoiceModeController(initial: VoiceMode = VoiceMode.IDLE) {

    private val _mode = MutableStateFlow(initial)

    /** The active voice mode. Collect to drive UI / Nazim state. */
    val mode: StateFlow<VoiceMode> = _mode.asStateFlow()

    /** Synchronous, allocation-free read of the current mode. */
    val current: VoiceMode get() = _mode.value

    /**
     * The mode a [VoiceMode.COMMAND] was entered from, so [endCommand] can restore it. Only
     * meaningful while [current] is COMMAND.
     */
    @Volatile
    private var commandReturnMode: VoiceMode = VoiceMode.IDLE

    /**
     * Enter [VoiceMode.CONVERSATION] (duplex Gemini Live chat). Idempotent if already in
     * conversation. If a command is running it ends first, returning to conversation.
     *
     * @return `true` if the mode changed.
     */
    fun startConversation(): Boolean = setMode(VoiceMode.CONVERSATION)

    /**
     * Enter [VoiceMode.COMMAND] (a hands-free task). Remembers the mode we came from so
     * [endCommand] can resume it. Idempotent if already in command mode.
     *
     * @return `true` if the mode changed.
     */
    fun startCommand(): Boolean {
        if (_mode.value == VoiceMode.COMMAND) return false
        commandReturnMode = _mode.value
        _mode.value = VoiceMode.COMMAND
        return true
    }

    /**
     * Finish the current command and return to whatever mode preceded it (CONVERSATION if the
     * task was launched mid-chat, otherwise IDLE). A no-op if not currently in command mode.
     *
     * @return `true` if the mode changed.
     */
    fun endCommand(): Boolean {
        if (_mode.value != VoiceMode.COMMAND) return false
        _mode.value = commandReturnMode
        return true
    }

    /**
     * Drop to [VoiceMode.IDLE] (mic off / waiting on the wake word). The terminal target of
     * the kill switch and of session teardown. Idempotent.
     *
     * @return `true` if the mode changed.
     */
    fun goIdle(): Boolean {
        commandReturnMode = VoiceMode.IDLE
        return setMode(VoiceMode.IDLE)
    }

    private fun setMode(target: VoiceMode): Boolean {
        if (_mode.value == target) return false
        _mode.value = target
        return true
    }
}
