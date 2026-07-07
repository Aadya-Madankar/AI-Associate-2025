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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.permission.ActionType
import com.example.permission.AgentAction
import com.example.permission.DenyLists
import com.example.permission.RiskClassifier
import com.example.permission.RiskTier
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import com.example.ui.theme.XenoShapeTokens

/**
 * PLAN-mode preview: the full ordered list of [AgentAction]s the agent intends to run,
 * with **nothing executed** until the user explicitly approves (ARCHITECTURE.md §4.1,
 * SCOPE.md §4). This is the "plan and review before acting" surface.
 *
 * Pure presentation. The parent supplies the planned [actions] (and optionally a static
 * [tierOf] classification per action, used only to color the step badge) and handles the
 * [onRun] / [onCancel] callbacks — this screen runs no permission logic itself.
 *
 * The badge tier and the "irreversible" markers are derived from the action **TYPE** via
 * the committed [RiskClassifier], NOT from the mutable [AgentAction.reversible] flag (which
 * RiskClassifier.kt documents as caller-set and possibly stale/wrong). This keeps the
 * preview honest: a hard-BLOCKED type (DELETE_DATA, CHANGE_SECURITY_SETTING) renders red and
 * an irreversible-by-type step (SEND_MESSAGE, SEND_EMAIL, PLACE_CALL, MAKE_PURCHASE,
 * INSTALL_APP, UNINSTALL_APP…) renders at least GUARDED even if it arrived with
 * `reversible = true` by mistake.
 *
 * Styling follows the Obsidian Aurora system (DESIGN.md): an obsidian canvas under a calm,
 * slow-breathing PLAN-magenta aurora; numbered steps in hairline-bordered glass cards;
 * literal params nested in a deeper glass card; and a confident Run / Cancel bar with a
 * gentle spring press. PLAN mode owns the magenta hue (`ModePlan`); per-step risk tinting
 * promotes a guarded/blocked step to its semantic accent so nothing under-reports.
 *
 * @param actions  the ordered plan to preview. An empty list shows a placeholder.
 * @param onRun     invoked when the user approves and wants the plan executed.
 * @param onCancel  invoked when the user discards the plan.
 * @param modifier  applied to the root.
 * @param title     headline above the list.
 * @param riskClassifier the committed classifier used to derive the per-step static tier by
 *                  TYPE; supplies the honest badge/irreversible signal.
 * @param tierOf    optional per-action static risk tier for the step badge color; defaults
 *                  to the type-based static tier from [riskClassifier], so it never
 *                  under-reports a BLOCKED or irreversible-by-type step.
 */
@Composable
fun PlanPreviewScreen(
    actions: List<AgentAction>,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Plan preview",
    riskClassifier: RiskClassifier = RiskClassifier(),
    tierOf: (AgentAction) -> RiskTier = { riskClassifier.staticTier(it.type) }
) {
    // PLAN mode's signature hue (DESIGN §2/§7): one accent drives this context's aurora,
    // step numerals, and the primary action — magenta, "show me first".
    val planAccent = XenoColors.ModePlan

    // Any step that is GUARDED/BLOCKED by TYPE (or flagged irreversible) must not be
    // blanket-approved by the single "Run plan" callback without the user seeing it
    // confirmed individually — that would defeat the "literal params shown for
    // irreversible actions" guarantee. We compute this from the type-based classifier,
    // never from the mutable `reversible` flag alone. Hoisted out of composition churn.
    val hasReviewableStep = remember(actions, tierOf) {
        actions.any { action ->
            tierOf(action) != RiskTier.SAFE || action.isIrreversibleByType()
        }
    }

    // One ambient loop for the whole screen (motion budget §7): a slow breath that drifts
    // the header aurora alpha. Cheap — a single infinite transition, no recomposition churn.
    val breath by rememberInfiniteTransition(label = "plan-aurora")
        .animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.Ambient, easing = Motion.EaseAmbient),
                repeatMode = RepeatMode.Reverse
            ),
            label = "plan-breath"
        )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(XenoColors.BgBase)
            .drawBehind {
                // Soft radial PLAN aurora at the top, dissolving into the obsidian — the
                // only thing that "lives" on this otherwise quiet, negative-space canvas.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            planAccent.copy(alpha = 0.14f * breath),
                            planAccent.copy(alpha = 0.04f * breath),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.22f, size.height * 0.04f),
                        radius = size.maxDimension * 0.7f
                    )
                )
            }
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(28.dp))

        PlanModeTag(accent = planAccent)

        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = XenoColors.TextPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (actions.isEmpty()) "No steps planned yet."
            else "${actions.size} step${if (actions.size == 1) "" else "s"} — nothing runs until you approve.",
            style = MaterialTheme.typography.bodyMedium,
            color = XenoColors.TextSecondary
        )
        Spacer(Modifier.height(20.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(actions, key = { index, _ -> index }) { index, action ->
                PlanStepRow(
                    stepNumber = index + 1,
                    action = action,
                    tier = tierOf(action),
                    planAccent = planAccent
                )
            }
        }

        if (hasReviewableStep) {
            Spacer(Modifier.height(16.dp))
            ReviewNote()
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CancelButton(onClick = onCancel, modifier = Modifier.weight(1f))

            RunButton(
                onClick = onRun,
                accent = planAccent,
                // Blanket "Run plan" is only a single approval; it must never stand in for
                // the per-step confirm of an irreversible/guarded/blocked action whose
                // literal params the user has not seen. When the plan contains any such
                // step, this control is disabled — those steps route back through the
                // PermissionEngine -> ConfirmSheet path the host wires up, rather than being
                // approved in bulk here.
                enabled = actions.isNotEmpty() && !hasReviewableStep,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** The calm tracked-out "PLAN MODE" mode tag — a soft glass pill with the mode dot. */
@Composable
private fun PlanModeTag(accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(XenoShapeTokens.Pill)
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.32f), XenoShapeTokens.Pill)
            .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(XenoShapeTokens.Pill)
                .background(accent)
        )
        Text(
            text = "PLAN MODE",
            style = MaterialTheme.typography.labelMedium,
            color = accent
        )
    }
}

