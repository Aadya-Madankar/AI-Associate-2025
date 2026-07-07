package com.example.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import com.example.ui.theme.XenoShapeTokens

/**
 * One per-app or per-action rule currently held in the [com.example.permission.RuleStore],
 * flattened into a renderable row. Pure UI model — the parent builds these from the rule
 * store and handles revocation through [PermissionCenterScreen]'s callbacks.
 *
 * @param id      stable identity for list keying / revoke routing.
 * @param label   human label, e.g. an app name or "Send message · Messages".
 * @param detail  optional secondary line (scope, action type, package…).
 * @param allowed true for an allow rule, false for a deny rule.
 * @param revocable whether this rule can be lifted from this screen. Defaults to true for
 *                user-set rules backed by a store revoke path. Hard, categorical blocks sourced
 *                from `DenyLists` (banking/wallet/authenticator packages, DELETE_DATA /
 *                CHANGE_SECURITY_SETTING) have no per-rule revoke API and are ethical
 *                boundaries — they MUST be passed with `revocable = false` so they render as an
 *                immutable lock/shield badge with no revoke control wired.
 */
data class PermissionRule(
    val id: String,
    val label: String,
    val detail: String? = null,
    val allowed: Boolean,
    val revocable: Boolean = true
)

/**
 * A special / runtime permission the agent needs the OS to grant once, surfaced as a
 * deep-link CTA (ARCHITECTURE.md §5: WRITE_SETTINGS for brightness, ACCESS_NOTIFICATION_POLICY
 * for DND, accessibility enablement, etc.). Pure UI model.
 *
 * @param id        stable identity for keying.
 * @param title     what the grant unlocks, e.g. "Adjust brightness".
 * @param rationale one line on why it's needed.
 * @param granted   whether the OS has already granted it (drives the badge + CTA state).
 */
data class SpecialAccess(
    val id: String,
    val title: String,
    val rationale: String,
    val granted: Boolean
)

/**
 * The Permission Center (ARCHITECTURE.md §4.4, SCOPE.md §4): manage the per-app / per-action
 * **allow** and **deny** lists, and grant the special OS permissions the agent needs via
 * deep-link CTAs.
 *
 * Pure presentation. The parent supplies the current [rules] and [specialAccess] (read from
 * the rule store / package manager) and handles the side effects:
 *  - [onRevoke] — drop a standing rule.
 *  - [onGrantSpecialAccess] — launch the relevant `Settings.ACTION_*` deep link.
 *  - [onClearSession] — revoke all session-scoped grants (mirrors `RuleStore.revokeSession`).
 *
 * @param rules          standing allow/deny rules to display.
 * @param specialAccess  special-permission CTAs.
 * @param onRevoke       invoked with a [PermissionRule.id] to revoke that rule.
 * @param onGrantSpecialAccess invoked with a [SpecialAccess.id] to launch its grant deep link.
 * @param onClearSession invoked to clear all session-scoped grants; `null` hides the control.
 * @param modifier       applied to the root.
 */
@Composable
fun PermissionCenterScreen(
    rules: List<PermissionRule>,
    specialAccess: List<SpecialAccess>,
    onRevoke: (id: String) -> Unit,
    onGrantSpecialAccess: (id: String) -> Unit,
    modifier: Modifier = Modifier,
    onClearSession: (() -> Unit)? = null
) {
    // Partition once per recomposition where inputs change, not per row.
    val allowRules = remember(rules) { rules.filter { it.allowed } }
    val denyRules = remember(rules) { rules.filter { !it.allowed } }

    // 'Clear session' silently drops every session grant, including any session-scoped deny —
    // a protection-removing action. Guard it with an explicit confirm so it isn't an accidental tap.
    var confirmingClearSession by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(XenoColors.BgBase)
            .auroraGlow()
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(28.dp))

            // ── Header: title + supporting line + (guarded) session reset ──────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Permissions",
                        style = MaterialTheme.typography.headlineLarge,
                        color = XenoColors.TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Standing allow & deny lists and the OS access Nazim needs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = XenoColors.TextSecondary
                    )
                }
                if (onClearSession != null) {
                    Spacer(Modifier.width(8.dp))
                    SessionResetControl(
                        confirming = confirmingClearSession,
                        onArm = { confirmingClearSession = true },
                        onCancel = { confirmingClearSession = false },
                        onConfirm = {
                            confirmingClearSession = false
                            onClearSession()
                        }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (specialAccess.isNotEmpty()) {
                    item("hdr-special") {
                        SectionHeader(
                            text = "Special access",
                            accent = XenoColors.AccentCyan,
                            count = specialAccess.size
                        )
                    }
                    items(specialAccess.size, key = { "sa-${specialAccess[it].id}" }) { i ->
                        SpecialAccessRow(
                            access = specialAccess[i],
                            onGrant = { onGrantSpecialAccess(specialAccess[i].id) }
                        )
                    }
                }

                item("hdr-allow") {
                    SectionHeader(
                        text = "Allowed",
                        accent = XenoColors.AccentViolet,
                        count = allowRules.size
                    )
                }
                if (allowRules.isEmpty()) {
                    item("empty-allow") { EmptyHint("No standing allow rules.") }
                } else {
                    items(allowRules.size, key = { "allow-${allowRules[it].id}" }) { i ->
                        RuleRow(rule = allowRules[i], onRevoke = { onRevoke(allowRules[i].id) })
                    }
                }

                item("hdr-deny") {
                    SectionHeader(
                        text = "Blocked",
                        accent = XenoColors.Error,
                        count = denyRules.size
                    )
                }
                if (denyRules.isEmpty()) {
                    item("empty-deny") { EmptyHint("No app or action is on the deny list.") }
                } else {
                    items(denyRules.size, key = { "deny-${denyRules[it].id}" }) { i ->
                        RuleRow(rule = denyRules[i], onRevoke = { onRevoke(denyRules[i].id) })
                    }
                }
            }
        }
    }
}

