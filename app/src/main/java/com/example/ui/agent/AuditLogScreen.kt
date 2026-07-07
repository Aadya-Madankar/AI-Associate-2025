package com.example.ui.agent

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audit.AuditEntry
import com.example.audit.AuditOutcome
import com.example.permission.AutonomyMode
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import com.example.ui.theme.XenoShapeTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The append-only audit trail (ARCHITECTURE.md §4.4): one row per observed/attempted
 * action, newest first. Each [AuditEntry] records the autonomy mode, action type, target
 * app, decision, outcome, and a **hashed** params digest — never raw field contents — so
 * the log itself can't leak message bodies, numbers, or amounts.
 *
 * Pure presentation: the parent supplies the already-loaded [entries] (e.g. collected from
 * `AuditLog.recent(...)`); this screen only renders them.
 *
 * Visual language ("Obsidian Aurora"): a calm, scannable ledger floating over the obsidian
 * stack. A single slow aurora breath lights the header — the one ambient loop on screen —
 * while each row is a quiet glass card carrying a mode-tinted rail, a monospace timestamp,
 * the action + target, and a full-radius outcome chip whose hue is the only color hit.
 * Nothing competes with Nazim.
 *
 * @param entries  the audit records to show; assumed newest-first by the caller. An empty
 *                 list shows a friendly placeholder.
 * @param modifier applied to the root.
 * @param title    headline above the list.
 */
@Composable
fun AuditLogScreen(
    entries: List<AuditEntry>,
    modifier: Modifier = Modifier,
    title: String = "Activity log"
) {
    // Single ambient loop for the whole screen — a slow header aurora breath. Hoisted here
    // so individual rows allocate no animations; the list stays cheap at any length.
    val ambient = rememberInfiniteTransition(label = "auditAmbient")
    val breath by ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Motion.Ambient, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Reverse
        ),
        label = "auditBreath"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(XenoColors.BgBase)
    ) {
        AuditHeader(title = title, breath = breath)

        if (entries.isEmpty()) {
            AuditEmptyState(breath = breath, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    AuditRow(entry = entry)
                }
            }
        }
    }
}

/** Screen header: title, a one-line privacy note, and a faint aurora wash that breathes. */
@Composable
private fun AuditHeader(title: String, breath: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // A soft violet aurora bleeds down from the top edge and dissolves into the
            // obsidian — depth from light, not shadow. Alpha rides the slow breath. Built
            // with drawWithCache so the gradient rebuilds only on resize; only the breath
            // alpha is re-read per frame, with no allocation in the draw phase.
            .drawWithCache {
                val base = XenoColors.AccentViolet
                onDrawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                base.copy(alpha = 0.10f + 0.05f * breath),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = size.height
                        )
                    )
                }
            }
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = XenoColors.TextPrimary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Every action the agent observed or attempted. Parameters are stored hashed.",
            style = MaterialTheme.typography.bodyMedium,
            color = XenoColors.TextSecondary
        )
    }
}

/**
 * One audit record rendered as a quiet glass card: a mode-tinted accent rail, the action
 * and target on the left, an outcome chip on the right, and a dim monospace timestamp
 * underneath. Calm density — scannable, never cluttered.
 */
@Composable
private fun AuditRow(entry: AuditEntry, modifier: Modifier = Modifier) {
    // Two hues carry meaning: the outcome drives the chip; the autonomy mode tints the rail
    // (AUTO = violet, BYPASS = amber, …). Stable per-entry, so memoize the lookups.
    val outcomeAccent = remember(entry.outcome) { entry.outcome.accent() }
    val modeAccent = remember(entry.mode) { entry.mode.accent() }
    val timestamp = remember(entry.timestampMs) { renderTimestamp(entry.timestampMs) }
    val action = remember(entry.actionType) { entry.actionType.replace('_', ' ') }
    val meta = remember(entry.targetApp, entry.mode, entry.decision) {
        buildList {
            entry.targetApp?.takeIf { it.isNotBlank() }?.let { add(it) }
            add(entry.mode.name)
            add(entry.decision)
        }.joinToString("  •  ")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(XenoShapeTokens.Card)
            // Obsidian glass: solid surface fill, a faint inner glow, then a top-lit sheen
            // and a 1dp hairline. No drop shadow — depth comes from layered light.
            .background(SolidColor(XenoColors.Surface1))
            .background(XenoColors.GlassHighlight)
            .border(1.dp, Brush.verticalGradient(XenoColors.GlassOverlay), XenoShapeTokens.Card)
            .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.Card)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Mode-tinted accent rail with a soft outward glow — the autonomy signal.
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(44.dp)
                .clip(XenoShapeTokens.Pill)
                .drawBehind {
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(modeAccent.copy(alpha = 0.18f), Color.Transparent),
                            startX = 0f,
                            endX = size.width * 8f
                        )
                    )
                    drawRect(SolidColor(modeAccent))
                }
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = action,
                    style = MaterialTheme.typography.titleMedium,
                    color = XenoColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(10.dp))
                OutcomeChip(label = entry.outcome.label(), accent = outcomeAccent)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelMedium,
                color = XenoColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = XenoColors.TextTertiary
            )
        }
    }
}

