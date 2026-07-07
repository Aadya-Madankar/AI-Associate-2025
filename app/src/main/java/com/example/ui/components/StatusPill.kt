package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.example.core.CompanionState
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import com.example.ui.theme.XenoShapeTokens

/**
 * Small tracked-out caps status label shown above the mic button.
 *
 * Renders CONNECTING / LISTENING / THINKING / SPEAKING during a live session,
 * and falls back to the active [personaName] when IDLE. A single state dot
 * carries liveness and gently breathes while a session is active.
 *
 * XENO: Living Presence — a quiet hairline-glass pill that recedes so the
 * on-screen person stays the hero. A barely-there [XenoColors.Surface1] fill
 * under a top-lit [XenoColors.GlassOverlay] sheen, a 1dp [XenoColors.GlassStroke]
 * hairline lit by [XenoColors.GlassStrokeStrong] along the top edge. The live
 * state speaks through the ONE soft electric accent only — the dot and a faint
 * accent wash — never a rainbow. Depth is light, not shadow. A single ambient
 * loop drives the dot's slow breath so nothing competes with XENO.
 */
@Composable
fun StatusPill(
    state: CompanionState,
    personaName: String,
    modifier: Modifier = Modifier
) {
    // State reads through the single accent family; only ERROR breaks to a muted
    // semantic red, and IDLE is fully neutral so the resting pill is silent.
    val (label, dotColor, pulsing) = when (state) {
        CompanionState.CONNECTING -> Triple("CONNECTING", XenoColors.AccentCyan, true)
        CompanionState.LISTENING -> Triple("LISTENING", XenoColors.AccentCyan, true)
        CompanionState.THINKING -> Triple("THINKING", XenoColors.AccentViolet, true)
        CompanionState.SPEAKING -> Triple("SPEAKING", XenoColors.AccentSolid, true)
        CompanionState.ERROR -> Triple("ERROR", XenoColors.Error, false)
        CompanionState.IDLE -> Triple(personaName.uppercase(), XenoColors.TextTertiary, false)
    }
    val live = state != CompanionState.IDLE && state != CompanionState.ERROR

    // One ambient loop: a slow, sine-like breath that softly cross-fades the dot.
    val transition = rememberInfiniteTransition(label = "status-pill")
    val breath by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Ambient / 2, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )
    val dotAlpha = if (pulsing) breath else 1f

    // Top-lit glass sheen — light, not shadow — sits just inside the hairline.
    val sheen = remember {
        Brush.verticalGradient(XenoColors.GlassOverlay)
    }
    // A faint single-accent wash, only while live, so the active state is felt
    // without a second competing surface. Dissolves into the near-black below.
    val accentWash = remember(dotColor, live) {
        if (live) {
            Brush.verticalGradient(
                colors = listOf(dotColor.copy(alpha = 0.07f), Color.Transparent)
            )
        } else {
            null
        }
    }

    Row(
        modifier = modifier
            .clip(XenoShapeTokens.Pill)
            .background(SolidColor(XenoColors.Surface1.copy(alpha = 0.66f)))
            .then(if (accentWash != null) Modifier.background(accentWash) else Modifier)
            .background(sheen)
            // Top-lit glass sheen as the hairline (light, not shadow), then a quiet
            // even hairline so every edge of the glass stays defined.
            .border(1.dp, Brush.verticalGradient(XenoColors.GlassOverlay), XenoShapeTokens.Pill)
            .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.Pill)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(dotAlpha)
                .drawBehind {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = size.minDimension / 2f
                    // Soft halo dissolving into the near-black, then a quiet core.
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(dotColor.copy(alpha = 0.40f), Color.Transparent),
                            center = c,
                            radius = r * 2.2f
                        ),
                        radius = r * 2.2f,
                        center = c
                    )
                    drawCircle(color = dotColor, radius = r * 0.85f, center = c)
                }
        )
        Text(
            text = label,
            color = if (state == CompanionState.IDLE) XenoColors.TextTertiary else XenoColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
