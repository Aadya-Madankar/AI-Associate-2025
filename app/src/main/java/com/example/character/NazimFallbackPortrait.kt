package com.example.character

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.example.core.CompanionState
import com.example.models.Persona
import com.example.ui.components.AuroraOrb
import com.example.ui.components.NazimDarkPalette
import com.example.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Asset-free, Compose-only fallback rendering of **XENO** for when `assets/nazim.glb` is not
 * bundled, or the SceneView/Filament path throws (SCOPE.md §2 — "the app is never blank").
 *
 * Restyled to **XENO: Living Presence** (DESIGN.md): a calm, premium, minimal portrait of a real
 * person quietly present with you. XENO is the hero; everything recedes. The face is a softly
 * shaded head/shoulders bust set in ONE quiet [AuroraOrb] presence glow that breathes slowly.
 * There is no rainbow — a SINGLE soft electric-blue accent ([NazimDarkPalette.AccentSolid]) lights the
 * face and drives the only crisp, voice-reactive rim, sparingly. Depth is built from light
 * (radial form + a top-lit [NazimDarkPalette.GlassHighlight] sheen and [NazimDarkPalette.GlassStroke]-weight
 * contours), never from heavy shadow or stacked gradients.
 *
 * Everything is drawn with [Canvas], so this has no asset dependencies and cannot fail to load.
 * Skin tones are intentionally local constants — the token palette has no flesh hues — but every
 * light, sheen and accent is driven by the committed [NazimDarkPalette] / [Motion] tokens.
 */
@Composable
fun NazimFallbackPortrait(
    state: NazimState,
    amplitude: Float,
    persona: Persona,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // ONE quiet ambient presence glow behind XENO (the orb, demoted to a calm backdrop).
        AuroraOrb(
            state = state.toCompanionState(),
            amplitude = amplitude * 0.6f,
            persona = persona,
            modifier = Modifier.fillMaxSize()
        )
        NazimBust(
            state = state,
            amplitude = amplitude,
            persona = persona,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * The XENO bust, drawn on a [Canvas]. Skin is shaded with soft radial form and a single top-lit
 * sheen so it reads as a portrait, not a cartoon; one restrained accent fill light grazes the
 * lower cheek. Eyes blink, brows track the conversational [state], and the mouth opens with the
 * live [amplitude].
 *
 * Motion is budgeted to the calm spec: a single [rememberInfiniteTransition] drives all looping
 * life (a slow breath, a long sway, an occasional blink) on the [Motion] easings, and the only
 * voice-reactive element is the accent rim/glow following [amplitude] over the short
 * [Motion.Reactive] window — one ambient loop plus one reactive element, nothing busy.
 */
@Composable
private fun NazimBust(
    state: NazimState,
    amplitude: Float,
    persona: Persona,
    modifier: Modifier
) {
    val transition = rememberInfiniteTransition(label = "nazim-bust")

    // One slow breath drives the chest/head rise (radians) — the breath of a present figure.
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Ambient, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Restart
        ),
        label = "breath"
    )

    // Blink envelope — long quiet, brief close near the end of the cycle.
    val blinkPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4600, easing = Motion.EaseStandard),
            repeatMode = RepeatMode.Restart
        ),
        label = "blink"
    )

    // Long-period sway so the bust never feels frozen (radians).
    val sway by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Drift, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Restart
        ),
        label = "sway"
    )

    // The single reactive channel — amplitude follows the voice over a short Reactive window so
    // the accent rim + mouth chase speech without jitter. Hoisted out of the draw loop.
    val amp = amplitude.coerceIn(0f, 1f)
    val glow by animateFloatAsState(
        targetValue = amp,
        animationSpec = tween(Motion.Reactive),
        label = "glow"
    )

    // The single accent — soft electric blue for live/active states, kept within the one accent
    // family even as it tints toward the persona. ERROR is the lone muted exception. Derived
    // once per persona/state change; never recomputed in the draw loop.
    val keyLight = remember(persona, state) { stateAccent(state, persona) }
    val mood = remember(state) { state.mood() }

    // Reusable scratch path — mutated in place each frame, never re-allocated in composition.
    val path = remember { Path() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val unit = min(w, h)
        val cx = w * 0.5f + cos(sway) * unit * 0.008f
        val headCy = h * 0.46f + (sin(breath) * 0.5f + 0.5f) * unit * 0.006f
        val headR = unit * 0.20f

        drawShoulders(path, cx, headCy, headR)
        drawNeck(cx, headCy, headR)
        drawHead(cx, headCy, headR, keyLight)
        drawHair(path, cx, headCy, headR)

        // Eyes.
        val eyeY = headCy - headR * 0.10f
        val eyeDx = headR * 0.42f
        val eyeR = headR * 0.13f
        val blinkClose = blinkEnvelope(blinkPhase)
        val eyeOpen = (1f - blinkClose) * mood.eyeOpen
        drawEye(path, Offset(cx - eyeDx, eyeY), eyeR, eyeOpen, keyLight)
        drawEye(path, Offset(cx + eyeDx, eyeY), eyeR, eyeOpen, keyLight)

        // Brows — vertical offset + tilt from mood.
        val browY = eyeY - headR * (0.30f + mood.browRaise * 0.12f)
        drawBrow(path, Offset(cx - eyeDx, browY), headR * 0.30f, mood.browTilt)
        drawBrow(path, Offset(cx + eyeDx, browY), headR * 0.30f, -mood.browTilt)

        // Nose.
        drawNose(path, cx, headCy, headR)

        // Mouth — opens with reactive amplitude, curvature from mood.
        val mouthY = headCy + headR * 0.52f
        val mouthOpen = (glow * 0.55f + mood.mouthOpenBias).coerceIn(0f, 0.9f)
        drawMouth(path, Offset(cx, mouthY), headR * 0.42f, mouthOpen, mood.mouthCurve, keyLight)

        // The single crisp accent: a whisper-thin, voice-reactive rim of light along one cheek.
        // Stays well below full alpha so it reads as light grazing a person, never neon.
        drawRimLight(path, cx, headCy, headR, keyLight, 0.16f + glow * 0.40f)
    }
}

