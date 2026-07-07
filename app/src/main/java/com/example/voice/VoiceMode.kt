package com.example.voice

/**
 * Which voice loop is currently active. The mic is owned by a single foreground
 * service; only one of these is live at a time (ARCHITECTURE.md §3.5).
 */
enum class VoiceMode {
    /** Mic off, optionally waiting on an on-device wake word. */
    IDLE,

    /** Duplex Gemini Live conversation (the existing companion experience). */
    CONVERSATION,

    /** A hands-free task is being planned and executed via the agent loop. */
    COMMAND
}