/** A single numbered step row in the plan list, rendered as a hairline glass card. */
@Composable
private fun PlanStepRow(
    stepNumber: Int,
    action: AgentAction,
    tier: RiskTier,
    planAccent: Color,
    modifier: Modifier = Modifier
) {
    // Risk-tinted accent: a SAFE step inherits the calm PLAN hue; a guarded/blocked step is
    // promoted to its semantic warning/error accent so nothing under-reports.
    val accent = if (tier == RiskTier.SAFE) planAccent else tier.accent()

    // Irreversibility is authoritative by TYPE: a non-SAFE static tier (which floors every
    // irreversible/outbound type at GUARDED) OR an explicit `reversible = false`. Keying off
    // the mutable `reversible` flag alone would silently present a SEND_EMAIL / UNINSTALL_APP
    // that arrived with a stale `reversible = true` as an ordinary step.
    val irreversible = tier != RiskTier.SAFE || action.isIrreversibleByType() || !action.reversible

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(XenoShapeTokens.Card)
            .background(XenoColors.Surface1)
            .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.Card)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Numbered glass badge — soft accent glow, hairline rim, accent numeral.
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(XenoShapeTokens.Pill)
                .background(accent.copy(alpha = 0.14f))
                .border(1.dp, accent.copy(alpha = 0.45f), XenoShapeTokens.Pill),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber.toString(),
                color = accent,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action.description.ifBlank { action.type.name.replace('_', ' ') },
                style = MaterialTheme.typography.titleMedium,
                color = XenoColors.TextPrimary
            )

            val subtitle = remember(action.targetApp, irreversible) {
                buildList {
                    action.targetApp?.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (irreversible) add("irreversible")
                }.joinToString("  •  ")
            }
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (irreversible) accent else XenoColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Literal params are mandatory for irreversible actions on a review/confirm
            // surface (AgentAction.params: "shown verbatim on the confirm sheet for
            // irreversible actions"). Render them inline — the same key/value pairs the
            // ConfirmSheet shows — so a SEND_MESSAGE / PLACE_CALL / MAKE_PURCHASE /
            // DELETE_DATA step's actual body, number, amount or target is visible here,
            // not just a model-supplied `description`. Sensitive values are masked in
            // lock-step with the engine's redaction (DenyLists), so nothing secret leaks.
            if (irreversible) {
                val literals = remember(action) { action.literalParamRows() }
                if (literals.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(XenoShapeTokens.CardInner)
                            .background(XenoColors.Surface2)
                            .border(1.dp, accent.copy(alpha = 0.28f), XenoShapeTokens.CardInner)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        literals.forEachIndexed { index, (label, value) ->
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
            }
        }
    }
}

