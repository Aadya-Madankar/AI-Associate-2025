package com.example.avatar

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure-Kotlin, deterministic (except seeded blink timing) controller that turns a 0..1 audio
 * amplitude into facial morph-target weights for the avatar: jaw, mouth shaping, eye blinks and
 * a subtle breathing motion, plus a layered head-sway helper.
 *
 * No Android imports — fully unit-testable. Blink intervals use a seeded [Random] (seed 1234) so
 * the blink schedule is reproducible in tests.
 *
 * Usage per frame:
 *   controller.setAmplitude(currentAmplitude)
 *   controller.update(deltaSeconds)
 *   val w = controller.weights   // feed into morph targets
 */
class LipSyncController {

    // --- Tunables -----------------------------------------------------------------------------
    private val jawGain = 0.85f          // max jaw open weight
    private val jawShape = 0.6f          // perceptual curve on amplitude
    private val tauOpen = 0.05f          // fast attack (seconds) toward a higher target
    private val tauClose = 0.12f         // slow release (seconds) toward a lower target

    private val blinkMinInterval = 2.5f  // seconds
    private val blinkMaxInterval = 6.0f  // seconds
    private val blinkDuration = 0.12f    // full blink envelope ~120ms

    private val breathPeriod = 4.0f      // seconds per breathing cycle
    private val breathDepth = 0.06f      // subtle mouth/jaw breathing contribution

    // --- State --------------------------------------------------------------------------------
    private var jawTarget = 0f
    private var jaw = 0f

    private var time = 0f

    private val blinkRandom = Random(1234L)
    private var nextBlinkAt = 0f
    private var blinkElapsed = Float.MAX_VALUE   // >= blinkDuration means "not blinking"
    private var blinkValue = 0f

    private var breathPhase = 0f

    private val mutableWeights: MutableMap<String, Float> = linkedMapOf(
        "jawOpen" to 0f,
        "mouthOpen" to 0f,
        "mouthFunnel" to 0f,
        "eyeBlinkLeft" to 0f,
        "eyeBlinkRight" to 0f
    )

    init {
        nextBlinkAt = scheduleNextBlink(0f)
    }

    /** Read-only view of the current morph-target weights (all clamped 0..1). */
    val weights: Map<String, Float> get() = mutableWeights

    /**
     * Set the shaped jaw-open target from a raw amplitude in 0..1.
     * Shaped target = (a^0.6) * 0.85.
     */
    fun setAmplitude(a: Float) {
        val clamped = a.coerceIn(0f, 1f)
        jawTarget = clamped.toDouble().pow(jawShape.toDouble()).toFloat() * jawGain
    }

    /**
     * Advance the simulation by [dtSeconds]. Integrates the jaw with an asymmetric time constant
     * (fast open, slow close), advances the random blink schedule and breathing, then recomputes
     * the exposed [weights].
     */
    fun update(dtSeconds: Float) {
        val dt = if (dtSeconds.isNaN() || dtSeconds <= 0f) 0f else min(dtSeconds, 0.1f)
        time += dt
        breathPhase += dt

        integrateJaw(dt)
        advanceBlink(dt)

        // Breathing: a gentle sine that nudges the resting mouth/jaw, independent of speech.
        val breath = (sin((breathPhase / breathPeriod) * 2.0 * PI).toFloat() * 0.5f + 0.5f) * breathDepth

        val jawOpen = clamp01(jaw + breath * 0.4f)
        // mouthOpen tracks the jaw but a touch wider; funnel grows on louder, rounder vowels.
        val mouthOpen = clamp01(jaw * 0.9f + breath * 0.5f)
        val mouthFunnel = clamp01((jaw * jaw) * 0.7f)

        mutableWeights["jawOpen"] = jawOpen
        mutableWeights["mouthOpen"] = mouthOpen
        mutableWeights["mouthFunnel"] = mouthFunnel
        mutableWeights["eyeBlinkLeft"] = blinkValue
        mutableWeights["eyeBlinkRight"] = blinkValue
    }

    /**
     * Small layered head motion for the given absolute [timeSeconds].
     * Returns (yawDegrees, pitchDegrees, verticalBob).
     */
    fun headSway(timeSeconds: Float): Triple<Float, Float, Float> {
        val t = timeSeconds.toDouble()
        val yaw = (sin(t * 0.37) * 2.2 + sin(t * 0.11) * 1.1).toFloat()
        val pitch = (sin(t * 0.29 + 1.3) * 1.4 + sin(t * 0.07) * 0.6).toFloat()
        val bob = (sin(t * 0.5 + 0.6) * 0.6).toFloat()
        return Triple(yaw, pitch, bob)
    }

    // --- Internals ----------------------------------------------------------------------------

    private fun integrateJaw(dt: Float) {
        if (dt <= 0f) return
        // Choose attack vs release time constant based on direction of travel.
        val tau = if (jawTarget > jaw) tauOpen else tauClose
        // Exponential smoothing toward target: jaw += (target - jaw) * (1 - e^(-dt/tau)).
        val alpha = 1f - exp((-dt / tau).toDouble()).toFloat()
        jaw += (jawTarget - jaw) * alpha
        jaw = clamp01(jaw)
    }

    private fun advanceBlink(dt: Float) {
        // If a blink is in progress, advance its envelope.
        if (blinkElapsed < blinkDuration) {
            blinkElapsed += dt
            blinkValue = blinkEnvelope(blinkElapsed / blinkDuration)
            if (blinkElapsed >= blinkDuration) {
                blinkValue = 0f
                nextBlinkAt = scheduleNextBlink(time)
            }
            return
        }
        // Otherwise wait for the scheduled blink time.
        if (time >= nextBlinkAt) {
            blinkElapsed = 0f
            blinkValue = blinkEnvelope(0f)
        }
    }

    /** A smooth open->closed->open envelope across normalized progress p in 0..1. */
    private fun blinkEnvelope(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        // sin(pi*x) gives 0 -> 1 -> 0, peaking fully closed at the midpoint.
        return clamp01(sin(x * PI).toFloat())
    }

    private fun scheduleNextBlink(now: Float): Float {
        val span = blinkMaxInterval - blinkMinInterval
        val interval = blinkMinInterval + blinkRandom.nextFloat() * span
        return now + interval
    }

    private fun clamp01(v: Float): Float = max(0f, min(1f, v))
}