// LazyListScope.item / items shims used above are the real Compose foundation members;
// the helper composables below render each row.

/**
 * One slow, top-anchored aurora wash that dissolves into the obsidian. A single
 * [rememberInfiniteTransition] drives a subliminal breath on the glow alpha — this is the
 * only thing that "lives" on the screen, satisfying the one-ambient-loop motion budget.
 */
@Composable
private fun Modifier.auroraGlow(): Modifier {
    val transition = rememberInfiniteTransition(label = "permission-aurora")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Ambient, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )
    return this.drawBehind {
        val glow = 0.10f + 0.05f * breath
        val radius = size.minDimension * (1.05f + 0.06f * breath)
        // Brand triad halo anchored above the header, always ending transparent.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    XenoColors.AccentViolet.copy(alpha = glow),
                    XenoColors.AccentCyan.copy(alpha = glow * 0.45f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.5f, size.height * 0.04f),
                radius = radius
            ),
            radius = radius,
            center = Offset(size.width * 0.5f, size.height * 0.04f)
        )
    }
}

/** Section divider: a tracked-out caps label with a soft accent leader dot + a count chip. */
@Composable
private fun SectionHeader(text: String, accent: Color, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(XenoShapeTokens.Pill)
                .background(accent.copy(alpha = 0.85f))
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.4f), Color.Transparent),
                            radius = size.minDimension * 1.8f
                        ),
                        radius = size.minDimension * 1.8f
                    )
                }
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = XenoColors.TextSecondary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = XenoColors.TextTertiary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(XenoShapeTokens.Card)
            .background(XenoColors.Surface1.copy(alpha = 0.5f))
            .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.Card)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = XenoColors.TextTertiary
        )
    }
}

/**
 * Two-step session reset. The cancel/confirm pair fades in over the armed action so the
 * protection-removing tap never lands by accident.
 */
