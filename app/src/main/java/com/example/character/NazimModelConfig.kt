package com.example.character

/**
 * Pure constants + data for the **full animated 3D Nazim** (the rigged, skinned GLB with named
 * skeletal animation clips *and* ARKit/MetaHuman blendshapes). This is the single tuning surface
 * for the SceneView/Filament render path in `NazimRenderer`; it contains **no** SceneView,
 * Filament, Android, or Compose imports, performs no I/O, and can never throw. That keeps the
 * fallback ladder in `NazimView` (animated GLB → portrait PNG → vector portrait) honest: a
 * misconfigured constant can never be the reason the screen goes blank — only an actual SceneView
 * call wrapped in `NazimRenderer`'s try/catch + `onFailure` can trip the fallback.
 *
 * Why a config object at all? The animated Nazim layers two *independent* Filament subsystems
 * driven from the same `onFrame`:
 *  1. **Skeleton** — looping/cross-faded body+head idle clips advanced by
 *     `ModelNode.playAnimation(...)` (or the raw gltfio `Animator` for true cross-fades).
 *  2. **Morph weights** — visemes ([VisemeController]) + expression ([NazimExpressionController])
 *     pushed via `RenderableManager.setMorphWeights(...)`.
 * They target different Filament managers (BoneManager vs. morph weights) so they compose without
 * conflict. This object holds the asset locations and the dial settings both layers read.
 *
 * All numeric tunables are documented with their unit and intended range so they can be tweaked
 * without re-reading the renderer.
 */
object NazimModelConfig {

    // =============================================================================================
    // 1) ASSET LOCATIONS
    // =============================================================================================

    /**
     * Animation-asset paths for the animated Nazim. Deliberately **not** named `NazimAssets`: the
     * single source of truth for the model location and presence checks (`hasModel`/`hasPortrait`)
     * is the top-level [com.example.character.NazimAssets]. This nested object only adds the
     * *animation-clip* asset layout (separate animation GLBs) on top of it, and re-exports the
     * model path via [MODEL_PATH] so a caller already holding `NazimModelConfig.Assets` has one
     * place to read both.
     *
     * Paths are **relative to the APK `assets/` root**, exactly as SceneView 2.3.3's
     * `ModelLoader.createModelInstance(assetFileLocation: String)` expects (e.g. `"nazim.glb"` or
     * `"models/nazim.glb"`).
     */
    object Assets {

        /**
         * The rigged Nazim GLB (skeleton + skin + blendshapes, ideally with embedded animation
         * clips). Re-exports the **single** source of truth, [com.example.character.NazimAssets.MODEL_PATH],
         * rather than re-declaring the literal, so the presence probe and the loader can never drift.
         * Loaded via `modelLoader.createModelInstance(NazimModelConfig.Assets.MODEL_PATH)`.
         */
        const val MODEL_PATH: String = NazimAssets.MODEL_PATH

        /**
         * Optional folder (under `assets/`) holding **separate** animation-only GLBs when the
         * idle/gesture clips are *not* embedded in [MODEL_PATH] (the common MetaHuman/Mixamo
         * workflow: one mesh GLB + several animation GLBs that retarget onto the same rig).
         *
         * When the renderer finds files here it may load them as extra `ModelInstance`s purely for
         * their `Animator` clips. When empty / absent, animation comes from clips embedded in
         * [MODEL_PATH] (and, failing that, the procedural head-sway in `NazimRenderer` stands in).
         * No trailing slash.
         */
        const val ANIMATION_DIR: String = "nazim_anim"

        /**
         * Candidate per-state animation-GLB file names looked up inside [ANIMATION_DIR] when clips
         * are shipped as separate files. The renderer should probe these in order and use the
         * first that exists; a missing file is **not** an error (procedural idle covers the gap).
         * File names are bare (no directory prefix); join with [ANIMATION_DIR] using `/`.
         */
        val ANIMATION_FILES: Map<NazimState, List<String>> = mapOf(
            NazimState.IDLE to listOf("idle.glb", "breathe.glb"),
            NazimState.LISTENING to listOf("listen.glb", "alert.glb", "idle.glb"),
            NazimState.THINKING to listOf("think.glb", "ponder.glb", "idle.glb"),
            NazimState.SPEAKING to listOf("speak.glb", "talk.glb", "idle.glb"),
            NazimState.ACTING to listOf("act.glb", "busy.glb", "idle.glb"),
            NazimState.ERROR to listOf("concern.glb", "idle.glb")
        )

