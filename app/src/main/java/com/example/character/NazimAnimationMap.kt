package com.example.character

/**
 * Pure-data lookup tables that map Nazim's high-level state onto **candidate** glTF/GLB
 * animation-clip names.
 *
 * ### Why "candidates"?
 * The exact clip and blendshape names baked into `assets/nazim.glb` depend entirely on how the
 * MetaHuman / Mixamo / Ready-Player-Me asset was exported: one rig calls its idle `"idle"`,
 * another `"Idle"`, another `"Armature|Idle"` or `"mixamo.com"`. Rather than hard-code a single
 * guess, this object lists an **ordered** set of plausible names per state. The renderer walks the
 * list and uses the **first name that actually exists** in the loaded model (see
 * [firstPresent] / [resolveClip]). The lists are ordered most-specific → most-generic so a model
 * that ships a dedicated `"thinking"` clip uses it, while a bare-bones model still finds a generic
 * `"idle"` to fall back to.
 *
 * ### No Android / SceneView / Filament dependencies
 * This file is intentionally framework-free so it is trivially unit-testable and cannot be the
 * reason a build breaks. It performs **no** model loading and touches **no** native types — it is
 * just `String`/`List`/`Map` data plus small, allocation-light lookup helpers. `NazimRenderer`
 * owns the actual SceneView 2.3.3 calls (enumerating `modelNode.animator` clip names and calling
 * `modelNode.playAnimation(...)`); it feeds the enumerated names into the helpers here.
 *
 * @see NazimState
 */
object NazimAnimationMap {

    // ---------------------------------------------------------------------------------------------
    // Animation clips
    // ---------------------------------------------------------------------------------------------

    /**
     * Ordered candidate clip names for each [NazimState]. The renderer should try them in order and
     * play the first one present in the GLB (looping). Names cover the common casings/prefixes used
     * by MetaHuman, Mixamo, Ready-Player-Me and hand-authored Blender exports. Lookup is
     * case-insensitive (see [firstPresent]); the multiple casings are kept anyway because some
     * pipelines preserve original case and exact-match consumers may want them.
     */
    val clipCandidates: Map<NazimState, List<String>> = mapOf(
        NazimState.IDLE to listOf(
            "idle", "Idle", "idle_breathe", "breathing_idle", "BreathingIdle",
            "idle_loop", "IdleLoop", "Armature|Idle", "mixamo.com",
            "stand", "Stand", "neutral", "Neutral", "rest", "T-Pose"
        ),
        NazimState.LISTENING to listOf(
            "listening", "Listening", "listen", "Listen", "attentive", "Attentive",
            "idle_alert", "alert_idle", "AlertIdle", "nod", "Nod", "Standing",
            // graceful generic idle fallbacks
            "idle", "Idle", "breathing_idle", "Armature|Idle", "mixamo.com"
        ),
        NazimState.THINKING to listOf(
            "thinking", "Thinking", "think", "Think", "ponder", "Ponder",
            "pondering", "Pondering", "idle_thinking", "ThinkingIdle",
            "head_scratch", "HeadScratch",
            // graceful generic idle fallbacks
            "idle", "Idle", "breathing_idle", "Armature|Idle", "mixamo.com"
        ),
        NazimState.SPEAKING to listOf(
            "talking", "Talking", "talk", "Talk", "speaking", "Speaking",
            "Yes", "speak", "Speak", "gesture", "Gesture", "gesturing", "Gesturing",
            "talking_gesture", "TalkingGesture", "explain", "Explain",
            // graceful generic idle fallbacks
            "idle", "Idle", "breathing_idle", "Armature|Idle", "mixamo.com"
        ),
        NazimState.ACTING to listOf(
            // On the roaming overlay this is the "hit / press the app" beat — prefer a punch.
            "Punch", "punch", "Wave", "ThumbsUp",
            "acting", "Acting", "action", "Action", "working", "Working",
            "work", "Work", "busy", "Busy", "typing", "Typing",
            "gesture", "Gesture", "point", "Point",
            // graceful generic idle fallbacks
            "idle", "Idle", "breathing_idle", "Armature|Idle", "mixamo.com"
        ),
        NazimState.WALKING to listOf(
            "Walking", "walking", "Walk", "walk", "WalkJump", "walk_loop", "WalkLoop",
            "Armature|Walk", "mixamo.com",
            // if a model has no walk, run reads fine; else fall back to idle
            "Running", "running", "Run", "run",
            "idle", "Idle", "breathing_idle", "Armature|Idle"
        ),
        NazimState.RUNNING to listOf(
            "Running", "running", "Run", "run", "run_loop", "RunLoop", "sprint", "Sprint",
            "Armature|Run", "mixamo.com",
            // fall back to walk, then idle
            "Walking", "walking", "Walk", "walk", "WalkJump",
            "idle", "Idle", "breathing_idle", "Armature|Idle"
        ),
        NazimState.ERROR to listOf(
            "error", "Error", "confused", "Confused", "concern", "Concern",
            "concerned", "Concerned", "shrug", "Shrug", "sad", "Sad", "No",
            // graceful generic idle fallbacks
            "idle", "Idle", "breathing_idle", "Armature|Idle", "mixamo.com"
        )
    )

    /**
     * A short, ordered list of universal "any idle" candidate names. Useful as a final fallback
     * when neither a state- nor idle-specific clip resolves, e.g. to keep *some* skeletal motion
     * playing rather than freezing on the bind pose.
     */
    val genericIdleCandidates: List<String> = listOf(
        "idle", "Idle", "idle_loop", "IdleLoop", "breathing_idle", "BreathingIdle",
        "Armature|Idle", "mixamo.com", "stand", "Stand", "neutral", "Neutral"
    )

    // ---------------------------------------------------------------------------------------------
    // Lookup helpers (case-insensitive, allocation-light)
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the first name in [candidates] that exists in [available] (case-insensitive), or
     * `null` if none match. This is the core "first present in the GLB wins" primitive: pass the
     * candidate list for a state/viseme and the set of names actually enumerated from the model.
     *
     * [available] may be any iterable of names (e.g. the GLB's animation-clip names from
     * `modelNode.animator.getAnimationName(i)`, or a renderable's morph-target names from
     * `FilamentAsset.getMorphTargetNames(entity)`); a [Set] is built once for O(1) membership.
     */
    fun firstPresent(candidates: List<String>, available: Iterable<String>): String? {
        val lower = HashSet<String>()
        for (name in available) lower.add(name.lowercase())
        if (lower.isEmpty()) return null
        for (candidate in candidates) {
            if (lower.contains(candidate.lowercase())) return candidate
        }
        return null
    }

    /**
     * Resolves the best animation clip name for [state] given the [availableClips] enumerated from
     * the loaded model. Falls back to [genericIdleCandidates] if no state-specific candidate is
     * present, so a model that only ships a bare `"idle"` still animates in every state. Returns
     * `null` only if the model exposes none of the known names (caller should then rely on the
     * procedural head-sway / morph-only path).
     */
    fun resolveClip(state: NazimState, availableClips: Iterable<String>): String? {
        val candidates = clipCandidates[state] ?: genericIdleCandidates
        return firstPresent(candidates, availableClips)
            ?: firstPresent(genericIdleCandidates, availableClips)
    }
}
