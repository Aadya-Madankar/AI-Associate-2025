package com.example.character

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Maps the high-level [NazimState] onto an [ExpressionPreset] plus the per-frame "liveness"
 * layer — idle breathing, periodic blinks, micro head-sway and brow/eye/cheek blendshape
 * weights — that make Nazim feel alive (SCOPE.md §2).
 *
 * Pure-Kotlin and deterministic apart from the seeded blink schedule, so it is fully
 * unit-testable with no Android dependencies. It is the expression counterpart to
 * [VisemeController]: visemes drive the mouth from audio, this drives everything *else* from
 * conversational state.
 *
 * Per-frame usage:
 * ```
 * expr.setState(NazimState.THINKING)
 * expr.update(deltaSeconds)
 * faceMorph.apply(engine, expr.weights)     // brows/eyes/cheeks
 * val sway = expr.headSway()                // yaw/pitch/bob in degrees + bob units
 * ```
 *
 * Weight keys use ARKit/MetaHuman blendshape names: `eyeBlinkLeft`, `eyeBlinkRight`,
 * `browInnerUp`, `browOuterUpLeft`, `browOuterUpRight`, `browDownLeft`, `browDownRight`,
 * `eyeWideLeft`, `eyeWideRight`, `cheekSquintLeft`, `cheekSquintRight`, `mouthSmileLeft`,
 * `mouthSmileRight`, `mouthFrownLeft`, `mouthFrownRight`.
 */
class NazimExpressionController(seed: Long = 4242L) {

    /**
     * A named facial pose target Nazim settles into for a given [NazimState]. The
     * controller smoothly cross-fades between presets so state changes never snap.
     */
    enum class ExpressionPreset {
        /** Relaxed neutral. */
        CALM,

        /** Attentive, brows slightly up, eyes a touch wider — "I'm listening". */
        ATTENTIVE,

        /** Concentrated, inner brow up, slight squint — "I'm thinking". */
        FOCUSED,

        /** Warm, light smile, soft cheeks — "I'm speaking to you". */
        WARM,

        /** Determined, brows down, steady — "I'm doing a task". */
        DETERMINED,

        /** Concerned, inner brow up + frown — something went wrong. */
        CONCERNED
    }

    /**
     * Coarse idle-animation/clip selection hint for the renderer. When a GLB ships named
     * animation clips, `NazimRenderer` can pick one; otherwise the procedural breathing/sway
     * stands in. Independent of the facial [ExpressionPreset].
     */
    enum class IdleAnimation {
        /** Calm breathing loop. */
        BREATHE,

        /** Slightly more alert idle (faster micro-motion). */
        ALERT,

        /** Minimal motion, head tilted in thought. */
        PONDER,

        /** Engaged, gesture-friendly idle while speaking. */
        ENGAGED,

        /** Brisk, purposeful idle while executing a task. */
        BUSY
    }

    // --- Tunables -----------------------------------------------------------------------------
    private val presetTau = 0.18f        // cross-fade time constant between presets (seconds)
    private val blinkMinInterval = 2.5f
    private val blinkMaxInterval = 6.0f
    private val blinkDuration = 0.12f
    private val breathPeriod = 4.0f

    // --- State --------------------------------------------------------------------------------
    private var state: NazimState = NazimState.IDLE
    private var preset: ExpressionPreset = ExpressionPreset.CALM
    private var idle: IdleAnimation = IdleAnimation.BREATHE

    /** Current (smoothed) pose, keyed by blendshape name, cross-fading toward [targetPose]. */
    private val pose: MutableMap<String, Float> = HashMap()
    private val targetPose: MutableMap<String, Float> = HashMap()

    private var time = 0f
    private var breathPhase = 0f

    private val blinkRandom = Random(seed)
    private var nextBlinkAt = 0f
    private var blinkElapsed = Float.MAX_VALUE
    private var blinkValue = 0f

