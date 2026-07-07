package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.core.CompanionState
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * The mic orb — XENO: Living Presence.
 *
 * A calm, premium microphone toggle: a single clean orb, one restrained accent, crisp
 * idle / listening / speaking states. Less is more — no rainbow, no heavy shadows; depth
 * comes from a hairline edge and a soft top-lit sheen, life from a slow breath and an
 * amplitude-reactive glow.
 *
 * - IDLE / ERROR: a quiet frosted-glass orb (layered surface fill + faint top sheen + 1px
 *   hairline) with a [Icons.Rounded.MicOff] glyph. ERROR tints the hairline with
 *   [XenoColors.Error] for an unmistakable cue.
 * - LIVE (CONNECTING / LISTENING / THINKING / SPEAKING): a solid accent orb lit from the
 *   top, ringed by a single-accent halo that breathes slowly and swells with the voice.
 *   Every live moment stays within the one soft electric-blue accent family — the tint
 *   shifts subtly per state, never to a competing hue.
 *
 * Motion budget: ONE infinite transition drives the slow breath; the halo radius follows
 * [amplitude] on a short reactive window; press uses a gentle spring. Nothing busy.
 */
@Composable
fun MicButton(
    state: CompanionState,
    amplitude: Float,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLive = state != CompanionState.IDLE && state != CompanionState.ERROR
    val amp = amplitude.coerceIn(0f, 1f)

    // One restrained accent, gently tinted per moment — all within the single blue family,
    // so the orb reads as a calm presence and never as a rainbow.
    val accent = when (state) {
        CompanionState.LISTENING -> XenoColors.AccentCyan
        CompanionState.SPEAKING -> XenoColors.AccentMagenta
        CompanionState.THINKING, CompanionState.CONNECTING -> XenoColors.AccentViolet
        CompanionState.ERROR -> XenoColors.Error
        CompanionState.IDLE -> XenoColors.AccentSolid
    }
    val accentAnim by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(Motion.Base, easing = Motion.EaseStandard),
        label = "micAccent"
    )

    // ONE infinite transition for all ambient life: a slow, human breath that is always
    // present (subtle when live, near-still otherwise).
    val transition = rememberInfiniteTransition(label = "mic")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Ambient, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Restart
        ),
        label = "breath"
    )

    // Amplitude-reactive halo scale, chasing the voice on a short reactive window.
    val haloScale by animateFloatAsState(
        targetValue = if (isLive) 1f + amp * 0.45f else 1f,
        animationSpec = tween(Motion.Reactive, easing = Motion.EaseStandard),
        label = "haloScale"
    )

    // Gentle spring press feedback.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else Motion.RestScale,
        animationSpec = Motion.SpringPress,
        label = "pressScale"
    )

    val buttonSize = 76.dp
    val haloExtent = 40.dp // generous negative space around the orb for the soft halo

    Box(
        modifier = modifier.size(buttonSize + haloExtent * 2),
        contentAlignment = Alignment.Center
    ) {
        // Single-accent presence halo — only while live, so the rest of the time is still.
        if (isLive) {
            Canvas(modifier = Modifier.size(buttonSize + haloExtent * 2)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val baseR = buttonSize.toPx() / 2f

                // Slow breath on the halo (subtle), independent of the voice amplitude.
                val breathSwell = 0.06f * (0.5f + 0.5f * sin(breath))

                val haloR = baseR * (haloScale + breathSwell + 0.85f)
                val rimR = baseR * (haloScale + breathSwell + 0.06f)

                // Soft ambient glow — the one accent radiating, then dissolving into the
                // near-black canvas (last stop transparent), louder as the voice rises.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentAnim.copy(alpha = 0.20f + amp * 0.14f),
                            accentAnim.copy(alpha = 0.07f),
                            Color.Transparent
                        ),
                        center = c,
                        radius = haloR
                    ),
                    radius = haloR,
                    center = c
                )

                // A single crisp accent rim just outside the orb — no sweep, no rainbow.
                drawCircle(
                    color = accentAnim.copy(alpha = 0.45f),
                    radius = rimR,
                    center = c,
                    style = Stroke(width = baseR * 0.04f)
                )
            }
        }

        Surface(
            onClick = onToggle,
            shape = CircleShape,
            color = Color.Transparent,
            interactionSource = interaction,
            modifier = Modifier
                .size(buttonSize)
                .scale(pressScale)
        ) {
            Box(
                modifier = Modifier
                    .size(buttonSize)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(buttonSize)) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = size.minDimension / 2f

                    if (isLive) {
                        // Solid accent orb, lit softly from the top for quiet depth.
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    accentAnim,
                                    accentAnim.copy(alpha = 0.88f)
                                ),
                                center = Offset(c.x, c.y - r * 0.4f),
                                radius = r * 1.5f
                            ),
                            radius = r,
                            center = c
                        )
                        // Faint top-lit inner sheen — light, never shadow.
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(XenoColors.GlassHighlight, Color.Transparent),
                                center = Offset(c.x, c.y - r * 0.55f),
                                radius = r * 0.9f
                            ),
                            radius = r,
                            center = c
                        )
                        // Lit top edge.
                        drawCircle(
                            color = XenoColors.GlassStrokeStrong,
                            radius = r - 1f,
                            center = c,
                            style = Stroke(width = 1.5f)
                        )
                    } else {
                        // Quiet frosted-glass orb: layered surface fill + soft sheen + hairline.
                        drawCircle(
                            brush = Brush.linearGradient(
                                colors = listOf(XenoColors.Surface2, XenoColors.Surface1),
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height)
                            ),
                            radius = r,
                            center = c
                        )
                        // Faint top-lit inner sheen, just inside the edge.
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(XenoColors.GlassHighlight, Color.Transparent),
                                center = Offset(c.x, c.y - r * 0.5f),
                                radius = r
                            ),
                            radius = r,
                            center = c
                        )
                        // Hairline edge; ERROR tints it for an unmistakable cue.
                        val strokeColor =
                            if (state == CompanionState.ERROR) {
                                XenoColors.Error.copy(alpha = 0.55f)
                            } else {
                                XenoColors.GlassStroke
                            }
                        drawCircle(
                            color = strokeColor,
                            radius = r - 1f,
                            center = c,
                            style = Stroke(width = 1.5f)
                        )
                    }
                }

                Icon(
                    imageVector = if (isLive) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                    contentDescription = if (isLive) "Stop listening" else "Start listening",
                    tint = if (isLive) XenoColors.TextOnAccent else XenoColors.TextPrimary,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}
