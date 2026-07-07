package com.example.avatar

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.core.CompanionState
import com.example.models.Persona
import com.example.ui.components.AuroraOrb

private const val TAG = "AvatarView"
private const val MODEL_ASSET = "avatar.glb"
private const val MODEL_PATH = "models/avatar.glb"

/**
 * Renders the on-screen "meta human".
 *
 * Primary path: load `assets/models/avatar.glb` with SceneView (Filament) and drive its facial
 * morph targets every frame from a [LipSyncController] fed by the live [amplitude].
 *
 * Fallback path: if the GLB asset is absent, or anything in the 3D path throws, render the
 * [AuroraOrb] "presence" instead. This composable MUST compile and run whether or not the GLB
 * is bundled, so the SceneView path is guarded both at the asset level and with try/catch.
 */
@Composable
fun AvatarView(
    state: CompanionState,
    amplitude: Float,
    persona: Persona,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Runtime asset probe: only attempt the SceneView path if avatar.glb is actually bundled.
    val hasModel = remember {
        try {
            context.assets.list("models")?.any { it.equals(MODEL_ASSET, ignoreCase = true) } == true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not list models assets; falling back to AuroraOrb", t)
            false
        }
    }

    // If the 3D path fails at runtime we flip this and never touch SceneView again this session.
    var sceneFailed by remember { mutableStateOf(false) }

    if (!hasModel || sceneFailed) {
        AuroraOrb(state = state, amplitude = amplitude, persona = persona, modifier = modifier)
        return
    }

    AvatarScene(
        state = state,
        amplitude = amplitude,
        persona = persona,
        modifier = modifier,
        onFailure = { t ->
            Log.w(TAG, "SceneView avatar path failed; falling back to AuroraOrb", t)
            sceneFailed = true
        }
    )
}

/**
 * The SceneView-backed 3D avatar. Isolated in its own composable so that constructing/loading the
 * Filament model is wrapped in try/catch; on any throw we invoke [onFailure], which flips the
 * caller back to the [AuroraOrb] fallback.
 */
@Composable
private fun AvatarScene(
    state: CompanionState,
    amplitude: Float,
    persona: Persona,
    modifier: Modifier,
    onFailure: (Throwable) -> Unit
) {
    val lipSync = remember { LipSyncController() }

    // Frame clock for dt-based integration in the LipSyncController.
    val lastFrameNanos = remember { longArrayOf(0L) }

    // Keep the controller fed with the latest amplitude even between frame callbacks.
    val latestAmplitude = remember { floatArrayOf(0f) }
    LaunchedEffect(amplitude) { latestAmplitude[0] = amplitude.coerceIn(0f, 1f) }

    // Deferred failure: state must not be mutated synchronously during composition (e.g. while the
    // node list is being built), so we stash the throwable and report it from a LaunchedEffect.
    val loadError = remember { arrayOfNulls<Throwable>(1) }

    val engine = io.github.sceneview.rememberEngine()
    val modelLoader = io.github.sceneview.rememberModelLoader(engine)
    val environmentLoader = io.github.sceneview.rememberEnvironmentLoader(engine)

    // Load the model + build the node list once.
    val childNodes = io.github.sceneview.rememberNodes {
        try {
            val modelInstance = modelLoader.createModelInstance(MODEL_PATH)
            val modelNode = io.github.sceneview.node.ModelNode(
                modelInstance = modelInstance,
                scaleToUnits = 1.0f,
                centerOrigin = io.github.sceneview.math.Position(0f, 0f, 0f)
            )
            add(modelNode)
        } catch (t: Throwable) {
            loadError[0] = t
        }
    }

    // Resolve the face renderable (the entity whose morph targets include "jawOpen") once.
    val faceMorph = remember(childNodes) {
        try {
            resolveFaceMorph(engine, childNodes)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve morph target entity", t)
            null
        }
    }

    // Surface any deferred load error to the caller (outside of composition).
    LaunchedEffect(childNodes) {
        loadError[0]?.let { onFailure(it) }
    }

    io.github.sceneview.Scene(
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

                // Drive lip-sync from the live amplitude.
                lipSync.setAmplitude(latestAmplitude[0])
                lipSync.update(dt)

                faceMorph?.apply(engine, lipSync.weights)

                // Subtle living head sway layered on top of the model node.
                val sway = lipSync.headSway(frameTimeNanos / 1_000_000_000f)
                applyHeadSway(childNodes, sway)
            } catch (t: Throwable) {
                onFailure(t)
            }
        }
    )
}