    private val mutableWeights: MutableMap<String, Float> = linkedMapOf(
        "eyeBlinkLeft" to 0f,
        "eyeBlinkRight" to 0f,
        "browInnerUp" to 0f,
        "browOuterUpLeft" to 0f,
        "browOuterUpRight" to 0f,
        "browDownLeft" to 0f,
        "browDownRight" to 0f,
        "eyeWideLeft" to 0f,
        "eyeWideRight" to 0f,
        "cheekSquintLeft" to 0f,
        "cheekSquintRight" to 0f,
        "mouthSmileLeft" to 0f,
        "mouthSmileRight" to 0f,
        "mouthFrownLeft" to 0f,
        "mouthFrownRight" to 0f
    )

    init {
        applyTargetForPreset(preset)
        // Start fully settled on the initial preset.
        targetPose.forEach { (k, v) -> pose[k] = v }
        nextBlinkAt = scheduleNextBlink(0f)
    }

    /** Read-only view of the current expression blendshape weights (all clamped 0..1). */
    val weights: Map<String, Float> get() = mutableWeights

    /** The current resolved [ExpressionPreset] for the active state (for debug/UI). */
    val currentPreset: ExpressionPreset get() = preset

    /** The current resolved [IdleAnimation] for the active state (for the renderer). */
    val currentIdleAnimation: IdleAnimation get() = idle

    /**
     * Set the conversational [NazimState]. Re-derives the [ExpressionPreset] and
     * [IdleAnimation]; the facial pose then cross-fades toward the new preset over subsequent
     * [update] calls. Idempotent for the same state.
     */
    fun setState(newState: NazimState) {
        if (newState == state) return
        state = newState
        preset = presetFor(newState)
        idle = idleFor(newState)
        applyTargetForPreset(preset)
    }

    /**
     * Advance the controller by [dtSeconds]: cross-fade toward the target pose, run the blink
     * schedule, advance breathing, then recompute [weights].
     */
    fun update(dtSeconds: Float) {
        val dt = if (dtSeconds.isNaN() || dtSeconds <= 0f) 0f else min(dtSeconds, 0.1f)
        time += dt
        breathPhase += dt

        crossFadePose(dt)
        advanceBlink(dt)

        // Subtle breathing micro-motion layered onto the brows so the resting face is never
        // perfectly static (a tiny, always-on "alive" nudge).
        val breathNudge = breath() * 0.03f

        for ((k, v) in pose) {
            val base = if (k == "browInnerUp") v + breathNudge else v
            mutableWeights[k] = clamp01(base)
        }
        mutableWeights["eyeBlinkLeft"] = blinkValue
        mutableWeights["eyeBlinkRight"] = blinkValue
    }

    /**
     * Layered micro head motion for a living idle, scaled by the current idle animation's
     * energy. Returns (yawDegrees, pitchDegrees, verticalBob). Mirrors
     * `LipSyncController.headSway` so the renderer can apply it the same way `AvatarView` does.
     */
    fun headSway(): Triple<Float, Float, Float> {
        val energy = when (idle) {
            IdleAnimation.PONDER -> 0.6f
            IdleAnimation.BREATHE -> 1.0f
            IdleAnimation.ALERT -> 1.2f
            IdleAnimation.ENGAGED -> 1.5f
            IdleAnimation.BUSY -> 1.7f
        }
        val t = time.toDouble()
        val yaw = ((sin(t * 0.37) * 2.2 + sin(t * 0.11) * 1.1) * energy).toFloat()
        val pitch = ((sin(t * 0.29 + 1.3) * 1.4 + sin(t * 0.07) * 0.6) * energy).toFloat()
        val bob = ((sin(t * 0.5 + 0.6) * 0.6) * energy).toFloat()
        return Triple(yaw, pitch, bob)
    }

    // --- Mapping ------------------------------------------------------------------------------

    private fun presetFor(s: NazimState): ExpressionPreset = when (s) {
        NazimState.IDLE -> ExpressionPreset.CALM
        NazimState.LISTENING -> ExpressionPreset.ATTENTIVE
        NazimState.THINKING -> ExpressionPreset.FOCUSED
        NazimState.SPEAKING -> ExpressionPreset.WARM
        NazimState.ACTING -> ExpressionPreset.DETERMINED
        NazimState.WALKING -> ExpressionPreset.DETERMINED
        NazimState.RUNNING -> ExpressionPreset.DETERMINED
        NazimState.ERROR -> ExpressionPreset.CONCERNED
    }