/** A `LABEL / value` row rendering a literal parameter verbatim, matching the ConfirmSheet. */
@Composable
private fun LiteralRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
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

/** The calm warning note shown when the plan contains a guarded / irreversible step. */
@Composable
private fun ReviewNote(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(XenoShapeTokens.Control)
            .background(XenoColors.Warning.copy(alpha = 0.08f))
            .border(1.dp, XenoColors.Warning.copy(alpha = 0.28f), XenoShapeTokens.Control)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(7.dp)
                .clip(XenoShapeTokens.Pill)
                .background(XenoColors.Warning)
        )
        Text(
            text = "Some steps are irreversible or guarded — each is confirmed " +
                "individually before it runs, not approved in bulk here.",
            style = MaterialTheme.typography.bodySmall,
            color = XenoColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}

/** The confident, solid-accent primary action — runs the plan, with a gentle spring press. */
@Composable
private fun RunButton(
    onClick: () -> Unit,
    accent: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) Motion.PressScale else Motion.RestScale,
        animationSpec = Motion.SpringPress,
        label = "run-press"
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .heightIn(min = 54.dp)
            .scale(scale),
        shape = XenoShapeTokens.Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = XenoColors.TextOnAccent,
            disabledContainerColor = XenoColors.Surface2,
            disabledContentColor = XenoColors.TextTertiary
        )
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Run plan", style = MaterialTheme.typography.labelLarge)
    }
}

/** The quieter outlined Cancel action, with a matching spring press. */
@Composable
private fun CancelButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else Motion.RestScale,
        animationSpec = Motion.SpringPress,
        label = "cancel-press"
    )
    OutlinedButton(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier
            .heightIn(min = 54.dp)
            .scale(scale),
        shape = XenoShapeTokens.Pill,
        border = BorderStroke(1.dp, XenoColors.GlassStroke),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = XenoColors.TextSecondary)
    ) { Text("Cancel", style = MaterialTheme.typography.labelLarge) }
}

/**
 * The set of [ActionType]s that are inherently irreversible / outbound. Kept in lock-step
 * with [com.example.permission.RiskClassifier] `irreversibleTypes` and
 * [com.example.agent.GeminiToolMapper] `IRREVERSIBLE_TYPES` — the type-based floor is
 * dishonest if these disagree.
 */
private val IRREVERSIBLE_TYPES: Set<ActionType> = setOf(
    ActionType.SEND_MESSAGE,
    ActionType.SEND_EMAIL,
    ActionType.PLACE_CALL,
    ActionType.MAKE_PURCHASE,
    ActionType.DELETE_DATA,
    ActionType.UNINSTALL_APP,
    ActionType.INSTALL_APP,
    ActionType.CHANGE_SECURITY_SETTING
)

/** Type-based irreversibility, authoritative over the mutable [AgentAction.reversible] flag. */
private fun AgentAction.isIrreversibleByType(): Boolean = type in IRREVERSIBLE_TYPES

/** Masked placeholder shown in place of a sensitive value, matching the confirm sheet. */
private const val REDACTED_PLACEHOLDER = "••••••"

/**
 * Flattens [AgentAction.params] into ordered label/value rows for inline display, mirroring
 * the confirm sheet's literal rows. Sensitive keys/values are masked in lock-step with the
 * engine's redaction ([DenyLists]) so no secret (OTP, card, PIN, password) ever surfaces on
 * this preview, matching the no-leak guarantee.
 */
private fun AgentAction.literalParamRows(): List<Pair<String, String>> = buildList {
    for ((key, value) in params) {
        add(prettyParamKey(key) to redactParamValue(key, value))
    }
}

private fun prettyParamKey(key: String): String =
    key.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun redactParamValue(key: String, value: Any?): String {
    if (value == null) return "—"
    if (DenyLists.sensitiveFieldRegex.containsMatchIn(key)) return REDACTED_PLACEHOLDER
    return DenyLists.sensitiveValueRegex.replace(value.toString()) { match ->
        "•".repeat(match.value.count { !it.isWhitespace() })
    }
}

private fun RiskTier.accent(): Color = when (this) {
    RiskTier.SAFE -> XenoColors.Success
    RiskTier.GUARDED -> XenoColors.Warning
    RiskTier.BLOCKED -> XenoColors.Error
}
