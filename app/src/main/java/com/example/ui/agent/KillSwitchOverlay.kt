package com.example.ui.agent

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.example.ui.theme.XenoShapeTokens
import com.example.ui.theme.XenoWarm
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
 * XENO: Warm Light styling: STOP is the one place a semantic hue ([XenoWarm.Error] — a
 * calm coral, never an alarming fire-engine red) is promoted to a solid hit. It reads as a
 * floating glass pill lit from within, ringed by a soft halo that *breathes* on the ambient
 * 4s cycle so it stays unmistakable while a task runs, yet never strobes or shouts. Depth
 * comes from light — a radial halo that dissolves into the warm canvas plus a lit top edge —
 * not from heavy shadow. Press answers with a gentle spring.
 *
 * Motion budget: a single infinite transition drives the calm breath (halo alpha + a
 * barely-perceptible scale); press uses one spring. Nothing else moves.
 *
 * @param visible  whether the overlay is shown at all (typically: a task is active and the
 *                 switch isn't already tripped).
 * @param onStop   invoked on tap to trigger the kill switch.
 * @param modifier applied to the button; the caller positions it (e.g. bottom-end).
 */
@Composable
fun KillSwitchOverlay(
    visible: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val stop = XenoWarm.Error

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
                tint = XenoWarm.TextOnDark,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "STOP",
                color = XenoWarm.TextOnDark,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/**
 * The STOP pill's glass recipe, drawn behind its content and tracking the measured
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
            colors = listOf(XenoWarm.Sheen, Color.Transparent),
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
                color = XenoWarm.Sheen,
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
    onClick(label = "Stop and close the connection", action = null)
}
