package com.example.character

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Maps a live audio [amplitude] (0..1) — and optional [Viseme] phoneme hints — onto the
 * mouth/jaw blendshape weights that drive Nazim's lip-sync.
 *
 * This is the Nazim analogue of `com.example.avatar.LipSyncController`: a pure-Kotlin,
 * frame-driven controller with **no Android imports**, so it is fully unit-testable.
 *
 * Why a separate controller? Nazim is amplitude-driven today but designed to be upgraded to
 * phoneme→viseme mapping (SCOPE.md §2). [setViseme] lets a future phoneme stream nudge the
 * mouth shape (rounding, spreading, closure) on top of the amplitude-driven jaw, while
 * amplitude-only sessions degrade gracefully to a clean open/close.
 *
 * Per-frame usage:
 * ```
 * controller.setAmplitude(currentAmplitude)
 * controller.setViseme(Viseme.OO, 0.7f)   // optional phoneme hint
 * controller.update(deltaSeconds)
 * faceMorph.apply(engine, controller.weights)
 * ```
 *
 * The emitted weight keys match the ARKit/MetaHuman blendshape names resolved by
 * `NazimRenderer`: `jawOpen`, `mouthOpen`, `mouthFunnel`, `mouthPucker`, `mouthSmileLeft`,
 * `mouthSmileRight`.
 */
class VisemeController {

    /**
     * Coarse viseme categories. A phoneme-to-viseme front-end (future work) maps phonemes to
     * one of these; the controller blends the corresponding mouth shaping over the
     * amplitude-driven jaw. [NEUTRAL] disables shaping (pure amplitude lip-sync).
     */
    enum class Viseme {
        /** No shaping hint — jaw follows amplitude only. */
        NEUTRAL,

        /** Open vowels (ah/aa) — wide jaw, relaxed lips. */
        AA,

        /** Rounded vowels (oo/uw) — funnel + pucker, narrower aperture. */
        OO,

        /** Spread vowels (ee/iy) — wide smile, small aperture. */
        EE,

        /** Bilabial closures (m/b/p) — lips shut, jaw closes. */
        MBP,

        /** Labiodental (f/v) — slight lower-lip tuck, small aperture. */
        FV
    }

    // --- Tunables (mirrors LipSyncController feel) --------------------------------------------
    private val jawGain = 0.82f          // max jaw-open weight
    private val jawShape = 0.6f          // perceptual curve on amplitude
    private val tauOpen = 0.05f          // fast attack (seconds)
    private val tauClose = 0.12f         // slow release (seconds)
    private val tauShape = 0.07f         // viseme-shape smoothing time constant
    private val visemeDecay = 2.2f       // how fast an un-refreshed viseme hint fades (per sec)

    private val breathPeriod = 4.0f
    private val breathDepth = 0.05f

    // --- State --------------------------------------------------------------------------------
    private var jawTarget = 0f
    private var jaw = 0f

    private var activeViseme = Viseme.NEUTRAL
    private var visemeIntensityTarget = 0f
    private var visemeIntensity = 0f

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
     * Provide an optional phoneme [viseme] hint with an [intensity] in 0..1. The hint blends
     * over the amplitude-driven jaw and naturally decays if not refreshed each turn, so a
     * dropped phoneme stream simply relaxes back to amplitude-only lip-sync.
     *
     * Passing [Viseme.NEUTRAL] clears any active shaping.
     */
    fun setViseme(viseme: Viseme, intensity: Float = 1f) {
        if (viseme == Viseme.NEUTRAL) {
            visemeIntensityTarget = 0f
            return
        }
        activeViseme = viseme
        visemeIntensityTarget = intensity.coerceIn(0f, 1f)
    }