/**
 * A full-radius pill describing the [AuditOutcome] — a soft tinted fill behind a 1dp
 * accent hairline, with a small solid dot as the one solid color hit. The outcome is the
 * only place a row carries chroma, keeping the ledger calm.
 */
@Composable
private fun OutcomeChip(label: String, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(XenoShapeTokens.Pill)
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), XenoShapeTokens.Pill)
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(XenoShapeTokens.Pill)
                .background(SolidColor(accent))
        )
        Text(
            text = label,
            color = accent,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** Calm placeholder for an empty trail: a soft breathing aurora ring over a quiet line. */
@Composable
private fun AuditEmptyState(breath: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .drawBehind {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.minDimension / 2f
                        // Aurora halo that always ends transparent so it dissolves into the
                        // obsidian rather than cutting a hard edge.
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = XenoColors.AuroraHalo,
                                center = center,
                                radius = radius * (0.9f + 0.18f * breath)
                            ),
                            radius = radius * (0.9f + 0.18f * breath),
                            center = center,
                            alpha = 0.20f + 0.12f * breath
                        )
                    }
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "No activity yet",
                style = MaterialTheme.typography.titleMedium,
                color = XenoColors.TextSecondary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Actions the agent takes will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = XenoColors.TextTertiary
            )
        }
    }
}

/** Caps display label for one [AuditOutcome] (small caps are the chip voice). */
private fun AuditOutcome.label(): String = when (this) {
    AuditOutcome.ALLOWED_AUTO -> "AUTO"
    AuditOutcome.ALLOWED_CONFIRMED -> "ALLOWED"
    AuditOutcome.BLOCKED -> "BLOCKED"
    AuditOutcome.DENIED_BY_USER -> "DENIED"
    AuditOutcome.FAILED -> "FAILED"
    AuditOutcome.UNDONE -> "UNDONE"
}

/** Outcome hue: cyan auto-allow, green confirmed, red block/fail, amber denial, violet undo. */
private fun AuditOutcome.accent(): Color = when (this) {
    AuditOutcome.ALLOWED_AUTO -> XenoColors.AccentCyan
    AuditOutcome.ALLOWED_CONFIRMED -> XenoColors.Success
    AuditOutcome.BLOCKED -> XenoColors.Error
    AuditOutcome.DENIED_BY_USER -> XenoColors.Warning
    AuditOutcome.FAILED -> XenoColors.Error
    AuditOutcome.UNDONE -> XenoColors.AccentViolet
}

/**
 * Mode-rail hue, matching the Obsidian Aurora mode tokens used by [ModePill]: calm cyan for
 * the ASK baseline, violet for trusted AUTO, magenta for "show me first" PLAN, amber for the
 * armed BYPASS state — so a row's rail color reads the same as the live mode pill.
 */
private fun AutonomyMode.accent(): Color = when (this) {
    AutonomyMode.ASK -> XenoColors.ModeAsk
    AutonomyMode.ASK_LESS -> XenoColors.ModeAsk
    AutonomyMode.AUTO -> XenoColors.ModeAuto
    AutonomyMode.BYPASS -> XenoColors.ModeBypass
    AutonomyMode.PLAN -> XenoColors.ModePlan
}

/** Formats an epoch-millis timestamp for display in the device's locale/zone. */
private fun renderTimestamp(epochMs: Long): String {
    val formatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(epochMs))
}
