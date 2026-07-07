package com.example.core

/**
 * Lifecycle state of the voice-first AI companion.
 *
 * IDLE        - no active session; awaiting user to go live.
 * CONNECTING  - establishing the realtime (WebSocket) session / setup handshake.
 * LISTENING   - session is live and capturing the user's microphone audio.
 * THINKING    - user turn complete; model is generating a response.
 * SPEAKING    - model audio is being played back to the user.
 * ERROR       - an unrecoverable error occurred in the current session.
 */
enum class CompanionState {
    IDLE,
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

/**
 * Originator of a [ChatMessage] in the transcript.
 *
 * USER - the human speaking/typing.
 * XENO - the AI companion (active persona).
 */
enum class Sender {
    USER,
    XENO
}

/**
 * A single line in the conversation transcript.
 *
 * @param id        stable unique identifier (defaults to a random UUID).
 * @param sender    who produced this message.
 * @param text      the message content.
 * @param timestamp epoch millis when the message was created.
 */
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)
