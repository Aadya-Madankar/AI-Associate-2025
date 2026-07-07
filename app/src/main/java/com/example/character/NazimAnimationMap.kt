package com.example.character

/**
 * Pure-data lookup tables that map Nazim's high-level state onto **candidate** glTF/GLB
 * animation-clip names and **candidate** mouth/jaw morph-target (blendshape) names.
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
 * owns the actual SceneView 2.3.3 calls (enumerating `modelNode.animator` clip names, calling
 * `modelNode.playAnimation(...)`, and pushing `RenderableManager.setMorphWeights(...)`); it feeds
 * the enumerated names into the helpers here.
 *
 * @see NazimState
 * @see NazimExpressionController.IdleAnimation
 * @see VisemeController
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
     * Ordered candidate clip names for each [NazimExpressionController.IdleAnimation] energy tier.
     *
     * [NazimExpressionController] already resolves a [NazimState] into a coarse idle "feel"
     * ([NazimExpressionController.IdleAnimation]); when a model ships idle variants this lets the
     * renderer pick a clip that matches that feel directly, independent of the conversational
     * state→clip map above. Each list ends with the universal generic idles so resolution always
     * succeeds on any model that has *some* idle.
     */
    val idleClipCandidates: Map<NazimExpressionController.IdleAnimation, List<String>> = mapOf(
        NazimExpressionController.IdleAnimation.BREATHE to listOf(
            "idle_breathe", "breathing_idle", "BreathingIdle", "breathe", "Breathe",
            "idle", "Idle", "Armature|Idle", "mixamo.com"
        ),
        NazimExpressionController.IdleAnimation.ALERT to listOf(
            "idle_alert", "alert_idle", "AlertIdle", "alert", "Alert", "attentive", "Attentive",
            "idle", "Idle", "Armature|Idle", "mixamo.com"
        ),
        NazimExpressionController.IdleAnimation.PONDER to listOf(
            "idle_thinking", "thinking_idle", "ponder", "Ponder", "pondering", "Pondering",
            "idle", "Idle", "Armature|Idle", "mixamo.com"
        ),
        NazimExpressionController.IdleAnimation.ENGAGED to listOf(
            "talking", "Talking", "gesture", "Gesture", "engaged", "Engaged",
            "talking_gesture", "TalkingGesture",
            "idle", "Idle", "Armature|Idle", "mixamo.com"
        ),
        NazimExpressionController.IdleAnimation.BUSY to listOf(
            "working", "Working", "busy", "Busy", "typing", "Typing", "action", "Action",
            "idle", "Idle", "Armature|Idle", "mixamo.com"
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
    // Morph targets (visemes + jaw)
    // ---------------------------------------------------------------------------------------------

    /**
     * Ordered candidate morph-target names for the **jaw open** / primary mouth-open aperture —
     * the single most important blendshape for amplitude-driven lip-sync. Covers ARKit/MetaHuman
     * (`jawOpen`), Oculus/RPM viseme rigs (`viseme_aa`), Blender shape keys (`mouthOpen`) and
     * common funnel/round variants. The renderer resolves the first present name and drives it
     * from the audio amplitude. Lookup is case-insensitive (see [firstPresent]).
     */
    val jawOpenCandidates: List<String> = listOf(
        "jawOpen", "JawOpen", "jaw_open", "mouthOpen", "MouthOpen", "mouth_open",
        "viseme_aa", "viseme_AA", "viseme_O", "viseme_o", "open", "Open",
        "mouthFunnel", "MouthFunnel", "mouth_funnel"
    )

    /**
     * Ordered candidate morph-target names for **lip rounding / funnel** (e.g. the "oo"/"ow"
     * shape). Layered on top of the jaw aperture for rounder vowels.
     */
    val mouthFunnelCandidates: List<String> = listOf(
        "mouthFunnel", "MouthFunnel", "mouth_funnel", "viseme_U", "viseme_u",
        "viseme_OW", "funnel", "Funnel", "round", "Round"
    )

    /**
     * Ordered candidate morph-target names for **lip pucker** (tight rounding, e.g. "oo"/"w").
     */
    val mouthPuckerCandidates: List<String> = listOf(
        "mouthPucker", "MouthPucker", "mouth_pucker", "viseme_U", "pucker", "Pucker",
        "mouthFunnel", "kiss", "Kiss"
    )

    /**
     * Canonical Oculus/Ready-Player-Me viseme blendshape set, in the order produced by the
     * Oculus LipSync SDK. Listed for renderers/exporters that want to enumerate or validate the
     * full `viseme_*` family on the loaded model. These map roughly:
     * `sil`=silence, `PP`/`FF`/`TH`/`DD`/`kk`/`CH`/`SS`/`nn`/`RR`=consonant groups,
     * `aa`/`E`/`I`/`O`/`U`=vowels.
     */
    val oculusVisemeNames: List<String> = listOf(
        "viseme_sil", "viseme_PP", "viseme_FF", "viseme_TH", "viseme_DD",
        "viseme_kk", "viseme_CH", "viseme_SS", "viseme_nn", "viseme_RR",
        "viseme_aa", "viseme_E", "viseme_I", "viseme_O", "viseme_U"
    )

    /**
     * Maps each [VisemeController.Viseme] category onto the morph-target name candidates that best
     * realize it, ordered most-specific → most-generic. A phoneme→viseme front-end (future work)
     * can use this to push a single coherent mouth shape regardless of the source rig's naming.
     *
     * - [VisemeController.Viseme.NEUTRAL] resolves to the silence/rest shape (or nothing).
     * - [VisemeController.Viseme.AA] → wide-open vowel.
     * - [VisemeController.Viseme.OO] → rounded vowel (funnel/pucker).
     * - [VisemeController.Viseme.EE] → spread vowel (wide, smiling).
     * - [VisemeController.Viseme.MBP] → bilabial closure (lips together).
     * - [VisemeController.Viseme.FV] → labiodental (lower lip to upper teeth).
     */
    val visemeCandidates: Map<VisemeController.Viseme, List<String>> = mapOf(
        VisemeController.Viseme.NEUTRAL to listOf(
            "viseme_sil", "viseme_silence", "sil", "neutral", "Neutral"
        ),
        VisemeController.Viseme.AA to listOf(
            "viseme_aa", "viseme_AA", "jawOpen", "mouthOpen", "aa", "AA"
        ),
        VisemeController.Viseme.OO to listOf(
            "viseme_U", "viseme_O", "mouthFunnel", "mouthPucker", "oo", "OO", "U", "O"
        ),
        VisemeController.Viseme.EE to listOf(
            "viseme_E", "viseme_I", "mouthSmileLeft", "mouthSmileRight", "ee", "EE", "E", "I"
        ),
        VisemeController.Viseme.MBP to listOf(
            "viseme_PP", "mouthClose", "MouthClose", "mouth_close", "pp", "PP", "mbp", "MBP"
        ),
        VisemeController.Viseme.FV to listOf(
            "viseme_FF", "mouthPress", "mouthFunnel", "ff", "FF", "fv", "FV"
        )
    )

    /**
     * The full union of every mouth/jaw morph-target name this map can request, de-duplicated and
     * lower-cased. Handy for a one-shot "does this model expose *any* lip-sync blendshape?" probe.
     */
    val allMouthMorphCandidates: List<String> = buildList {
        addAll(jawOpenCandidates)
        addAll(mouthFunnelCandidates)
        addAll(mouthPuckerCandidates)
        addAll(oculusVisemeNames)
        visemeCandidates.values.forEach { addAll(it) }
    }.map { it.lowercase() }.distinct()

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

    /**
     * Resolves the best idle clip name for an [NazimExpressionController.IdleAnimation] energy
     * tier given the [availableClips], falling back to [genericIdleCandidates]. See
     * [idleClipCandidates].
     */
    fun resolveIdleClip(
        idle: NazimExpressionController.IdleAnimation,
        availableClips: Iterable<String>
    ): String? {
        val candidates = idleClipCandidates[idle] ?: genericIdleCandidates
        return firstPresent(candidates, availableClips)
            ?: firstPresent(genericIdleCandidates, availableClips)
    }

    /**
     * Resolves the model's actual jaw/mouth-open morph-target name from the [availableMorphs]
     * (a renderable's `getMorphTargetNames(...)`). Returns the exact name as it appears in
     * [availableMorphs] when found (preserving the model's original casing), else `null`.
     */
    fun resolveJawMorph(availableMorphs: Iterable<String>): String? =
        resolvePreservingCase(jawOpenCandidates, availableMorphs)

    /**
     * Resolves the model's actual morph-target name for a [VisemeController.Viseme] category from
     * the [availableMorphs], preserving the model's original casing. Returns `null` if the model
     * exposes none of the candidates for that viseme.
     */
    fun resolveVisemeMorph(
        viseme: VisemeController.Viseme,
        availableMorphs: Iterable<String>
    ): String? {
        val candidates = visemeCandidates[viseme] ?: return null
        return resolvePreservingCase(candidates, availableMorphs)
    }

    /**
     * Builds a `candidateKey → actualModelName` map for every [VisemeController.Viseme] category
     * that the model can satisfy. Categories the model cannot express are simply omitted. Useful
     * for binding a viseme controller's logical keys to a specific GLB's blendshape names once,
     * up front. The returned names preserve the model's original casing.
     */
    fun resolveVisemeMorphMap(
        availableMorphs: Iterable<String>
    ): Map<VisemeController.Viseme, String> {
        val resolved = LinkedHashMap<VisemeController.Viseme, String>()
        for ((viseme, candidates) in visemeCandidates) {
            val match = resolvePreservingCase(candidates, availableMorphs)
            if (match != null) resolved[viseme] = match
        }
        return resolved
    }

    /**
     * `true` if [availableMorphs] contains at least one mouth/jaw morph this map knows about, i.e.
     * the model can do *some* lip-sync. Equivalent to "is there a face to drive?".
     */
    fun hasAnyMouthMorph(availableMorphs: Iterable<String>): Boolean {
        for (name in availableMorphs) {
            if (allMouthMorphCandidates.contains(name.lowercase())) return true
        }
        return false
    }

    /**
     * Like [firstPresent], but returns the matching name **exactly as it appears in**
     * [available] (the model's own casing), which is what Filament's per-name APIs expect, rather
     * than the candidate's casing. Returns `null` when nothing matches.
     */
    private fun resolvePreservingCase(
        candidates: List<String>,
        available: Iterable<String>
    ): String? {
        // Map lower-cased model name -> original model name (first occurrence wins).
        val byLower = LinkedHashMap<String, String>()
        for (name in available) byLower.putIfAbsent(name.lowercase(), name)
        if (byLower.isEmpty()) return null
        for (candidate in candidates) {
            val hit = byLower[candidate.lowercase()]
            if (hit != null) return hit
        }
        return null
    }
}
