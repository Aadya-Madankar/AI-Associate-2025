package com.example.character

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Maps a live audio [amplitude] (0..1) onto the mouth/jaw blendshape weights that drive Nazim's
 * lip-sync.
 *
 * A pure-Kotlin, frame-driven controller with **no Android imports**, so it is fully
 * unit-testable.
 *
 * Per-frame usage:
 * ```
 * controller.setAmplitude(currentAmplitude)
 * controller.update(deltaSeconds)
 * faceMorph.apply(engine, controller.weights)
 * ```
 *
 * The emitted weight keys match the ARKit/MetaHuman blendshape names resolved by
 * `NazimRenderer`: `jawOpen`, `mouthOpen`, `mouthFunnel`, `mouthPucker`, `mouthSmileLeft`,
 * `mouthSmileRight`.
 */
class VisemeController {

    // --- Tunables -------------------------------------------------------------------------------
    private val jawGain = 0.82f          // max jaw-open weight
    private val jawShape = 0.6f          // perceptual curve on amplitude
    private val tauOpen = 0.05f          // fast attack (seconds)
    private val tauClose = 0.12f         // slow release (seconds)

    private val breathPeriod = 4.0f
    private val breathDepth = 0.05f

    // --- State --------------------------------------------------------------------------------
    private var jawTarget = 0f
    private var jaw = 0f

    private var breathPhase = 0f

    private val mutableWeights: MutableMap<String, Float> = linkedMapOf(
        "jawOpen" to 0f,
        "mouthOpen" to 0f,
        "mouthFunnel" to 0f,
        "mouthPucker" to 0f,
        "mouthSmileLeft" to 0f,
        "mouthSmileRight" to 0f
    )

    /** Read-only view of the current blendshape weights (all clamped 0..1). */
    val weights: Map<String, Float> get() = mutableWeights

    /**
     * Set the shaped jaw-open target from a raw [amplitude] in 0..1.
     * Shaped target = (a^[jawShape]) * [jawGain].
     */
    fun setAmplitude(amplitude: Float) {
        val clamped = amplitude.coerceIn(0f, 1f)
        jawTarget = clamped.toDouble().pow(jawShape.toDouble()).toFloat() * jawGain
    }

    /**
     * Advance the controller by [dtSeconds] and recompute [weights]. Integrates the jaw with an
     * asymmetric attack/release and layers a gentle breathing motion.
     */
    fun update(dtSeconds: Float) {
        val dt = if (dtSeconds.isNaN() || dtSeconds <= 0f) 0f else min(dtSeconds, 0.1f)
        breathPhase += dt

        integrateJaw(dt)

        // Breathing nudge, independent of speech.
        val breath = (sin((breathPhase / breathPeriod) * 2.0 * PI).toFloat() * 0.5f + 0.5f) * breathDepth

        // Base aperture from jaw + breathing.
        val jawOpen = clamp01(jaw + breath * 0.4f)
        val mouthOpen = clamp01(jaw * 0.9f + breath * 0.5f)

        // mouthFunnel tracks rounded loud vowels.
        val mouthFunnel = clamp01((jaw * jaw) * 0.55f)

        mutableWeights["jawOpen"] = jawOpen
        mutableWeights["mouthOpen"] = mouthOpen
        mutableWeights["mouthFunnel"] = mouthFunnel
        mutableWeights["mouthPucker"] = 0f

        // No smile shaping in the amplitude-only path — omit the keys so
        // NazimExpressionController's WARM-preset smile shows through NazimRenderer's
        // `visemeWeights[key] ?: expressionWeights[key] ?: 0f` merge (the `?:` never falls
        // through for an always-present 0f key).
        mutableWeights.remove("mouthSmileLeft")
        mutableWeights.remove("mouthSmileRight")
    }

    // --- Internals ----------------------------------------------------------------------------

    private fun integrateJaw(dt: Float) {
        if (dt <= 0f) return
        val tau = if (jawTarget > jaw) tauOpen else tauClose
        val alpha = 1f - exp((-dt / tau).toDouble()).toFloat()
        jaw += (jawTarget - jaw) * alpha
        jaw = clamp01(jaw)
    }

    private fun clamp01(v: Float): Float = max(0f, min(1f, v))
}