// --- Drawing helpers ----------------------------------------------------------------------------

private fun DrawScope.drawShoulders(path: Path, cx: Float, headCy: Float, headR: Float) {
    val top = headCy + headR * 1.5f
    path.reset()
    path.moveTo(cx - headR * 2.6f, size.height)
    path.cubicTo(
        cx - headR * 2.2f, top,
        cx - headR * 0.9f, top - headR * 0.2f,
        cx, top - headR * 0.25f
    )
    path.cubicTo(
        cx + headR * 0.9f, top - headR * 0.2f,
        cx + headR * 2.2f, top,
        cx + headR * 2.6f, size.height
    )
    path.close()
    // Frosted near-black garment over the surface stack — it recedes so the face is the hero.
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            colors = listOf(NazimDarkPalette.Surface1, NazimDarkPalette.BgRaised, NazimDarkPalette.BgBase),
            startY = top,
            endY = size.height
        )
    )
    // A single 1px-weight top-lit sheen along the shoulder line — light, not shadow.
    path.reset()
    path.moveTo(cx - headR * 1.9f, top - headR * 0.04f)
    path.quadraticBezierTo(cx, top - headR * 0.34f, cx + headR * 1.9f, top - headR * 0.04f)
    drawPath(path, color = NazimDarkPalette.GlassStroke, style = Stroke(width = headR * 0.02f))
}

private fun DrawScope.drawNeck(cx: Float, headCy: Float, headR: Float) {
    val neckTop = headCy + headR * 0.7f
    val neckW = headR * 0.55f
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(SkinShadow, SkinMid)
        ),
        topLeft = Offset(cx - neckW, neckTop),
        size = Size(neckW * 2f, headR * 1.1f)
    )
}