    /**
     * Advance the controller by [dtSeconds] and recompute [weights]. Integrates the jaw with an
     * asymmetric attack/release, decays the viseme hint, and layers a gentle breathing motion.
     */
    fun update(dtSeconds: Float) {
        val dt = if (dtSeconds.isNaN() || dtSeconds <= 0f) 0f else min(dtSeconds, 0.1f)
        breathPhase += dt

        integrateJaw(dt)
        integrateViseme(dt)

        // Breathing nudge, independent of speech.
        val breath = (sin((breathPhase / breathPeriod) * 2.0 * PI).toFloat() * 0.5f + 0.5f) * breathDepth

        // Base aperture from jaw + breathing.
        val baseJaw = clamp01(jaw + breath * 0.4f)
        var jawOpen = baseJaw
        var mouthOpen = clamp01(jaw * 0.9f + breath * 0.5f)
        var mouthFunnel = 0f
        var mouthPucker = 0f
        var smile = 0f

        // Apply the active viseme shaping, scaled by its (smoothed) intensity.
        val k = visemeIntensity
        if (k > 0f) {
            when (activeViseme) {
                Viseme.AA -> {
                    jawOpen = clamp01(baseJaw + 0.18f * k)
                    mouthOpen = clamp01(mouthOpen + 0.20f * k)
                }
                Viseme.OO -> {
                    mouthFunnel = 0.65f * k
                    mouthPucker = 0.55f * k
                    mouthOpen = clamp01(mouthOpen * (1f - 0.45f * k))
                }
                Viseme.EE -> {
                    smile = 0.6f * k
                    mouthOpen = clamp01(mouthOpen * (1f - 0.35f * k))
                }
                Viseme.MBP -> {
                    jawOpen = clamp01(jawOpen * (1f - 0.85f * k))
                    mouthOpen = clamp01(mouthOpen * (1f - 0.85f * k))
                }
                Viseme.FV -> {
                    mouthPucker = 0.3f * k
                    mouthOpen = clamp01(mouthOpen * (1f - 0.5f * k))
                }
                Viseme.NEUTRAL -> Unit
            }
        }

        // mouthFunnel also tracks rounded loud vowels even without a hint.
        mouthFunnel = clamp01(max(mouthFunnel, (jaw * jaw) * 0.55f))

        mutableWeights["jawOpen"] = jawOpen
        mutableWeights["mouthOpen"] = mouthOpen
        mutableWeights["mouthFunnel"] = mouthFunnel
        mutableWeights["mouthPucker"] = mouthPucker

        // Only emit the smile keys when this controller actually produces a smile (the EE
        // viseme). Emitting `0f` every frame would make these keys *always present* in [weights],
        // which shadows NazimExpressionController's WARM-preset smile in NazimRenderer's
        // `visemeWeights[key] ?: expressionWeights[key] ?: 0f` merge (the `?:` never falls through
        // for an always-present 0f key). Omitting them when there is no smile lets the expression
        // controller's value show through.
        if (smile > 0f) {
            mutableWeights["mouthSmileLeft"] = smile
            mutableWeights["mouthSmileRight"] = smile
        } else {
            mutableWeights.remove("mouthSmileLeft")
            mutableWeights.remove("mouthSmileRight")
        }
    }

    // --- Internals ----------------------------------------------------------------------------

    private fun integrateJaw(dt: Float) {
        if (dt <= 0f) return
        val tau = if (jawTarget > jaw) tauOpen else tauClose
        val alpha = 1f - exp((-dt / tau).toDouble()).toFloat()
        jaw += (jawTarget - jaw) * alpha
        jaw = clamp01(jaw)
    }

    private fun integrateViseme(dt: Float) {
        if (dt <= 0f) return
        // The intensity target itself bleeds toward 0 so a stale hint relaxes the mouth.
        visemeIntensityTarget = max(0f, visemeIntensityTarget - visemeDecay * dt)
        val alpha = 1f - exp((-dt / tauShape).toDouble()).toFloat()
        visemeIntensity += (visemeIntensityTarget - visemeIntensity) * alpha
        visemeIntensity = clamp01(visemeIntensity)
    }

    private fun clamp01(v: Float): Float = max(0f, min(1f, v))
}
