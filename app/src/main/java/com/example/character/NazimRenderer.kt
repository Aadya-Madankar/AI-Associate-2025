package com.example.character

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.google.android.filament.Engine
import com.google.android.filament.gltfio.Animator
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes

private const val TAG = "NazimRenderer"

/** Seconds over which one animation clip cross-fades into the next on a state change. */
private const val CROSS_FADE_SECONDS = 0.35f

/**
 * Framing constants for the bundled `nazim.glb`. This Sketchfab/FBX rig reports a misleading
 * bind-pose bounding box (skinning re-inflates it at runtime), so SceneView's `scaleToUnits`
 * over-scales it. We bypass that and place the model explicitly: a uniform scale + a base Y so
 * the standing figure sits centered, and a base yaw so it faces the user. Head-sway is applied
 * on top of the base yaw/Y each frame. Retune these three if a different GLB is dropped in.
 */
private const val MODEL_SCALE = 0.29f
private const val MODEL_BASE_Y = -0.17f
private const val MODEL_FACING_YAW = 0f

/**
 * The SceneView/Filament-backed renderer for the **Nazim** MetaHuman.
 *
 * Loads `assets/nazim.glb` via the SceneView 2.3.3 API (`rememberEngine` / `rememberModelLoader` /
 * `rememberEnvironmentLoader` / `rememberNodes` + [io.github.sceneview.Scene]; embedded textures
 * load automatically). Each frame it independently drives three Filament subsystems on the loaded
 * model:
 *
 *  1. **Skeleton** — plays the named animation clip chosen by [NazimAnimationMap.resolveClip] for
 *     the current [state], looping. On a state change it **cross-fades** from the previous clip to
 *     the new one (Filament `Animator.applyAnimation` + `applyCrossFade` + `updateBoneMatrices`).
 *  2. **Face morph targets** — mouth/jaw visemes from [VisemeController] (fed by [amplitude]) merged
 *     with brow/eye/cheek expression + blink from [NazimExpressionController] (fed by [state]),
 *     pushed via `RenderableManager.setMorphWeights`.
 *  3. **Root transform** — a subtle living head-sway from [NazimExpressionController.headSway].
 *
 * Skeleton animation (BoneManager) and morph weights (per-renderable morph weights) are independent
 * Filament subsystems, so driving both in the same frame does not conflict.
 *
 * **Every** SceneView/Filament call is wrapped in try/catch. On any throw — missing asset, loader
 * failure, per-frame error — [onFailure] is invoked so [NazimView] can swap to
 * [NazimFallbackPortrait]. This composable must never be the reason the screen goes blank.
 *
 * This is an internal building block; callers should use [NazimView], which owns the GLB-presence
 * probe and the fallback decision. The public signature is fixed by that caller:
 * `(state, amplitude, modifier, onFailure)`.
 */