private fun DrawScope.drawHead(cx: Float, cy: Float, r: Float, keyLight: Color) {
    val topLeft = Offset(cx - r * 0.92f, cy - r * 1.18f)
    val ovalSize = Size(r * 1.84f, r * 2.36f)

    // 1. Base skin volume with a soft top-left key light — pure form, no edge.
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(SkinHigh, SkinMid, SkinShadow),
            center = Offset(cx - r * 0.30f, cy - r * 0.35f),
            radius = r * 1.70f
        ),
        topLeft = topLeft,
        size = ovalSize
    )

    // 2. The single accent fill light grazing the lower-right cheek. Restrained and dissolving
    //    to transparent so it reads as one quiet electric-blue light on the face, not an overlay.
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(keyLight.copy(alpha = 0.16f), Color.Transparent),
            center = Offset(cx + r * 0.50f, cy + r * 0.50f),
            radius = r * 1.25f
        ),
        topLeft = topLeft,
        size = ovalSize
    )

    // 3. Faint top-lit sheen on the brow/forehead — the soft glass highlight of the language.
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(NazimDarkPalette.GlassHighlight, Color.Transparent),
            center = Offset(cx - r * 0.22f, cy - r * 0.55f),
            radius = r * 0.85f
        ),
        topLeft = topLeft,
        size = ovalSize
    )
}

private fun DrawScope.drawHair(path: Path, cx: Float, cy: Float, r: Float) {
    path.reset()
    path.moveTo(cx - r * 0.95f, cy - r * 0.1f)
    path.cubicTo(
        cx - r * 1.05f, cy - r * 1.2f,
        cx + r * 1.05f, cy - r * 1.2f,
        cx + r * 0.95f, cy - r * 0.1f
    )
    path.cubicTo(
        cx + r * 0.7f, cy - r * 0.75f,
        cx - r * 0.7f, cy - r * 0.75f,
        cx - r * 0.95f, cy - r * 0.1f
    )
    path.close()
    // Near-black hair drawn from the surface stack — reads as form, not a flat cap.
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            colors = listOf(NazimDarkPalette.BgBase, NazimDarkPalette.Surface1),
            startY = cy - r * 1.2f,
            endY = cy
        )
    )
    // A single hairline of top-lit sheen on the crown — depth from light, not shadow.
    path.reset()
    path.moveTo(cx - r * 0.72f, cy - r * 0.78f)
    path.quadraticBezierTo(cx, cy - r * 1.12f, cx + r * 0.72f, cy - r * 0.78f)
    drawPath(path, color = NazimDarkPalette.GlassStroke, style = Stroke(width = r * 0.03f))
}

private fun DrawScope.drawEye(path: Path, center: Offset, r: Float, open: Float, iris: Color) {
    val openC = open.coerceIn(0.04f, 1f)
    // Sclera — off-white, never pure white (kinder against the near-black face).
    drawOval(
        color = ScleraColor,
        topLeft = Offset(center.x - r, center.y - r * openC),
        size = Size(r * 2f, r * 2f * openC)
    )
    if (openC > 0.25f) {
        // Iris carries the single accent hue — the calm spark of presence.
        val irisR = r * 0.62f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(iris.copy(alpha = 0.95f), iris.copy(alpha = 0.5f)),
                center = center,
                radius = irisR
            ),
            radius = irisR * openC.coerceAtMost(1f),
            center = center
        )
        drawCircle(NazimDarkPalette.TextOnAccent, radius = irisR * 0.4f * openC, center = center)
        // Crisp catchlight — the glint of life.
        drawCircle(
            NazimDarkPalette.TextPrimary.copy(alpha = 0.85f),
            radius = r * 0.12f,
            center = Offset(center.x - irisR * 0.3f, center.y - irisR * 0.3f)
        )
    }
    // Upper lid line.
    drawLidStroke(path, center, r, openC)
}

private fun DrawScope.drawLidStroke(path: Path, center: Offset, r: Float, open: Float) {
    path.reset()
    path.moveTo(center.x - r, center.y)
    path.quadraticBezierTo(center.x, center.y - r * open * 1.1f, center.x + r, center.y)
    drawPath(path, color = SkinShadow.copy(alpha = 0.7f), style = Stroke(width = r * 0.12f))
}