        /** Full asset path to a named animation GLB inside [ANIMATION_DIR]. */
        fun animationPath(fileName: String): String = "$ANIMATION_DIR/$fileName"
    }

    // =============================================================================================
    // 2) MORPH / VISEME TUNABLES (lip-sync + expression)
    // =============================================================================================

    /**
     * Tunables for the morph-weight (blendshape) layer: viseme smoothing and clamps applied when
     * `NazimRenderer` merges [VisemeController.weights] + [NazimExpressionController.weights] into
     * the per-entity `RenderableManager.setMorphWeights` scratch array.
     */
    object Visemes {

        /**
         * Hard ceiling on the `jawOpen` blendshape, in morph-weight units [0..1]. MetaHuman jaw
         * rigs look unnatural fully open; clamping a touch below 1.0 keeps the mouth in its
         * believable range even on loud peaks. Applied as a final `min(weight, MAX_JAW_OPEN)`.
         */
        const val MAX_JAW_OPEN: Float = 0.82f

        /**
         * Global ceiling for every *other* morph weight, in [0..1]. A small safety margin below
         * 1.0 avoids harsh blendshape extremes (e.g. over-wide eyes) when viseme + expression
         * contributions for the same key stack.
         */
        const val MAX_MORPH_WEIGHT: Float = 0.95f

        /**
         * Per-frame viseme smoothing factor (exponential-moving-average alpha), in [0..1], used if
         * the renderer applies an additional output smoothing pass on top of the controllers'
         * internal attack/release. Higher = snappier (follows the audio faster), lower = smoother
         * (more damped). The controllers already smooth internally, so this is intentionally high
         * to avoid double-lag while still de-jittering frame-to-frame morph noise.
         *
         * `smoothed = smoothed + (target - smoothed) * SMOOTHING`.
         */
        const val SMOOTHING: Float = 0.55f

        /**
         * Below this absolute morph weight, treat the value as zero before writing it. Snapping
         * tiny residuals to 0 keeps fully-relaxed faces crisp (no perpetual micro-twitch) and lets
         * a controller that *omits* a key fall through to the other controller's value during the
         * merge, matching the `?:` fall-through contract documented in [VisemeController].
         */
        const val DEADZONE: Float = 0.01f
    }

    // =============================================================================================
    // 3) ANIMATION (skeleton) TUNABLES
    // =============================================================================================

    /**
     * Tunables for the skeletal-animation layer: idle-clip selection, playback speed, and
     * cross-fade timing between clips when the conversational state changes.
     */
    object Animation {

        /**
         * Whether the body/idle skeleton clip should loop. Idle/talk/listen poses are continuous
         * loops, so this is `true`. (One-shot gestures, if ever added, would pass `false` to
         * `ModelNode.playAnimation(name, loop = false)`.)
         */
        const val LOOP_IDLE: Boolean = true

        /** Default playback speed multiplier for idle clips (1.0 = authored speed). */
        const val DEFAULT_SPEED: Float = 1.0f

        /**
         * Cross-fade duration in **seconds** between the outgoing and incoming clip on a state
         * change. Drives the `alpha` ramp 0→1 used with the raw gltfio
         * `Animator.applyCrossFade(prevIdx, prevTime, alpha)`; when `alpha` reaches 1 the renderer
         * drops back to playing the target clip alone. Short enough to feel responsive, long
         * enough to avoid a visible pop.
         */
        const val CROSSFADE_SECONDS: Float = 0.25f

        /**
         * Per-state playback speed scale, layered on top of [DEFAULT_SPEED]. Lets thinking idle
         * slow slightly and acting idle quicken without re-authoring clips. Mirrors the energy
         * scaling in [NazimExpressionController.headSway].
         */
        val SPEED_FOR_STATE: Map<NazimState, Float> = mapOf(
            NazimState.IDLE to 1.0f,
            NazimState.LISTENING to 1.05f,
            NazimState.THINKING to 0.9f,
            NazimState.SPEAKING to 1.1f,
            NazimState.ACTING to 1.2f,
            NazimState.ERROR to 0.95f
        )