@Composable
internal fun NazimRenderer(
    state: NazimState,
    amplitude: Float,
    modifier: Modifier = Modifier,
    onFailure: (Throwable) -> Unit
) {
    val viseme = remember { VisemeController() }
    val expression = remember { NazimExpressionController() }

    // Frame clock for dt-based integration.
    val lastFrameNanos = remember { longArrayOf(0L) }

    // Keep the controllers fed with the latest inputs between frame callbacks.
    val latestAmplitude = remember { floatArrayOf(0f) }
    LaunchedEffect(amplitude) { latestAmplitude[0] = amplitude.coerceIn(0f, 1f) }

    // Latest requested state, consumed on the next frame (so all driving happens off the UI path).
    val latestState = remember { arrayOf(state) }
    LaunchedEffect(state) {
        latestState[0] = state
        try {
            expression.setState(state)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set Nazim expression state", t)
        }
    }

    // Deferred load error: composition must not synchronously call onFailure while building nodes.
    val loadError = remember { arrayOfNulls<Throwable>(1) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Load the Nazim model + build the node list once.
    val childNodes = rememberNodes {
        try {
            val modelInstance = modelLoader.createModelInstance(NazimAssets.MODEL_PATH)
            val modelNode = ModelNode(
                modelInstance = modelInstance,
                autoAnimate = false
                // No scaleToUnits: this rig's bind-pose bbox is unreliable (see the framing
                // constants above). We size/place it explicitly below instead.
            )
            modelNode.scale = Scale(MODEL_SCALE)
            add(modelNode)
        } catch (t: Throwable) {
            loadError[0] = t
        }
    }

    // Resolve the face renderable (the entity whose morph targets include Nazim's blendshapes).
    val faceMorph = remember(childNodes) {
        try {
            resolveNazimFaceMorph(engine, childNodes)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve Nazim morph target entity", t)
            null
        }
    }

    // Resolve the skeleton-animation driver (clip enumeration + cross-fade state) once.
    val animation = remember(childNodes) {
        try {
            NazimAnimationDriver.create(childNodes)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve Nazim animation clips", t)
            null
        }
    }

    // Surface any deferred load error to the caller (outside composition).
    LaunchedEffect(childNodes) {
        loadError[0]?.let { onFailure(it) }
    }

    Scene(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        environmentLoader = environmentLoader,
        childNodes = childNodes,
        isOpaque = false,
        onFrame = { frameTimeNanos ->
            try {
                val prev = lastFrameNanos[0]
                lastFrameNanos[0] = frameTimeNanos
                val dt = if (prev == 0L) 0f else (frameTimeNanos - prev) / 1_000_000_000f

                // 1) Skeleton: advance the looping clip + cross-fade for the current state.
                animation?.update(latestState[0], dt)

                // 2) Face: advance mouth (audio-driven) and expression (state-driven) controllers.
                viseme.setAmplitude(latestAmplitude[0])
                viseme.update(dt)
                expression.update(dt)
                faceMorph?.apply(engine, viseme.weights, expression.weights)

                // 3) Subtle living head sway on the root node, scaled by idle energy.
                val sway = expression.headSway()
                applyHeadSway(childNodes, sway)
            } catch (t: Throwable) {
                onFailure(t)
            }
        }
    )
}

// =================================================================================================
// Skeleton animation driver (clip selection + looping + cross-fade)
// =================================================================================================

/**
 * Drives the loaded model's skeletal animation entirely through the Filament [Animator]
 * (`applyAnimation` / `applyCrossFade` / `updateBoneMatrices`), bypassing
 * [ModelNode.playAnimation]. We manage clip time manually so we can cross-fade on a state change.
 *
 * Why not [ModelNode.playAnimation]? Because manual `applyAnimation` and `ModelNode`'s internal
 * `applyAnimations` would both write the same bones. By never calling `playAnimation`, the
 * `ModelNode`'s internal playing-animation list stays empty and only this driver touches the
 * skeleton — the documented-correct way to run a true Filament cross-fade.
 */
private class NazimAnimationDriver private constructor(
    private val animator: Animator,
    private val clipCount: Int,
    private val clipNames: List<String>,
    private val durations: FloatArray
) {
    // The clip we are currently playing (and fading *to*), as a clip index, or -1 if none.
    private var currentIndex: Int = -1
    private var currentTime: Float = 0f

    // The clip we are fading *from* during a cross-fade, or -1 when not fading.
    private var fadeFromIndex: Int = -1
    private var fadeFromTime: Float = 0f

    // Cross-fade progress: 0 at the start of a fade, 1 when fully on the new clip.
    private var fadeProgress: Float = 1f

    // The NazimState whose clip we last resolved, so we only re-resolve on change.
    private var resolvedState: NazimState? = null

    /**
     * Advance animation by [dt] seconds for [state]. On a state change, resolves the new clip and
     * starts a cross-fade from the currently playing clip. Safe to call every frame.
     */
    fun update(state: NazimState, dt: Float) {
        if (clipCount <= 0) return
        val step = if (dt.isNaN() || dt <= 0f) 0f else minOf(dt, 0.1f)

        if (state != resolvedState) {
            resolvedState = state
            val target = resolveClipIndex(state)
            if (target >= 0 && target != currentIndex) {
                if (currentIndex >= 0) {
                    // Begin cross-fading from the clip currently playing.
                    fadeFromIndex = currentIndex
                    fadeFromTime = currentTime
                    fadeProgress = 0f
                }
                currentIndex = target
                currentTime = 0f
            } else if (currentIndex < 0 && target >= 0) {
                currentIndex = target
                currentTime = 0f
            }
        }

        if (currentIndex < 0) return

        // Advance the target clip's loop time.
        currentTime = loopTime(currentTime + step, currentIndex)

        // Filament call order is mandatory: target applyAnimation -> previous applyCrossFade ->
        // updateBoneMatrices.
        animator.applyAnimation(currentIndex, currentTime)

        if (fadeFromIndex >= 0 && fadeProgress < 1f) {
            // Keep the outgoing clip ticking so the blend looks natural, then mix it under the
            // target by (1 - alpha) via applyCrossFade(prev, prevTime, alpha).
            fadeFromTime = loopTime(fadeFromTime + step, fadeFromIndex)
            fadeProgress = (fadeProgress + step / CROSS_FADE_SECONDS).coerceIn(0f, 1f)
            animator.applyCrossFade(fadeFromIndex, fadeFromTime, fadeProgress)
            if (fadeProgress >= 1f) {
                fadeFromIndex = -1
            }
        }

        animator.updateBoneMatrices()
    }

    /**
     * Resolve [state] to a concrete clip index using [NazimAnimationMap]. If no candidate name
     * matches (a rig with unconventional clip names), fall back to the model's first clip so it
     * still animates rather than freezing on the bind pose.
     */
    private fun resolveClipIndex(state: NazimState): Int {
        val name = NazimAnimationMap.resolveClip(state, clipNames) ?: return if (clipCount > 0) 0 else -1
        val idx = clipNames.indexOfFirst { it.equals(name, ignoreCase = true) }
        return if (idx >= 0) idx else 0
    }

    /** Wrap [t] into the clip's [0, duration) range, treating a non-positive duration as 0. */
    private fun loopTime(t: Float, index: Int): Float {
        val dur = durations.getOrElse(index) { 0f }
        if (dur <= 0f) return 0f
        var x = t % dur
        if (x < 0f) x += dur
        return x
    }

    companion object {
        /**
         * Builds a driver for the first [ModelNode] in [childNodes] that exposes ≥1 animation clip,
         * or `null` if the model has no clips (the procedural head-sway / morph path then stands in).
         */
        fun create(childNodes: List<Node>): NazimAnimationDriver? {
            for (node in childNodes) {
                val modelNode = node as? ModelNode ?: continue
                val animator = runCatching { modelNode.modelInstance.animator }.getOrNull() ?: continue
                val count = runCatching { animator.animationCount }.getOrNull() ?: 0
                if (count <= 0) continue

                val names = ArrayList<String>(count)
                val durations = FloatArray(count)
                for (i in 0 until count) {
                    names.add(runCatching { animator.getAnimationName(i) }.getOrNull() ?: "clip$i")
                    durations[i] = runCatching { animator.getAnimationDuration(i) }.getOrNull() ?: 0f
                }
                Log.i(TAG, "Bound Nazim animation: $count clips $names")
                return NazimAnimationDriver(animator, count, names, durations)
            }
            Log.i(TAG, "No animation clips found in Nazim model; skeletal animation disabled")
            return null
        }
    }
}

// =================================================================================================
// Face morph-target binding (visemes + expression)
// =================================================================================================

/**
 * Binds the face renderable entity that owns Nazim's mouth/expression morph targets and maps the
 * controller weight keys onto that entity's morph-target index layout. Built once and reused every
 * frame to avoid per-frame allocation and name lookups.
 *
 * Unions the viseme and expression key sets so both controllers can write into one
 * `setMorphWeights` call.
 */
private class NazimFaceMorphBinding(
    private val entity: Int,
    private val morphTargetCount: Int,
    /** index of each known weight key inside this entity's morph-target array, or -1 if absent. */
    private val keyIndices: Map<String, Int>,
    private val scratch: FloatArray
) {
    fun apply(
        engine: Engine,
        visemeWeights: Map<String, Float>,
        expressionWeights: Map<String, Float>
    ) {
        if (morphTargetCount <= 0) return
        val rm = engine.renderableManager
        val instance = rm.getInstance(entity)
        if (instance == 0) return

        java.util.Arrays.fill(scratch, 0f)
        for ((key, idx) in keyIndices) {
            if (idx in 0 until morphTargetCount) {
                val w = (visemeWeights[key] ?: 0f).coerceAtLeast(expressionWeights[key] ?: 0f)
                scratch[idx] = w.coerceIn(0f, 1f)
            }
        }
        rm.setMorphWeights(instance, scratch, 0)
    }
}

/** The full set of blendshape keys Nazim's controllers can emit (viseme + expression). */
private val NAZIM_BLENDSHAPE_KEYS: List<String> = listOf(
    // Visemes (mouth/jaw)
    "jawOpen", "mouthOpen", "mouthFunnel", "mouthPucker",
    // Expression (brows / eyes / cheeks / lips)
    "eyeBlinkLeft", "eyeBlinkRight",
    "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
    "browDownLeft", "browDownRight",
    "eyeWideLeft", "eyeWideRight",
    "cheekSquintLeft", "cheekSquintRight",
    "mouthSmileLeft", "mouthSmileRight",
    "mouthFrownLeft", "mouthFrownRight"
)

/**
 * Scans the loaded model's renderable entities for the one exposing a `jawOpen` morph target and
 * returns a reusable [NazimFaceMorphBinding]. Returns null if no morph-target face is found, in
 * which case lip-sync/expression is silently disabled (the model still renders and animates).
 */
private fun resolveNazimFaceMorph(
    engine: Engine,
    childNodes: List<Node>
): NazimFaceMorphBinding? {
    val rm = engine.renderableManager

    for (node in childNodes) {
        val modelNode = node as? ModelNode ?: continue
        val instance = modelNode.modelInstance
        val asset = runCatching { instance.asset }.getOrNull() ?: continue
        val entities = runCatching { instance.entities }.getOrNull() ?: continue

        for (entity in entities) {
            if (!rm.hasComponent(entity)) continue
            val rmInstance = rm.getInstance(entity)
            if (rmInstance == 0) continue
            val count = rm.getMorphTargetCount(rmInstance)
            if (count <= 0) continue

            val names: Array<String> = runCatching { asset.getMorphTargetNames(entity) }
                .getOrNull() ?: emptyArray()

            val nameToIndex = HashMap<String, Int>(names.size)
            names.forEachIndexed { i, n -> nameToIndex[n.lowercase()] = i }

            if (!nameToIndex.containsKey("jawopen")) continue

            val keyIndices = HashMap<String, Int>(NAZIM_BLENDSHAPE_KEYS.size)
            for (key in NAZIM_BLENDSHAPE_KEYS) {
                keyIndices[key] = nameToIndex[key.lowercase()] ?: -1
            }

            Log.i(TAG, "Bound Nazim face morph entity=$entity morphCount=$count")
            return NazimFaceMorphBinding(
                entity = entity,
                morphTargetCount = count,
                keyIndices = keyIndices,
                scratch = FloatArray(count)
            )
        }
    }
    Log.i(TAG, "No 'jawOpen' morph target found in Nazim model; facial animation disabled")
    return null
}

/** Applies a gentle yaw/pitch/bob to the root model node for a "living" idle feel. */
private fun applyHeadSway(
    childNodes: List<Node>,
    sway: Triple<Float, Float, Float>
) {
    val root = childNodes.firstOrNull { it is ModelNode } ?: return
    val (yawDeg, pitchDeg, bob) = sway
    root.rotation = Rotation(x = pitchDeg, y = MODEL_FACING_YAW + yawDeg, z = 0f)
    root.position = Position(x = 0f, y = MODEL_BASE_Y + bob * 0.02f, z = 0f)
}
