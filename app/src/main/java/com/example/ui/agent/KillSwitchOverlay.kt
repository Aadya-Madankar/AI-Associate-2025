package com.example.ui.agent

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import com.example.ui.theme.XenoShapeTokens
import kotlin.math.PI
import kotlin.math.sin

/**
 * The persistent, always-on-top panic **STOP** control (ARCHITECTURE.md §4.4, SCOPE.md §4).
 * One tap halts all automation: the parent's [onStop] wires into
 * `KillSwitch.trigger(...)`, which cancels in-flight gestures, lets the accessibility
 * service `disableSelf()`, and reverts the autonomy mode to ASK.
 *
 * This is a pure composable: it only renders the button and reports the tap. In production
 * it is hosted in a persistent floating window (foreground-service overlay /
 * `TYPE_ACCESSIBILITY_OVERLAY`) so it survives across apps and can't be obscured by the
 * automated target; here it is a plain composable the caller positions.
 *
 * Obsidian Aurora styling: STOP is the one place a semantic hue ([XenoColors.Error] — a
 * calm coral, never an alarming fire-engine red) is promoted to a solid hit. It reads as a
 * floating glass pill lit from within, ringed by a soft halo that *breathes* on the ambient
 * 4s cycle so it stays unmistakable while a task runs, yet never strobes or shouts. Depth
 * comes from light — a radial halo that dissolves into the obsidian plus a lit top edge —
 * not from heavy shadow. Press answers with a gentle spring.
 *
 * Motion budget: a single infinite transition drives the calm breath (halo alpha + a
 * barely-perceptible scale); press uses one spring. Nothing else moves.
 *
 * @param visible  whether the overlay is shown at all (typically: a task is active and the
 *                 switch isn't already tripped).
 * @param onStop   invoked on tap to trigger the kill switch.
 * @param modifier applied to the button; the caller positions it (e.g. bottom-end).
 * @param expanded when true, shows a labelled pill ("STOP"); when false, a compact FAB-style
 *                 circle. Defaults to expanded for unmissable visibility while acting.
 */
@Composable
fun KillSwitchOverlay(
    visible: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true
) {
    if (!visible) return

    val stop = XenoColors.Error

    // One infinite transition for all ambient life: a slow, organic breath phase. Kept as a
    // State and read only inside draw/layer lambdas (deferred to the draw phase) so the
    // per-frame breath never recomposes this composable — only redraws.
    val transition = rememberInfiniteTransition(label = "killswitch")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Ambient, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Restart
        ),
        label = "breath"
    )
    // 0f..1f breath curve derived from the single phase — cheap, no extra animations.
    val breath: () -> Float = { 0.5f + 0.5f * sin(phase.value) }

    // Gentle spring press feedback, matching the mic and other controls. Read in a layer
    // lambda so press + breath drive scale without recomposing.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else Motion.RestScale,
        animationSpec = Motion.SpringPress,
        label = "pressScale"
    )

    // The breath is a whisper of scale on top of the press response.
    val liveScale: GraphicsLayerScope.() -> Unit = {
        val s = pressScale * (1f + breath() * 0.012f)
        scaleX = s
        scaleY = s
    }

    if (expanded) {
        Surface(
            onClick = onStop,
            shape = XenoShapeTokens.Pill,
            color = Color.Transparent,
            interactionSource = interaction,
            modifier = modifier
                .graphicsLayer(liveScale)
                .semanticsStop()
        ) {
            Row(
                modifier = Modifier
                    .stopPillGlass(stop, breath)
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = null,
                    tint = XenoColors.TextOnAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "STOP",
                    color = XenoColors.TextOnAccent,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    } else {
        val orbSize = 60.dp
        val ringExtent = 22.dp
        Box(
            modifier = modifier.size(orbSize + ringExtent * 2),
            contentAlignment = Alignment.Center
        ) {
            // Breathing halo behind the orb — dissolves into the obsidian. `breath()` is read
            // here in the draw phase, so the pulse redraws without recomposing.
            Canvas(modifier = Modifier.size(orbSize + ringExtent * 2)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension / 2f
                val inner = 0.16f + 0.16f * breath()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            stop.copy(alpha = inner),
                            stop.copy(alpha = inner * 0.4f),
                            Color.Transparent
                        ),
                        center = c,
                        radius = r
                    ),
                    radius = r,
                    center = c
                )
            }

            Surface(
                onClick = onStop,
                shape = CircleShape,
                color = Color.Transparent,
                interactionSource = interaction,
                modifier = Modifier
                    .size(orbSize)
                    .graphicsLayer(liveScale)
                    .semanticsStop()
            ) {
                Box(
                    modifier = Modifier
                        .size(orbSize)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(orbSize)) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = size.minDimension / 2f
                        // Solid accent orb, top-lit radial for depth.
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(stop, stop.copy(alpha = 0.85f)),
                                center = Offset(c.x, c.y - r * 0.35f),
                                radius = r * 1.4f
                            ),
                            radius = r,
                            center = c
                        )
                        // Lit top edge — light, not shadow.
                        drawCircle(
                            color = XenoColors.GlassStrokeStrong,
                            radius = r - 1f,
                            center = c,
                            style = Stroke(width = 2f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = "Stop the agent",
                        tint = XenoColors.TextOnAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

/**
 * The expanded pill's glass recipe, drawn behind its content and tracking the measured
 * size: a soft breathing halo, a solid accent base lit from the top by a radial sheen, and
 * a hairline lit top edge. Light, not shadow. Brushes are cached and only the breath alpha
 * varies per frame.
 */
private fun Modifier.stopPillGlass(stop: Color, breath: () -> Float): Modifier =
    drawWithCache {
        // Cached once per size change: the static brushes + geometry. Only the breathing
        // halo alpha is recomputed each draw, inside onDrawBehind.
        val pillR = size.height / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val haloR = size.maxDimension * 0.62f
        val base = Brush.verticalGradient(
            colors = listOf(stop, stop.copy(alpha = 0.88f)),
            startY = 0f,
            endY = size.height
        )
        val sheen = Brush.verticalGradient(
            colors = listOf(XenoColors.GlassHighlight, Color.Transparent),
            startY = 0f,
            endY = size.height * 0.5f
        )
        onDrawBehind {
            // Breathing halo first, so the pill sits crisply on top of its own glow.
            val inner = 0.16f + 0.16f * breath()
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        stop.copy(alpha = inner),
                        stop.copy(alpha = inner * 0.4f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = haloR
                ),
                radius = haloR,
                center = center
            )
            // Solid accent base.
            drawRoundRect(brush = base, cornerRadius = CornerRadius(pillR))
            // Top-lit sheen for the "glass under light" read.
            drawRoundRect(brush = sheen, cornerRadius = CornerRadius(pillR))
            // Brighter lit top edge — a hairline of light.
            drawRoundRect(
                color = XenoColors.GlassStrokeStrong,
                cornerRadius = CornerRadius(pillR),
                style = Stroke(width = 2f)
            )
        }
    }

/**
 * Restores the accessibility cue the original [androidx.compose.foundation.clickable] carried:
 * a Button role and an explicit "Stop the agent" click label, layered on top of the
 * [Surface]'s own click handling.
 */
private fun Modifier.semanticsStop(): Modifier = semantics {
    role = Role.Button
    onClick(label = "Stop the agent", action = null)
}
