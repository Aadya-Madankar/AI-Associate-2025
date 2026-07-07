package com.example.ui.agent

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.permission.AutonomyMode
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import com.example.ui.theme.XenoShapeTokens

/**
 * A compact pill that displays the active [AutonomyMode] and cycles it on tap
 * (ARCHITECTURE.md §4.1, SCOPE.md §4).
 *
 * The five modes form a deliberate ordering — ASK → ASK_LESS → AUTO → PLAN → ASK — that
 * the pill walks through on each tap. **BYPASS is intentionally excluded from the cycle**:
 * per the safety rules it must be a deliberate, re-confirmed opt-in and never reachable
 * via a one-tap toggle, so this control never advances *into* it (though it still renders
 * BYPASS correctly if the mode is already set elsewhere).
 *
 * Pure presentation: the parent owns the persisted mode (DataStore via
 * `AutonomyModeStore`) and decides what the next mode is; this composable only reflects
 * [mode] and reports taps through [onCycle].
 *
 * Visual language ("XENO: Living Presence"): a near-black frosted-glass pill, defined by a
 * single hairline and a faint top-lit sheen — no heavy fill, no stacked gradients. The mode
 * is read almost entirely by ONE thing: a small accent dot, the pill's only solid hit of
 * colour. Within the single soft-electric-blue accent family the dot quietly shifts tint per
 * mode (amber alone breaks out to mark the armed BYPASS state); the label stays neutral
 * off-white. A slow breath gives the dot a barely-there halo of ambient life, and a gentle
 * spring answers each press. The accent crossfades on a mode change so cycling reads as one
 * calm hue shift rather than a hard cut. Less is more — the dot does the talking.
 *
 * @param mode     the currently active mode to display.
 * @param onCycle  invoked with the **next** mode in the cycle when the pill is tapped.
 * @param modifier applied to the pill.
 * @param enabled  when false, the pill is shown but taps are ignored (e.g. while a task
 *                 is mid-flight and the mode is locked).
 */
@Composable
fun ModePill(
    mode: AutonomyMode,
    onCycle: (AutonomyMode) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val label = remember(mode) { mode.label() }
    val targetAccent = mode.accent()

    // One crossfading accent so a mode change reads as a calm hue shift, not a hard cut.
    // When locked, the accent recedes — the pill is present but plainly inactive.
    val accent by animateColorAsState(
        targetValue = if (enabled) targetAccent else targetAccent.copy(alpha = 0.45f),
        animationSpec = tween(durationMillis = Motion.Base, easing = Motion.EaseStandard),
        label = "modeAccent"
    )

    // The single ambient loop: one slow breath driving only the dot's soft halo. Nothing
    // else animates — the dot is the one quiet living thing here, never competing with XENO.
    val breath = rememberInfiniteTransition(label = "modeBreath")
    val glow by breath.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Motion.Ambient, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Reverse
        ),
        label = "modeGlow"
    )

    // Gentle spring on press — the only feedback motion.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) Motion.PressScale else Motion.RestScale,
        animationSpec = Motion.SpringPress,
        label = "modePress"
    )

    Row(
        modifier = modifier
            .scale(pressScale)
            .clip(XenoShapeTokens.Pill)
            // Barely-there frosted glass — a flat near-black surface, no mode fill. The
            // colour lives entirely in the dot, keeping the pill calm and recessive.
            .background(SolidColor(XenoColors.Surface1))
            // Depth from light, not shadow: a faint top-lit sheen over a quiet 1px hairline.
            .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.Pill)
            .border(1.dp, Brush.verticalGradient(XenoColors.GlassOverlay), XenoShapeTokens.Pill)
            .selectable(
                selected = true,
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = { onCycle(mode.nextInCycle()) }
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // The mode dot: a solid accent core inside a soft breathing halo — the pill's one
        // and only solid hit of colour, and the primary way the mode is read.
        Box(
            modifier = Modifier
                .size(7.dp)
                .drawBehind {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val core = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.45f), Color.Transparent),
                            center = center,
                            radius = core * (2.4f + 1.0f * glow)
                        ),
                        radius = core * (2.4f + 1.0f * glow),
                        center = center
                    )
                    drawCircle(color = accent, radius = core * 0.9f, center = center)
                }
        )
        // Neutral small-caps label — composed, tightly tracked telemetry voice, never
        // tinted, so the dot stays the only colour and the type just reads cleanly.
        Text(
            text = label,
            color = if (enabled) XenoColors.TextPrimary else XenoColors.TextTertiary,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/** Caps display label for one [AutonomyMode] (small caps are the pill voice). */
private fun AutonomyMode.label(): String = when (this) {
    AutonomyMode.ASK -> "ASK"
    AutonomyMode.ASK_LESS -> "ASK-LESS"
    AutonomyMode.AUTO -> "AUTO"
    AutonomyMode.BYPASS -> "BYPASS"
    AutonomyMode.PLAN -> "PLAN"
}

/**
 * Accent tint encoding the mode. Everything stays inside the single soft-electric-blue accent
 * family — the cooler blue for the calm ASK / ASK-LESS baseline, the accent blue for trusted
 * AUTO, the signature blue tint for "show me first" PLAN — so mode reads as a quiet shift, not
 * a rainbow. Amber alone breaks out to mark the armed BYPASS state.
 */
private fun AutonomyMode.accent(): Color = when (this) {
    AutonomyMode.ASK -> XenoColors.ModeAsk
    AutonomyMode.ASK_LESS -> XenoColors.ModeAsk
    AutonomyMode.AUTO -> XenoColors.ModeAuto
    AutonomyMode.BYPASS -> XenoColors.ModeBypass
    AutonomyMode.PLAN -> XenoColors.ModePlan
}

/**
 * The next mode this pill advances to. BYPASS is never entered via the cycle (it requires
 * a deliberate re-confirmed opt-in elsewhere); if the current mode *is* BYPASS, tapping
 * returns to the safe ASK baseline.
 */
private fun AutonomyMode.nextInCycle(): AutonomyMode = when (this) {
    AutonomyMode.ASK -> AutonomyMode.ASK_LESS
    AutonomyMode.ASK_LESS -> AutonomyMode.AUTO
    AutonomyMode.AUTO -> AutonomyMode.PLAN
    AutonomyMode.PLAN -> AutonomyMode.ASK
    AutonomyMode.BYPASS -> AutonomyMode.ASK
}
