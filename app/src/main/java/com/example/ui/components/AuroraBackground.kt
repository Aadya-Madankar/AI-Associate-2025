package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.example.core.CompanionState
import com.example.models.Persona
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Full-bleed "Living Presence" backdrop.
 *
 * Calm, premium, minimal — Linear / Apple restraint. The old multi-colour aurora is gone:
 * the canvas is now a deep near-black holding ONE quiet ambient presence glow that breathes
 * slowly, like a real person sitting with you. There is no rainbow, no orbiting blobs, no
 * stacked gradients fighting for attention — just a single soft electric-blue halo
 * ([XenoColors.AuroraHalo]) that dissolves into the obsidian, plus a top vignette and a
 * bottom scrim so XENO and the foreground chrome always read clearly. XENO is the hero;
 * this recedes.
 *
 * Motion is the one ambient breath ([Motion.Ambient], ~5.2s) plus a barely-perceptible
 * vertical drift ([Motion.Drift], 30s). The glow's radius and brightness lift gently with
 * the session [state] and live [amplitude] — the presence "leans in" when it speaks or
 * listens — but never enough to distract.
 *
 * The persona only *tints* the single glow: [Persona.primaryColors] is resolved (off-frame)
 * toward [XenoColors.AccentSolid] so even a vivid persona palette stays inside the one calm
 * blue accent rather than reintroducing a second hue.
 *
 * Cheap to render: one [rememberInfiniteTransition] feeding two looped phases, one [Canvas],
 * all per-frame work is plain math, and every derived input is memoized.
 */
@Composable
fun AuroraBackground(
    state: CompanionState,
    amplitude: Float,
    persona: Persona,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "presence-bg")

    // The one slow breath — a single Motion.Ambient cycle, 0..2π, sine-eased.
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = TAU,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Ambient, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Restart
        ),
        label = "breath"
    )

    // Barely-perceptible vertical drift so the glow never sits perfectly still — Motion.Drift.
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = TAU,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Drift, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Restart
        ),
        label = "drift"
    )

    val amp = amplitude.coerceIn(0f, 1f)

    // State-driven base presence: idle is quiet, listening / speaking lean in. Memoized.
    val stateEnergy = remember(state) {
        when (state) {
            CompanionState.IDLE -> 0.34f
            CompanionState.CONNECTING -> 0.46f
            CompanionState.LISTENING -> 0.62f
            CompanionState.THINKING -> 0.54f
            CompanionState.SPEAKING -> 0.78f
            CompanionState.ERROR -> 0.40f
        }
    }

    // Resolve the persona palette into the SINGLE glow hue, biased hard toward the one accent
    // so the background stays a calm blue regardless of persona. Computed once per persona.
    val glowHue = remember(persona.id) { glowHueFor(persona) }

    // Plain val (not remembered) so the Canvas lambda re-reads it each recomposition and the
    // glow follows live amplitude + state. Gentle lift, capped so it never blooms harshly.
    val intensity = (stateEnergy + amp * 0.34f).coerceIn(0f, 1f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val minDim = if (w < h) w else h
        val centerX = w * 0.5f
        // Sit the glow behind XENO's head/upper body; drift it a hair vertically.
        val centerY = h * 0.40f + sin(drift) * h * 0.012f

        // Deepest obsidian canvas.
        drawRect(color = XenoColors.BgBase, size = Size(w, h))

        // One faint raised wash so the base isn't dead flat — top a touch lifted, settling
        // into pure near-black below. No second accent, just structure.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(XenoColors.BgRaised, XenoColors.BgBase),
                startY = 0f,
                endY = h
            ),
            size = Size(w, h)
        )

        // The single ambient presence glow. Radius breathes slowly (≈ ±6%) and opens a little
        // with amplitude; brightness tracks intensity. It always dissolves fully into the
        // canvas via the transparent last stop of AuroraHalo — no hard edge, no halo ring.
        val breathScale = 1f + 0.06f * sin(breath)
        val radius = minDim * (0.74f + 0.12f * amp) * breathScale
        val glow = (0.55f + 0.45f * intensity).coerceIn(0f, 1f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    glowHue.copy(alpha = 0.22f * glow),
                    glowHue.copy(alpha = 0.08f * glow),
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = radius
            ),
            radius = radius,
            center = Offset(centerX, centerY)
        )

        // Subtle top vignette — settles the status-bar region into the obsidian.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(XenoColors.BgBase.copy(alpha = 0.55f), Color.Transparent),
                startY = 0f,
                endY = h * 0.26f
            ),
            size = Size(w, h * 0.26f)
        )

        // Bottom scrim so the foreground controls always stay legible over the glow.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, XenoColors.BgBase.copy(alpha = 0.90f)),
                startY = h * 0.52f,
                endY = h
            ),
            topLeft = Offset(0f, h * 0.52f),
            size = Size(w, h * 0.48f)
        )
    }
}

// ── Constants & helpers ────────────────────────────────────────────────────────────────
// Pulled out of composition so nothing allocates per frame.

private const val TAU = (2.0 * PI).toFloat()

/**
 * Resolves a persona's [Persona.primaryColors] into the ONE glow hue. The persona may tint
 * the single presence glow, but only a little — the result is biased strongly toward
 * [XenoColors.AccentSolid] so even a vivid persona palette resolves into the calm soft
 * electric blue instead of reintroducing a second hue. Called once per persona, off-frame.
 */
private fun glowHueFor(persona: Persona): Color {
    val source = persona.primaryColors.firstOrNull() ?: return XenoColors.AccentSolid
    // Keep just a hint of persona identity; the rest snaps back to the single accent.
    val keepPersona = 0.30f
    return lerp(XenoColors.AccentSolid, source.copy(alpha = 1f), keepPersona)
}
