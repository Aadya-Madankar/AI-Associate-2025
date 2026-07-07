package com.example.character

import com.example.core.CompanionState

/**
 * Expressive state of the **Nazim** MetaHuman, derived from the session
 * [CompanionState] plus whether a phone action is currently executing. Drives the
 * character's visemes / expression blendshapes and idle animation selection.
 *
 * Nazim is the single, named face of Xeno Live (replacing the abstract orb). See
 * SCOPE.md §2 and the `com.example.character` render pipeline.
 */
enum class NazimState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ACTING,     // a phone task is being executed
    WALKING,    // roaming overlay: strolling across the screen to its next target
    RUNNING,    // roaming overlay: dashing to a far target
    ERROR;

    companion object {
        fun from(state: CompanionState, acting: Boolean): NazimState = when {
            acting -> ACTING
            else -> when (state) {
                CompanionState.IDLE -> IDLE
                CompanionState.CONNECTING -> THINKING
                CompanionState.LISTENING -> LISTENING
                CompanionState.THINKING -> THINKING
                CompanionState.SPEAKING -> SPEAKING
                CompanionState.ERROR -> ERROR
            }
        }
    }
}