    private fun idleFor(s: NazimState): IdleAnimation = when (s) {
        NazimState.IDLE -> IdleAnimation.BREATHE
        NazimState.LISTENING -> IdleAnimation.ALERT
        NazimState.THINKING -> IdleAnimation.PONDER
        NazimState.SPEAKING -> IdleAnimation.ENGAGED
        NazimState.ACTING -> IdleAnimation.BUSY
        NazimState.WALKING -> IdleAnimation.BUSY
        NazimState.RUNNING -> IdleAnimation.BUSY
        NazimState.ERROR -> IdleAnimation.ALERT
    }

    /** Writes the resting blendshape pose for [p] into [targetPose] (zeroing the rest). */
    private fun applyTargetForPreset(p: ExpressionPreset) {
        // Reset all keys to 0 first so retired expressions relax out.
        for (k in mutableWeights.keys) {
            if (k == "eyeBlinkLeft" || k == "eyeBlinkRight") continue
            targetPose[k] = 0f
            if (!pose.containsKey(k)) pose[k] = 0f
        }
        when (p) {
            ExpressionPreset.CALM -> {
                // resting neutral; nothing to set
            }
            ExpressionPreset.ATTENTIVE -> {
                targetPose["browInnerUp"] = 0.22f
                targetPose["browOuterUpLeft"] = 0.18f
                targetPose["browOuterUpRight"] = 0.18f
                targetPose["eyeWideLeft"] = 0.20f
                targetPose["eyeWideRight"] = 0.20f
            }
            ExpressionPreset.FOCUSED -> {
                targetPose["browInnerUp"] = 0.30f
                targetPose["browDownLeft"] = 0.18f
                targetPose["browDownRight"] = 0.18f
                targetPose["cheekSquintLeft"] = 0.15f
                targetPose["cheekSquintRight"] = 0.15f
            }
            ExpressionPreset.WARM -> {
                targetPose["mouthSmileLeft"] = 0.32f
                targetPose["mouthSmileRight"] = 0.32f
                targetPose["cheekSquintLeft"] = 0.22f
                targetPose["cheekSquintRight"] = 0.22f
                targetPose["browInnerUp"] = 0.10f
            }
            ExpressionPreset.DETERMINED -> {
                targetPose["browDownLeft"] = 0.30f
                targetPose["browDownRight"] = 0.30f
                targetPose["eyeWideLeft"] = 0.10f
                targetPose["eyeWideRight"] = 0.10f
            }
            ExpressionPreset.CONCERNED -> {
                targetPose["browInnerUp"] = 0.40f
                targetPose["mouthFrownLeft"] = 0.28f
                targetPose["mouthFrownRight"] = 0.28f
            }
        }
    }

    // --- Internals ----------------------------------------------------------------------------

    private fun crossFadePose(dt: Float) {
        if (dt <= 0f) return
        val alpha = 1f - exp((-dt / presetTau).toDouble()).toFloat()
        for ((k, target) in targetPose) {
            val cur = pose[k] ?: 0f
            pose[k] = cur + (target - cur) * alpha
        }
    }

    private fun advanceBlink(dt: Float) {
        if (blinkElapsed < blinkDuration) {
            blinkElapsed += dt
            blinkValue = blinkEnvelope(blinkElapsed / blinkDuration)
            if (blinkElapsed >= blinkDuration) {
                blinkValue = 0f
                nextBlinkAt = scheduleNextBlink(time)
            }
            return
        }
        if (time >= nextBlinkAt) {
            blinkElapsed = 0f
            blinkValue = blinkEnvelope(0f)
        }
    }

    private fun blinkEnvelope(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        return clamp01(sin(x * PI).toFloat())
    }

    private fun scheduleNextBlink(now: Float): Float {
        val span = blinkMaxInterval - blinkMinInterval
        val interval = blinkMinInterval + blinkRandom.nextFloat() * span
        return now + interval
    }

    /** Breathing oscillator in 0..1 (one cycle per [breathPeriod] seconds). */
    private fun breath(): Float =
        (sin((breathPhase / breathPeriod) * 2.0 * PI).toFloat() * 0.5f + 0.5f)

    private fun clamp01(v: Float): Float = max(0f, min(1f, v))
}