        /**
         * Preferred animation-clip names per state, in priority order. The renderer enumerates the
         * loaded model's clips via the gltfio `Animator` (`getAnimationName(i)` for
         * `i in 0 until animationCount`) and picks the first candidate that exists (see
         * [resolveClipName]). Names are matched case-insensitively. If none match, the renderer
         * falls back to clip index 0 (or pure procedural sway when there are no clips at all), so
         * an unknown rig still animates.
         */
        val CLIP_CANDIDATES: Map<NazimState, List<String>> = mapOf(
            NazimState.IDLE to listOf("Idle", "Breathe", "Breathing", "Neutral"),
            NazimState.LISTENING to listOf("Listen", "Listening", "Alert", "Idle"),
            NazimState.THINKING to listOf("Think", "Thinking", "Ponder", "Idle"),
            NazimState.SPEAKING to listOf("Speak", "Speaking", "Talk", "Talking", "Idle"),
            NazimState.ACTING to listOf("Act", "Acting", "Busy", "Work", "Idle"),
            NazimState.ERROR to listOf("Concern", "Concerned", "Sad", "Idle")
        )
    }

    // =============================================================================================
    // 4) PERFORMANCE BUDGET
    // =============================================================================================

    /**
     * Target frame budget for the per-frame Nazim drive (controller updates + morph upload +
     * head sway). Used by the renderer to decide a target frame rate and to throttle expensive
     * work if a frame runs long.
     */
    object Performance {

        /** Target frames per second for the SceneView render loop. */
        const val TARGET_FPS: Int = 60

        /** Target per-frame budget in **nanoseconds** (≈ 16.67 ms at 60 FPS). */
        const val FRAME_BUDGET_NANOS: Long = 1_000_000_000L / TARGET_FPS

        /** Target per-frame budget in **milliseconds**, for logging/comparison. */
        const val FRAME_BUDGET_MILLIS: Float = 1_000f / TARGET_FPS

        /**
         * Largest delta-time, in **seconds**, the renderer should integrate in a single frame.
         * After a stall (GC pause, backgrounding) the wall-clock `dt` can spike; clamping it keeps
         * the jaw/expression/blink integrators stable instead of jumping. Matches the internal
         * `min(dt, 0.1f)` guards already in the controllers.
         */
        const val MAX_FRAME_DELTA_SECONDS: Float = 0.1f
    }

    // =============================================================================================
    // 5) PURE HELPERS (no SceneView dependency — safe to call from anywhere)
    // =============================================================================================

    /**
     * Picks the best available animation-clip name for [state] from a model's actual clip list.
     *
     * Pure string logic — it never touches SceneView/Filament, so callers in `NazimRenderer` can
     * enumerate clip names once (via the gltfio `Animator`) and resolve a target name here without
     * risking a throw. Matching is case-insensitive and follows [Animation.CLIP_CANDIDATES]
     * priority order.
     *
     * @param state the conversational state to animate.
     * @param availableClipNames every clip name exposed by the loaded model (any order/casing).
     * @return the matched clip name **exactly as it appears in [availableClipNames]** (preserving
     *   the model's casing so it can be passed straight to `playAnimation(name)`), or `null` if no
     *   candidate matches — in which case the caller should fall back to clip index 0 or the
     *   procedural idle.
     */
    fun resolveClipName(state: NazimState, availableClipNames: List<String>): String? {
        if (availableClipNames.isEmpty()) return null
        val byLower = availableClipNames.associateBy { it.lowercase() }
        for (candidate in Animation.CLIP_CANDIDATES[state].orEmpty()) {
            byLower[candidate.lowercase()]?.let { return it }
        }
        return null
    }

    /** Effective playback speed for [state] = [Animation.DEFAULT_SPEED] × per-state scale. */
    fun speedForState(state: NazimState): Float =
        Animation.DEFAULT_SPEED * (Animation.SPEED_FOR_STATE[state] ?: 1.0f)

    /**
     * Clamps a single resolved morph weight to its allowed range, applying the per-key jaw ceiling
     * and the global ceiling, plus the [Visemes.DEADZONE] snap-to-zero. Keys are matched
     * case-insensitively against `"jawOpen"`.
     *
     * @param key blendshape key (e.g. `"jawOpen"`, `"mouthSmileLeft"`).
     * @param weight raw weight, typically already in [0..1] from a controller.
     */
    fun clampMorphWeight(key: String, weight: Float): Float {
        val ceiling = if (key.equals("jawOpen", ignoreCase = true)) {
            Visemes.MAX_JAW_OPEN
        } else {
            Visemes.MAX_MORPH_WEIGHT
        }
        val clamped = weight.coerceIn(0f, ceiling)
        return if (clamped < Visemes.DEADZONE) 0f else clamped
    }
}
