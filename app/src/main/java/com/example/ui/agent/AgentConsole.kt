package com.example.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.agent.AgentStatus
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import com.example.ui.theme.XenoShapeTokens

/**
 * The live agent **console strip** (ARCHITECTURE.md §3.4): a quiet, always-visible-while-
 * acting working strip that shows the current step and how many tool calls have run, so the
 * user can watch the plan-act-observe loop unfold — without ever competing with XENO.
 *
 * Animates in only while [AgentStatus.active] is true (it collapses away when idle). Pure
 * presentation — driven entirely by the [status] the parent collects from the agent loop /
 * `XenoViewModel`.
 *
 * Visual language ("XENO: Living Presence"): a single barely-there frosted strip floating over
 * the near-black canvas. There is no busy sweep — restraint is the point. One soft, slowly
 * breathing presence dot in the single electric-blue accent is the only motion, signalling the
 * loop is quietly alive. Depth comes from a 1px hairline and a faint top-lit sheen, never
 * shadow. The step counter sits in a calm full-radius pill. The strip fades + expands in and
 * dissolves out so it never snaps.
 *
 * @param status   the current agent progress.
 * @param modifier applied to the strip.
 */
@Composable
fun AgentConsole(
    status: AgentStatus,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = status.active,
        enter = fadeIn(tween(Motion.Base, easing = Motion.EaseEnter)) +
            expandVertically(tween(Motion.Base, easing = Motion.EaseEnter)),
        exit = fadeOut(tween(Motion.Quick, easing = Motion.EaseExit)) +
            shrinkVertically(tween(Motion.Quick, easing = Motion.EaseExit))
    ) {
        // The strip's single hue: THE soft electric-blue accent. One accent, used sparingly.
        val accent = XenoColors.AccentSolid

        // One ambient loop drives the entire strip — a single slow breath of presence, the
        // only motion on the strip (per the "one ambient loop" rule). Nothing sweeps or busies.
        val transition = rememberInfiniteTransition(label = "agentConsole")
        val breath by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = Motion.Ambient, easing = Motion.EaseAmbient),
                repeatMode = RepeatMode.Reverse
            ),
            label = "agentBreath"
        )

        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(XenoShapeTokens.CardInner)
                // Barely-there frosted glass: a calm primary surface, lifted by the faintest
                // accent breath so the strip reads as quietly alive, not tinted.
                .background(SolidColor(XenoColors.Surface1))
                .background(accent.copy(alpha = 0.018f + 0.022f * breath))
                // Top-lit glass sheen, then a single quiet hairline. Light, not shadow.
                .border(1.dp, Brush.verticalGradient(XenoColors.GlassOverlay), XenoShapeTokens.CardInner)
                .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.CardInner)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            WorkingDot(accent = accent, breath = breath)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WORKING",
                    color = XenoColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(3.dp))
                // Hoisted so the fallback string isn't re-derived on every breath frame.
                val step = remember(status.currentStep) {
                    status.currentStep.ifBlank { "Thinking…" }
                }
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = XenoColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (status.stepCount > 0) {
                StepCountBadge(step = status.stepCount)
            }
        }
    }
}

/**
 * The live indicator: a solid accent core inside a soft breathing halo — the strip's single
 * accent hit and its only motion, signalling the loop is quietly alive.
 */
@Composable
private fun WorkingDot(accent: Color, breath: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(9.dp)
            .scale(0.94f + 0.08f * breath)
            .drawBehind {
                val center = Offset(size.width / 2f, size.height / 2f)
                val core = size.minDimension / 2f
                // Soft presence halo that dissolves fully into the near-black.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.30f + 0.18f * breath), Color.Transparent),
                        center = center,
                        radius = core * (2.0f + 1.0f * breath)
                    ),
                    radius = core * (2.0f + 1.0f * breath),
                    center = center
                )
                drawCircle(color = accent, radius = core * 0.6f, center = center)
            }
    )
}

/** A quiet, neutral full-radius pill showing the current step number. */
@Composable
private fun StepCountBadge(step: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(XenoShapeTokens.Pill)
            .background(SolidColor(XenoColors.Surface2))
            .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.Pill)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "STEP $step",
            color = XenoColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