@Composable
private fun SessionResetControl(
    confirming: Boolean,
    onArm: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    if (confirming) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onCancel,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = XenoColors.TextSecondary
                )
            ) { Text("Cancel", style = MaterialTheme.typography.labelLarge) }
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = XenoColors.Error
                )
            ) {
                Text(
                    "Clear all?",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    } else {
        // A quiet glass pill rather than a bare text button, to read as a control not a link.
        Box(
            modifier = Modifier
                .clip(XenoShapeTokens.Pill)
                .background(XenoColors.Surface1)
                .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.Pill)
                .pressable(onClick = onArm)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Clear session",
                style = MaterialTheme.typography.labelMedium,
                color = XenoColors.AccentCyan,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun RuleRow(
    rule: PermissionRule,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Color carries meaning: allow = success green, block = error red.
    val accent: Color = if (rule.allowed) XenoColors.Success else XenoColors.Error
    val icon: ImageVector = if (rule.allowed) Icons.Filled.CheckCircle else Icons.Filled.Block
    // Confirm guard for deny rows: lifting a block WEAKENS safety, so it must be a deliberate,
    // two-step act rather than an accidental one-tap. Allow rows keep one-tap revoke (tightening).
    var confirmingUnblock by remember(rule.id) { mutableStateOf(false) }

    GlassCard(modifier = modifier) {
        // Leading status glyph in a tinted disc — the lone solid accent hit per row.
        StatusDisc(icon = icon, tint = accent)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rule.label,
                style = MaterialTheme.typography.titleMedium,
                color = XenoColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            rule.detail?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = XenoColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            // Hard, categorical block (e.g. DenyLists-sourced): immutable from this screen.
            // Render a static lock badge and wire no revoke control.
            !rule.revocable -> LockedBadge()
            // Allow rule: removing it tightens safety — keep the one-tap revoke.
            rule.allowed -> {
                IconButton(onClick = onRevoke) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Revoke",
                        tint = XenoColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            // Revocable deny rule: require an explicit confirm before un-blocking.
            else -> {
                // Crossfade between the arm affordance and the confirm pill.
                AnimatedVisibility(
                    visible = confirmingUnblock,
                    enter = fadeIn(tween(Motion.Quick)) + scaleIn(tween(Motion.Quick), initialScale = 0.9f),
                    exit = fadeOut(tween(Motion.Quick)) + scaleOut(tween(Motion.Quick), targetScale = 0.9f)
                ) {
                    TextButton(
                        onClick = {
                            confirmingUnblock = false
                            onRevoke()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = XenoColors.Error
                        )
                    ) {
                        Text(
                            "Unblock?",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !confirmingUnblock,
                    enter = fadeIn(tween(Motion.Quick)),
                    exit = fadeOut(tween(Motion.Quick))
                ) {
                    IconButton(onClick = { confirmingUnblock = true }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove block",
                            tint = XenoColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecialAccessRow(
    access: SpecialAccess,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier) {
        // Special access reads in the cool ASK-mode cyan when pending, success green when held.
        val discTint = if (access.granted) XenoColors.Success else XenoColors.AccentCyan
        StatusDisc(icon = Icons.Filled.Lock, tint = discTint)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = access.title,
                style = MaterialTheme.typography.titleMedium,
                color = XenoColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = access.rationale,
                style = MaterialTheme.typography.labelMedium,
                color = XenoColors.TextTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        if (access.granted) {
            GrantedBadge()
        } else {
            // A small solid-accent CTA pill — the sanctioned "active control" accent hit.
            Box(
                modifier = Modifier
                    .clip(XenoShapeTokens.Pill)
                    .background(XenoColors.AccentCyan.copy(alpha = 0.14f))
                    .border(1.dp, XenoColors.AccentCyan.copy(alpha = 0.45f), XenoShapeTokens.Pill)
                    .pressable(onClick = onGrant)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Grant",
                        style = MaterialTheme.typography.labelMedium,
                        color = XenoColors.AccentCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = XenoColors.AccentCyan,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// ── Shared glass primitives ──────────────────────────────────────────────────────────────

/**
 * The standard Obsidian Aurora glass card: a [XenoColors.Surface1] fill, a 1dp hairline
 * [XenoColors.GlassStroke] edge, and a soft top-lit sheen via [XenoColors.GlassOverlay] —
 * depth from light, never shadow.
 */
@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(XenoShapeTokens.Card)
            .background(XenoColors.Surface1)
            .drawBehind {
                // Top-edge sheen so the card reads as lit glass, not a flat block.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = XenoColors.GlassOverlay,
                        startY = 0f,
                        endY = size.height * 0.5f
                    )
                )
            }
            .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.Card)
            .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** A leading status glyph seated in a soft tinted disc — the row's single accent hit. */
@Composable
private fun StatusDisc(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(XenoShapeTokens.Pill)
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.28f), XenoShapeTokens.Pill),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun LockedBadge(modifier: Modifier = Modifier) {
    StatusPillBadge(
        modifier = modifier,
        icon = Icons.Filled.Lock,
        label = "Locked",
        tint = XenoColors.Error
    )
}

@Composable
private fun GrantedBadge(modifier: Modifier = Modifier) {
    StatusPillBadge(
        modifier = modifier,
        icon = Icons.Filled.CheckCircle,
        label = "Granted",
        tint = XenoColors.Success
    )
}

/** A small tinted status pill (icon + caps label) used for immutable / granted states. */
@Composable
private fun StatusPillBadge(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(end = 6.dp)
            .clip(XenoShapeTokens.Pill)
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.35f), XenoShapeTokens.Pill)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                color = tint,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Adds a gentle [Motion.SpringPress] scale-down on press (to [Motion.PressScale]) plus a
 * clickable. Used for the bespoke glass-pill controls so they feel tactile without the
 * Material ripple fighting the obsidian surfaces.
 */
@Composable
private fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else Motion.RestScale,
        animationSpec = Motion.SpringPress,
        label = "press-scale"
    )
    return this
        .scale(scale)
        .androidxClickable(interaction, onClick)
}

/** Thin clickable wrapper that suppresses the default ripple to keep glass surfaces clean. */
@Composable
private fun Modifier.androidxClickable(
    interaction: MutableInteractionSource,
    onClick: () -> Unit
): Modifier = this.clickable(
    interactionSource = interaction,
    indication = null,
    onClick = onClick
)