private fun DrawScope.drawBrow(path: Path, center: Offset, halfWidth: Float, tilt: Float) {
    val dy = halfWidth * tilt
    path.reset()
    path.moveTo(center.x - halfWidth, center.y + dy)
    path.quadraticBezierTo(
        center.x, center.y - halfWidth * 0.25f + dy * 0.3f,
        center.x + halfWidth, center.y - dy
    )
    drawPath(path, color = BrowColor, style = Stroke(width = halfWidth * 0.28f))
}

private fun DrawScope.drawNose(path: Path, cx: Float, cy: Float, r: Float) {
    path.reset()
    path.moveTo(cx, cy - r * 0.05f)
    path.lineTo(cx - r * 0.12f, cy + r * 0.28f)
    path.quadraticBezierTo(cx, cy + r * 0.38f, cx + r * 0.12f, cy + r * 0.28f)
    drawPath(path, color = SkinShadow.copy(alpha = 0.55f), style = Stroke(width = r * 0.06f))
}

private fun DrawScope.drawMouth(
    path: Path,
    center: Offset,
    halfWidth: Float,
    open: Float,
    curve: Float,
    accent: Color
) {
    val openH = halfWidth * open
    // Lip body — warm flesh blended toward the single accent at the parting, very gently.
    path.reset()
    path.moveTo(center.x - halfWidth, center.y)
    path.quadraticBezierTo(center.x, center.y - halfWidth * curve * 0.5f, center.x + halfWidth, center.y)
    path.quadraticBezierTo(center.x, center.y + openH + halfWidth * curve * 0.5f, center.x - halfWidth, center.y)
    path.close()
    drawPath(
        path,
        brush = Brush.verticalGradient(
            colors = listOf(LipColor, accent.copy(alpha = 0.28f)),
            startY = center.y - openH,
            endY = center.y + openH
        )
    )
    if (open > 0.12f) {
        // Inner mouth shadow.
        path.reset()
        path.moveTo(center.x - halfWidth * 0.7f, center.y + openH * 0.1f)
        path.quadraticBezierTo(center.x, center.y + openH, center.x + halfWidth * 0.7f, center.y + openH * 0.1f)
        path.quadraticBezierTo(center.x, center.y + openH * 0.4f, center.x - halfWidth * 0.7f, center.y + openH * 0.1f)
        path.close()
        drawPath(path, color = MouthInnerColor)
    }
}

private fun DrawScope.drawRimLight(
    path: Path,
    cx: Float,
    cy: Float,
    r: Float,
    accent: Color,
    intensity: Float
) {
    val a = intensity.coerceIn(0f, 0.6f)
    path.reset()
    path.moveTo(cx + r * 0.85f, cy - r * 0.7f)
    path.quadraticBezierTo(cx + r * 1.0f, cy, cx + r * 0.8f, cy + r * 0.8f)
    drawPath(path, color = accent.copy(alpha = a), style = Stroke(width = r * 0.06f))
}

// --- Palette + math -----------------------------------------------------------------------------

// Flesh / portrait tones — intentionally local: the token palette has no skin hues. Tuned a touch
// cooler/quieter than before so the single blue accent reads as the hero on the near-black canvas.
// Everything else (the accent fill, sheen, rim, iris, lip parting) is driven by committed tokens.
private val SkinHigh = Color(0xFFE2BCA0)
private val SkinMid = Color(0xFFBF9069)
private val SkinShadow = Color(0xFF855B43)
private val LipColor = Color(0xFF845360)
private val MouthInnerColor = Color(0xFF240D14)
private val BrowColor = Color(0xFF1E1A24)

// Sclera reuses the off-white text token so the face never introduces a pure white.
private val ScleraColor = NazimDarkPalette.TextPrimary

private const val TWO_PI = (2.0 * PI).toFloat()

