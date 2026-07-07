package com.example.ui.agent

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.permission.ConfirmRequest
import com.example.permission.RiskTier
import com.example.permission.RuleScope
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import com.example.ui.theme.XenoShapeTokens

/**
 * Bottom-sheet confirmation surface for a single agent action awaiting the user's
 * permission decision (ARCHITECTURE.md §4.4).
 *
 * Rendered when the [com.example.permission.PermissionEngine] returns
 * [com.example.permission.PermissionDecision.Confirm]. It shows the action verb /
 * [ConfirmRequest.title], the target app, the **literal** parameters
 * ([ConfirmRequest.literalDetails] — the actual message text, number, amount, etc., which
 * are mandatory for irreversible actions), and a [RiskTier] badge. The user resolves it
 * through one of three controls that map onto a [RuleScope]:
 *
 *  - **Allow once** → `onDecision(RuleScope.ONCE, allowed = true)`
 *  - **Always allow** → `onDecision(<first non-ONCE offered scope>, allowed = true)` —
 *    only rendered when the engine actually offered a non-ONCE scope; never fabricated.
 *  - **Deny** → `onDecision(null, allowed = false)`
 *
 * For [RiskTier.BLOCKED] requests (reachable only in BYPASS), Deny is the prominent default,
 * "Always allow" is never shown, and "Allow once" stays disabled until the user ticks an
 * explicit acknowledgement.
 *
 * This composable is **pure**: it owns no business logic, performs no permission checks,
 * and simply renders state and emits callbacks. In production it is hosted over a
 * `TYPE_ACCESSIBILITY_OVERLAY` window so it floats above the target app and the dispatch
 * path cannot defeat it; here it is a plain [ModalBottomSheet] driven by the caller.
 *
 * Styling follows XENO: Living Presence (DESIGN.md): a 26dp-top frosted sheet over a deep
 * scrim, ONE calm ambient presence glow that breathes slowly behind the header (the single
 * accent — never a risk-coloured rainbow), generous negative space, hairline borders and a
 * soft top-lit sheen, literal values in a quiet nested glass card, and well-spaced pill
 * actions with a gentle spring press. The risk tier reads through a single muted semantic
 * badge, not by recolouring the whole surface.
 *
 * @param request      what to render; `null` dismisses the sheet (renders nothing).
 * @param onDecision   the user's choice — `(scope, allowed)`. `scope` is the [RuleScope]
 *                     to persist when allowed, or `null` on deny / a transient dismiss.
 * @param modifier     applied to the sheet content column.
 * @param sheetState   the bottom-sheet state; defaults to a fresh
 *                     [rememberModalBottomSheetState].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    request: ConfirmRequest?,
    onDecision: (scope: RuleScope?, allowed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    if (request == null) return

    // The standing-grant scope offered alongside "Allow once": strictly the first non-ONCE
    // scope the engine actually offered. NEVER fabricate one — when the engine offers only
    // ONCE (every irreversible / forced-ask action and every BLOCKED tier), this is null and
    // the "Always allow" control is not rendered, so a single confirm can never become a
    // standing grant.
    val alwaysScope: RuleScope? = request.offeredScopes
        .firstOrNull { it != RuleScope.ONCE }

    // BLOCKED tier is only reachable in BYPASS and demands an extra explicit acknowledgement
    // before "Allow once" is enabled, so a single accidental tap can never commit a
    // hard-blocked / secure-screen override.
    val requiresAck: Boolean = request.tier == RiskTier.BLOCKED
    var acknowledged by remember(request) { mutableStateOf(false) }

    // The risk tier's muted semantic hue. It tints ONLY the small badge / warning — never the
    // whole surface. XENO's single accent ([AccentSolid]) carries the presence glow instead,
    // so the sheet reads calm, not risk-coloured (DESIGN §"One accent").
    val riskHue: Color = riskTint(request.tier)

    // ONE ambient loop for the whole sheet (motion budget): a slow breath that drifts the
    // single presence glow's alpha. Cheap — a single infinite transition, no recomposition
    // churn — and it is the only animated background element on the sheet.
    val breath by rememberInfiniteTransition(label = "confirm-presence")
        .animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.Ambient, easing = Motion.EaseAmbient),
                repeatMode = RepeatMode.Reverse
            ),
            label = "confirm-breath"
        )

    ModalBottomSheet(
        // A transient swipe-dismiss / scrim tap is treated as a Deny — fail closed.
        onDismissRequest = { onDecision(null, false) },
        sheetState = sheetState,
        shape = XenoShapeTokens.Sheet,
        containerColor = XenoColors.BgRaised,
        contentColor = XenoColors.TextPrimary,
        scrimColor = XenoColors.Scrim
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // --- Header: the single ambient presence glow + bold verb -------------
            // A frosted pane lit from the top edge; behind it, ONE slow-breathing accent
            // halo dissolves into the canvas. The tier is a quiet badge, not a tint.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(XenoShapeTokens.Card)
                    .background(XenoColors.Surface1)
                    .drawBehind {
                        // The single presence glow — one calm accent radiating from the top
                        // and dissolving fully into the frosted surface. Not risk-coloured.
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    XenoColors.AccentSolid.copy(alpha = 0.16f * breath),
                                    XenoColors.AccentSolid.copy(alpha = 0.05f * breath),
                                    Color.Transparent
                                ),
                                center = Offset(size.width * 0.20f, size.height * 0.10f),
                                radius = size.maxDimension * 1.05f
                            )
                        )
                        // Soft top-lit inner sheen — depth from light, never shadow.
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = XenoColors.GlassOverlay,
                                startY = 0f,
                                endY = size.height * 0.55f
                            )
                        )
                    }
                    .border(1.dp, XenoColors.GlassStrokeStrong, XenoShapeTokens.Card)
                    .padding(horizontal = 22.dp, vertical = 22.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RiskBadge(tier = request.tier, hue = riskHue)

                    Text(
                        text = request.title.ifBlank { "Confirm action" },
                        style = MaterialTheme.typography.headlineMedium,
                        color = XenoColors.TextPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    request.action.targetApp?.takeIf { it.isNotBlank() }?.let { app ->
                        AppChip(app = app)
                    }
                }
            }

            // --- Literal parameter rows (the verbatim values being committed) ----
            if (request.literalDetails.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(XenoShapeTokens.CardInner)
                        .background(XenoColors.Surface2)
                        .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.CardInner)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    request.literalDetails.forEachIndexed { index, (label, value) ->
                        if (index > 0) {
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(XenoColors.GlassStroke)
                            )
                        }
                        LiteralRow(label = label, value = value)
                    }
                }
            }

            // Surface the secure / irreversibility warning when the action can't be undone OR
            // whenever the tier is BLOCKED (a secure screen / hard-blocked override), even if
            // the action is nominally reversible.
            if (!request.action.reversible || requiresAck) {
                WarningNote(
                    text = if (requiresAck) {
                        "This is a blocked or secure screen. Proceeding overrides a hard safety block and can't be undone."
                    } else {
                        "This action can't be undone."
                    },
                    tint = if (requiresAck) XenoColors.Error else XenoColors.Warning
                )
            }

            // For BLOCKED requests require an explicit acknowledgement before allowing.
            if (requiresAck) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(XenoShapeTokens.Control)
                        .background(XenoColors.Error.copy(alpha = 0.08f))
                        .border(1.dp, XenoColors.Error.copy(alpha = 0.32f), XenoShapeTokens.Control)
                        .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = XenoColors.Error,
                            uncheckedColor = XenoColors.Error,
                            checkmarkColor = XenoColors.TextOnAccent
                        )
                    )
                    Text(
                        text = "I understand this is a blocked or secure screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = XenoColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // --- Decision controls -----------------------------------------------
            // For BLOCKED requests, make Deny the prominent default and de-emphasize Allow,
            // and gate Allow on the explicit acknowledgement.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (requiresAck) {
                    PrimaryAction(
                        label = "Deny",
                        container = XenoColors.AccentSolid,
                        content = XenoColors.TextOnAccent,
                        onClick = { onDecision(null, false) }
                    )
                    SecondaryAction(
                        label = "Allow once",
                        content = XenoColors.Error,
                        enabled = acknowledged,
                        onClick = { onDecision(RuleScope.ONCE, true) }
                    )
                } else {
                    PrimaryAction(
                        label = "Allow once",
                        container = XenoColors.AccentSolid,
                        content = XenoColors.TextOnAccent,
                        onClick = { onDecision(RuleScope.ONCE, true) }
                    )

                    // The "Always allow" control only appears when the engine actually offered
                    // a standing scope; it can only ever emit a scope the engine sanctioned.
                    if (alwaysScope != null) {
                        SecondaryAction(
                            label = alwaysScope.allowLabel(),
                            content = XenoColors.AccentSolid,
                            onClick = { onDecision(alwaysScope, true) }
                        )
                    }

                    TextButton(
                        onClick = { onDecision(null, false) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = XenoShapeTokens.Pill,
                        colors = ButtonDefaults.textButtonColors(contentColor = XenoColors.TextSecondary)
                    ) { Text("Deny", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

/** A small horizontal pill + icon communicating the action's [RiskTier], in a muted hue. */
@Composable
private fun RiskBadge(tier: RiskTier, hue: Color, modifier: Modifier = Modifier) {
    val (icon: ImageVector, label: String) = when (tier) {
        RiskTier.SAFE -> Icons.Filled.CheckCircle to "SAFE"
        RiskTier.GUARDED -> Icons.Filled.Warning to "GUARDED"
        RiskTier.BLOCKED -> Icons.Filled.Block to "BLOCKED"
    }
    Row(
        modifier = modifier
            .clip(XenoShapeTokens.Pill)
            .background(hue.copy(alpha = 0.10f))
            .border(1.dp, hue.copy(alpha = 0.32f), XenoShapeTokens.Pill)
            .padding(start = 11.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = hue, modifier = Modifier.size(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = hue
        )
    }
}

/** The target-app glass chip rendered under the verb — a quiet, neutral hairline pill. */
@Composable
private fun AppChip(app: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(XenoShapeTokens.Pill)
            .background(XenoColors.Surface2)
            .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.Pill)
            .padding(start = 11.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // The single accent dot — XENO's one elegant accent, used sparingly.
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(XenoShapeTokens.Pill)
                .background(XenoColors.AccentSolid)
        )
        Text(
            text = app,
            style = MaterialTheme.typography.labelMedium,
            color = XenoColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A `label / value` row rendering a literal parameter verbatim. */
@Composable
private fun LiteralRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = XenoColors.TextTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = XenoColors.TextPrimary
        )
    }
}

