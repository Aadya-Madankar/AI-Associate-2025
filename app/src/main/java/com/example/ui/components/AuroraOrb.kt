package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.example.core.CompanionState
import com.example.models.Persona
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Obsidian Aurora — the ambient presence halo.
 *
 * This is the *backdrop* aura, not the character: a soft, slowly breathing,
 * amplitude-reactive glow that lives behind Nazim (see [com.example.character.NazimFallbackPortrait])
 * and fills the scene whenever the 3D avatar is unavailable. It is deliberately
 * quiet — a layered radial halo from the restrained violet / cyan / magenta triad
 * that always dissolves to transparent so it melts into the obsidian base rather
 * than cutting an edge. The active [persona] tints the core hue and [state] +
 * [amplitude] drive a gentle reactive bloom. No face, no eyes — nothing competes
 * with Nazim.
 *
 * Drawn entirely with [Canvas]; no asset dependencies.
 */
@Composable
fun AuroraOrb(
    state: CompanionState,
    amplitude: Float,
    persona: Persona,
    modifier: Modifier = Modifier
) {
    // ── Ambient channels — one infinite transition drives all looping motion. ──
    val transition = rememberInfiniteTransition(label = "aurora")

    // Slow breath: radius + alpha drift. EaseAmbient gives an organic in/out swell.
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Ambient, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    // Long-period drift: rotates the aurora field so the glow never feels static.
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Drift, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift"
    )

    // ── Reactivity — energy follows voice with a short Reactive window. ──
    val amp = amplitude.coerceIn(0f, 1f)
    val targetEnergy = when (state) {
        CompanionState.IDLE -> 0.22f
        CompanionState.CONNECTING -> 0.40f
        CompanionState.LISTENING -> 0.46f + amp * 0.34f
        CompanionState.THINKING -> 0.48f
        CompanionState.SPEAKING -> 0.54f + amp * 0.40f
        CompanionState.ERROR -> 0.30f
    }.coerceIn(0f, 1f)
    val energy by animateFloatAsState(
        targetValue = targetEnergy,
        animationSpec = tween(Motion.Reactive),
        label = "energy"
    )
    val glow by animateFloatAsState(
        targetValue = amp,
        animationSpec = tween(Motion.Reactive),
        label = "glow"
    )

    // ── Hue — persona accent tints the core; the triad colours the field/rim. ──
    // Derived once per persona/state change; no allocation in the draw loop.
    val accent = remember(persona, state) { stateAccent(state, persona) }
    val haloMid = XenoColors.AccentViolet
    val haloEdge = XenoColors.AccentMagenta

    // Reusable field path — mutated in place each frame to avoid per-frame allocation.
    val fieldPath = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val cx = w * 0.5f
        val cy = h * 0.5f
        val baseR = min(w, h) * 0.34f

        // Breathing swell, gently amplified by live energy.
        val swell = 0.94f + 0.06f * breath + 0.10f * glow
        val haloR = baseR * swell

        // 1. Wide outer aurora wash — the calm ambient field. Always transparent-out.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.16f + 0.18f * energy),
                    haloMid.copy(alpha = 0.10f + 0.10f * energy),
                    haloEdge.copy(alpha = 0.05f + 0.06f * energy),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = haloR * 2.6f
            ),
            radius = haloR * 2.6f,
            center = Offset(cx, cy)
        )

        // 2. Soft morphing aurora core — a gently wobbling lobe, off-centred by the
        //    drift so the light feels alive without ever being busy.
        val drwhere = Offset(
            cx + cos(drift) * baseR * 0.06f,
            cy + sin(drift * 1.3f) * baseR * 0.06f
        )
        buildAuroraField(
            path = fieldPath,
            cx = drwhere.x,
            cy = drwhere.y,
            radius = haloR,
            phase = drift,
            wobble = 0.05f + 0.04f * energy
        )
        drawPath(
            path = fieldPath,
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.34f + 0.22f * energy),
                    haloMid.copy(alpha = 0.20f + 0.12f * energy),
                    haloEdge.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = Offset(
                    drwhere.x - haloR * 0.12f,
                    drwhere.y - haloR * 0.14f
                ),
                radius = haloR * 1.55f
            )
        )

        // 3. Luminous off-white heart — barely there at rest, blooms with the voice.
        val coreR = haloR * (0.36f + 0.10f * glow)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    XenoColors.TextPrimary.copy(alpha = 0.10f + 0.22f * glow),
                    accent.copy(alpha = 0.16f + 0.16f * energy),
                    Color.Transparent
                ),
                center = Offset(cx - haloR * 0.08f, cy - haloR * 0.10f),
                radius = coreR * 1.7f
            ),
            radius = coreR,
            center = Offset(cx - haloR * 0.06f, cy - haloR * 0.08f)
        )

        // 4. Whisper-thin reactive triad rim — the only crisp element, kept faint.
        val rimAlpha = (0.10f + 0.26f * energy).coerceIn(0f, 0.42f)
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(
                    XenoColors.AccentCyan.copy(alpha = rimAlpha),
                    XenoColors.AccentViolet.copy(alpha = rimAlpha),
                    XenoColors.AccentMagenta.copy(alpha = rimAlpha),
                    XenoColors.AccentCyan.copy(alpha = rimAlpha)
                ),
                center = Offset(cx, cy)
            ),
            radius = haloR * 1.04f,
            center = Offset(cx, cy),
            style = Stroke(width = haloR * 0.018f)
        )
    }
}

/**
 * Resolves the dominant aurora hue for the current [state], biased toward the
 * active [persona] accent so the halo subtly reflects who is present and what
 * the companion is doing. Falls back to the committed triad tokens.
 */
private fun stateAccent(state: CompanionState, persona: Persona): Color {
    val personaHue = persona.primaryColors.firstOrNull() ?: XenoColors.PersonaAccent
    val stateHue = when (state) {
        CompanionState.LISTENING -> XenoColors.AccentCyan
        CompanionState.THINKING -> XenoColors.AccentViolet
        CompanionState.SPEAKING -> XenoColors.AccentMagenta
        CompanionState.ERROR -> XenoColors.Error
        else -> XenoColors.PersonaAccent
    }
    // Blend the persona's identity with the conversational state hue.
    return lerp(personaHue, stateHue, 0.5f)
}

/**
 * Rebuilds a closed, gently morphing aurora lobe into [path] (cleared first) from
 * layered low-frequency sinusoids. Reusing the path avoids per-frame allocation.
 */
private fun buildAuroraField(
    path: Path,
    cx: Float,
    cy: Float,
    radius: Float,
    phase: Float,
    wobble: Float
) {
    path.reset()
    val steps = 48
    val twoPi = 2f * PI.toFloat()
    for (i in 0..steps) {
        val a = (i.toFloat() / steps) * twoPi
        val r = radius * (1f +
            wobble * sin(2f * a + phase) +
            wobble * 0.5f * cos(3f * a - phase * 0.7f))
        val x = cx + cos(a) * r
        val y = cy + sin(a) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}