/**
 * Resolves the light hue for the current [state]. The new direction is a SINGLE restrained accent,
 * not a rainbow: every conversational state stays inside the one soft electric-blue family
 * ([NazimDarkPalette.AccentSolid] and its muted blue-family tints), gently biased toward the active
 * [persona] accent so the face quietly reflects who is present. ERROR is the one muted exception.
 * Mirrors [AuroraOrb] so the presence glow and the portrait read as one coherent calm light.
 */
private fun stateAccent(state: NazimState, persona: Persona): Color {
    val personaHue = persona.primaryColors.firstOrNull() ?: NazimDarkPalette.AccentSolid
    val stateHue = when (state) {
        NazimState.LISTENING -> NazimDarkPalette.AccentCyan
        NazimState.THINKING -> NazimDarkPalette.AccentViolet
        NazimState.SPEAKING -> NazimDarkPalette.AccentMagenta
        NazimState.ACTING -> NazimDarkPalette.AccentSolid
        NazimState.WALKING -> NazimDarkPalette.AccentSolid
        NazimState.RUNNING -> NazimDarkPalette.AccentSolid
        NazimState.ERROR -> NazimDarkPalette.Error
        NazimState.IDLE -> NazimDarkPalette.AccentSolid
    }
    // Bias toward the state hue but keep the persona's identity present; both sit in the one accent.
    return lerp(personaHue, stateHue, 0.45f)
}

private fun blinkEnvelope(phase: Float): Float {
    val window = 0.06f
    if (phase < 1f - window) return 0f
    val local = (phase - (1f - window)) / window
    return (1f - abs(local - 0.5f) * 2f).coerceIn(0f, 1f)
}

/** Per-state facial bias used by the fallback portrait. */
private data class Mood(
    val eyeOpen: Float,
    val browRaise: Float,
    val browTilt: Float,
    val mouthCurve: Float,
    val mouthOpenBias: Float
)

private fun NazimState.mood(): Mood = when (this) {
    NazimState.IDLE -> Mood(eyeOpen = 0.9f, browRaise = 0f, browTilt = 0f, mouthCurve = 0.15f, mouthOpenBias = 0f)
    NazimState.LISTENING -> Mood(eyeOpen = 1f, browRaise = 0.6f, browTilt = 0.1f, mouthCurve = 0.2f, mouthOpenBias = 0f)
    NazimState.THINKING -> Mood(eyeOpen = 0.8f, browRaise = 0.3f, browTilt = -0.25f, mouthCurve = -0.05f, mouthOpenBias = 0f)
    NazimState.SPEAKING -> Mood(eyeOpen = 1f, browRaise = 0.2f, browTilt = 0.05f, mouthCurve = 0.3f, mouthOpenBias = 0.1f)
    NazimState.ACTING -> Mood(eyeOpen = 1f, browRaise = -0.2f, browTilt = -0.3f, mouthCurve = 0.05f, mouthOpenBias = 0f)
    NazimState.WALKING -> Mood(eyeOpen = 1f, browRaise = 0.1f, browTilt = -0.1f, mouthCurve = 0.2f, mouthOpenBias = 0f)
    NazimState.RUNNING -> Mood(eyeOpen = 1f, browRaise = -0.1f, browTilt = -0.2f, mouthCurve = 0.1f, mouthOpenBias = 0.05f)
    NazimState.ERROR -> Mood(eyeOpen = 0.85f, browRaise = 0.5f, browTilt = 0.35f, mouthCurve = -0.4f, mouthOpenBias = 0f)
}

/** Map [NazimState] to the [CompanionState] expected by [AuroraOrb]. */
private fun NazimState.toCompanionState(): CompanionState = when (this) {
    NazimState.IDLE -> CompanionState.IDLE
    NazimState.LISTENING -> CompanionState.LISTENING
    NazimState.THINKING -> CompanionState.THINKING
    NazimState.SPEAKING -> CompanionState.SPEAKING
    NazimState.ACTING -> CompanionState.THINKING
    NazimState.WALKING -> CompanionState.THINKING
    NazimState.RUNNING -> CompanionState.THINKING
    NazimState.ERROR -> CompanionState.ERROR
}