/** A calm, tinted irreversibility / secure-screen note. */
@Composable
private fun WarningNote(text: String, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(XenoShapeTokens.Control)
            .background(tint.copy(alpha = 0.07f))
            .border(1.dp, tint.copy(alpha = 0.24f), XenoShapeTokens.Control)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = XenoColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}

/** The prominent, full-width primary action (solid accent fill, gentle spring press). */
@Composable
private fun PrimaryAction(
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else Motion.RestScale,
        animationSpec = Motion.SpringPress,
        label = "primary-press"
    )
    Button(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .scale(scale),
        shape = XenoShapeTokens.Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content
        )
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

/** A quieter outlined action; the hairline outline + label pick up the supplied content hue. */
@Composable
private fun SecondaryAction(
    label: String,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) Motion.PressScale else Motion.RestScale,
        animationSpec = Motion.SpringPress,
        label = "secondary-press"
    )
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .scale(scale),
        shape = XenoShapeTokens.Pill,
        border = BorderStroke(
            1.dp,
            if (enabled) content.copy(alpha = 0.38f) else XenoColors.GlassStroke
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = content,
            disabledContentColor = XenoColors.TextTertiary
        )
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

/** The single muted semantic hue that tints a tier's badge and warning (never the surface). */
private fun riskTint(tier: RiskTier): Color = when (tier) {
    RiskTier.SAFE -> XenoColors.Success
    RiskTier.GUARDED -> XenoColors.Warning
    RiskTier.BLOCKED -> XenoColors.Error
}

/** The "Always allow…" button copy that makes the persisted [RuleScope] explicit. */
private fun RuleScope.allowLabel(): String = when (this) {
    RuleScope.ONCE -> "Allow once"
    RuleScope.SESSION -> "Always allow (this session)"
    RuleScope.ALWAYS_THIS_ACTION_AND_APP -> "Always allow (this action + app)"
    RuleScope.THIS_APP_SESSION -> "Always allow (this app, this session)"
}
