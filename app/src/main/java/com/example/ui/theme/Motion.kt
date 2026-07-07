package com.example.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * XENO: Living Presence — motion tokens (easings, durations in ms, and springs).
 *
 * Motion is subtle and organic, never busy — the feeling of a real person quietly present.
 * Three jobs:
 *  - Ambient life: ONE slow breathing presence glow that never stops but never distracts.
 *  - Reactivity: an amplitude-driven glow on the mic and XENO that follows the voice.
 *  - Feedback: a gentle spring on press, and a soft fade/slide for surfaces appearing.
 *
 * Tuned slower and calmer than the old direction: the breath is longer, drift is barely
 * perceptible, and feedback springs settle without showy overshoot.
 *
 * Every original member is preserved by name ([EaseStandard], [EaseEnter], [EaseExit],
 * [EaseAmbient], [Quick], [Base], [Slow], [Ambient], [Reactive], [Drift], [SpringPress],
 * [SpringSoft], [PressScale], [RestScale]); values are retuned for the new calm.
 */
object Motion {

  // ── Easings ────────────────────────────────────────────────────────────────

  /** Standard ease for most state changes — gentle out, settled in. */
  val EaseStandard: Easing = CubicBezierEasing(0.25f, 0.1f, 0.0f, 1f)

  /** Decelerate-heavy easing for entering surfaces (soft slide/fade in). */
  val EaseEnter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

  /** Accelerate-heavy easing for exiting surfaces (quiet slide/fade out). */
  val EaseExit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

  /** Symmetric sine-like easing for ambient looping motion — the breath of presence. */
  val EaseAmbient: Easing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

  // ── Durations (milliseconds) ─────────────────────────────────────────────────

  /** Micro-feedback: taps, dot pulses, tint swaps. */
  const val Quick: Int = 200

  /** Default transition: most enter/exit, color and size changes. */
  const val Base: Int = 320

  /** Deliberate transition: sheets, large surface moves. */
  const val Slow: Int = 540

  /** One ambient breath cycle of the presence glow — slow and human. */
  const val Ambient: Int = 5200

  /** Amplitude follow window — how fast the reactive glow chases the voice level. */
  const val Reactive: Int = 130

  /** Long-period background drift of the single presence glow — barely perceptible. */
  const val Drift: Int = 30000

  // ── Springs ──────────────────────────────────────────────────────────────────

  /** Gentle press spring — a soft, almost-no-overshoot settle. For tactile feedback. */
  val SpringPress: SpringSpec<Float> =
    spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)

  /** Soft, no-overshoot spring for layout/size settling. */
  val SpringSoft: SpringSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)

  /** Standard press-scale targets for interactive surfaces (use with [SpringPress]). */
  const val PressScale: Float = 0.97f
  const val RestScale: Float = 1.0f
}