/**
 * Binds the face renderable entity that owns the "jawOpen" morph target and maps the
 * [LipSyncController] weight keys onto that entity's morph-target index layout. Built once and
 * reused every frame to avoid per-frame allocation and name lookups.
 */
private class FaceMorphBinding(
    private val entity: Int,
    private val morphTargetCount: Int,
    /** index of each known weight key inside this entity's morph-target array, or -1 if absent. */
    private val keyIndices: Map<String, Int>,
    private val scratch: FloatArray
) {
    fun apply(engine: com.google.android.filament.Engine, weights: Map<String, Float>) {
        if (morphTargetCount <= 0) return
        val rm = engine.renderableManager
        val instance = rm.getInstance(entity)
        if (instance == 0) return

        // Reset scratch then write known weights at their resolved indices.
        java.util.Arrays.fill(scratch, 0f)
        for ((key, idx) in keyIndices) {
            if (idx in 0 until morphTargetCount) {
                scratch[idx] = (weights[key] ?: 0f).coerceIn(0f, 1f)
            }
        }
        rm.setMorphWeights(instance, scratch, 0)
    }
}

/**
 * Scans the loaded model's renderable entities for the one exposing a "jawOpen" morph target and
 * returns a reusable [FaceMorphBinding]. Returns null if no morph-target face is found.
 */
private fun resolveFaceMorph(
    engine: com.google.android.filament.Engine,
    childNodes: List<io.github.sceneview.node.Node>
): FaceMorphBinding? {
    val rm = engine.renderableManager

    val lipSyncKeys = listOf(
        "jawOpen", "mouthOpen", "mouthFunnel", "eyeBlinkLeft", "eyeBlinkRight"
    )

    for (node in childNodes) {
        val modelNode = node as? io.github.sceneview.node.ModelNode ?: continue
        val instance = modelNode.modelInstance
        val asset = runCatching { instance.asset }.getOrNull() ?: continue
        val entities = runCatching { instance.entities }.getOrNull() ?: continue

        for (entity in entities) {
            // Must be a renderable with morph targets.
            if (!rm.hasComponent(entity)) continue
            val rmInstance = rm.getInstance(entity)
            if (rmInstance == 0) continue
            val count = rm.getMorphTargetCount(rmInstance)
            if (count <= 0) continue

            val names: Array<String> = runCatching { asset.getMorphTargetNames(entity) }
                .getOrNull() ?: emptyArray()

            // Build a case-insensitive name -> index map for this entity.
            val nameToIndex = HashMap<String, Int>(names.size)
            names.forEachIndexed { i, n -> nameToIndex[n.lowercase()] = i }

            if (!nameToIndex.containsKey("jawopen")) continue

            val keyIndices = HashMap<String, Int>(lipSyncKeys.size)
            for (key in lipSyncKeys) {
                keyIndices[key] = nameToIndex[key.lowercase()] ?: -1
            }

            Log.i(TAG, "Bound face morph entity=$entity morphCount=$count names=${names.toList()}")
            return FaceMorphBinding(
                entity = entity,
                morphTargetCount = count,
                keyIndices = keyIndices,
                scratch = FloatArray(count)
            )
        }
    }
    Log.i(TAG, "No 'jawOpen' morph target found in model; lip-sync disabled")
    return null
}

/** Applies a gentle yaw/pitch/bob to the root model node for a "living" idle feel. */
private fun applyHeadSway(
    childNodes: List<io.github.sceneview.node.Node>,
    sway: Triple<Float, Float, Float>
) {
    val root = childNodes.firstOrNull { it is io.github.sceneview.node.ModelNode } ?: return
    val (yawDeg, pitchDeg, bob) = sway
    root.rotation = io.github.sceneview.math.Rotation(x = pitchDeg, y = yawDeg, z = 0f)
    val pos = root.position
    root.position = io.github.sceneview.math.Position(x = pos.x, y = bob * 0.02f, z = pos.z)
}
