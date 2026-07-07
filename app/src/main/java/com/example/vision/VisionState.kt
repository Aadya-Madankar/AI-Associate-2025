package com.example.vision

/**
 * What Nazim is currently SEEING, beyond the structured accessibility tree.
 * Only one live video source streams at a time (camera OR screen), to keep bandwidth
 * and battery honest (frames are throttled to ~1 fps either way).
 */
enum class VisionState {
    /** No video stream — Nazim hears you and reads the accessibility tree only. */
    OFF,

    /** The device camera (the world outside the phone) is streaming to Gemini. */
    CAMERA,

    /** The screen (MediaProjection "screen share") is streaming to Gemini. */
    SCREEN
}
