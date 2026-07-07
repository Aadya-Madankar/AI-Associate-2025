package com.example.ui.agent

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import com.example.ui.theme.XenoShapeTokens

/**
 * First-run **prominent disclosure + affirmative consent** screen (ARCHITECTURE.md §8,
 * SCOPE.md §6). Google Play / privacy policy require this to be shown **in-app**, in normal
 * usage, with an explicit affirmative action — never buried in a settings page — before the
 * agent may read the screen or drive other apps.
 *
 * It plainly discloses what the agent can do, what it will never touch (banking / passwords /
 * OTP / secure screens), and that screen contents may be sent to the model with sensitive
 * fields redacted on-device. The user must tick the consent control before [onConsent]
 * becomes enabled; a separate CTA deep-links to Accessibility settings to enable the service.
 *
 * Pure presentation: the parent owns the [consentChecked] state and decides what each
 * callback does (persist consent, launch the accessibility settings intent, dismiss).
 *
 * Styling follows the Obsidian Aurora system (DESIGN.md): a calm full-bleed obsidian canvas
 * lit by a single slow-breathing brand aurora behind the title (the only thing that "lives"),
 * tracked-out caps eyebrow, a tight type hierarchy, glass disclosure cards each carrying one
 * restrained accent as a soft glow, a glass affirmative-consent control, and a confident solid
 * primary CTA with a gentle spring press. Depth comes from hairline borders + light, not shadow.
 *
 * @param consentChecked        whether the affirmative-consent box is ticked.
 * @param onConsentCheckedChange toggles the consent box.
 * @param accessibilityEnabled  whether the AccessibilityService is already enabled (drives
 *                              the CTA label + the primary button's enablement).
 * @param onEnableAccessibility invoked to deep-link to `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
 * @param onConsent             invoked when the user affirmatively continues; only callable
 *                              once [consentChecked] is true.
 * @param onDecline             invoked when the user declines; `null` hides the decline control.
 * @param modifier              applied to the root.
 */
@Composable
fun OnboardingConsentScreen(
    consentChecked: Boolean,
    onConsentCheckedChange: (Boolean) -> Unit,
    accessibilityEnabled: Boolean,
    onEnableAccessibility: () -> Unit,
    onConsent: () -> Unit,
    modifier: Modifier = Modifier,
    onDecline: (() -> Unit)? = null
) {
    // One ambient loop for the whole screen (motion budget, DESIGN §7): a slow breath that
    // drifts the title aurora's alpha + reach. Cheap — a single infinite transition feeds it,
    // all per-frame work is plain math inside one drawBehind.
    val breath by rememberInfiniteTransition(label = "onboarding-aurora")
        .animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.Ambient, easing = Motion.EaseAmbient),
                repeatMode = RepeatMode.Reverse
            ),
            label = "onboarding-breath"
        )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(XenoColors.BgBase)
            // Signature brand aurora behind the title region — cyan→violet→magenta dissolving
            // into the obsidian. The persona/brand glow; the one element that breathes.
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            XenoColors.AccentViolet.copy(alpha = 0.16f * breath),
                            XenoColors.AccentCyan.copy(alpha = 0.08f * breath),
                            XenoColors.AccentMagenta.copy(alpha = 0.04f * breath),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.24f, size.height * 0.12f),
                        radius = size.maxDimension * (0.62f + 0.05f * breath)
                    )
                )
            }
            .padding(horizontal = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(48.dp))

            // Tracked-out caps eyebrow — the calm "system telemetry" voice (DESIGN §3).
            Text(
                text = "FIRST RUN",
                style = MaterialTheme.typography.labelSmall,
                color = XenoColors.AccentViolet
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Let Nazim operate your phone",
                style = MaterialTheme.typography.displayMedium,
                color = XenoColors.TextPrimary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "With your permission, Nazim can run hands-free tasks on this device — " +
                    "opening apps, typing, toggling settings, and multi-step actions you ask for.",
                style = MaterialTheme.typography.bodyLarge,
                color = XenoColors.TextSecondary
            )

            Spacer(Modifier.height(32.dp))

            DisclosureItem(
                icon = Icons.Filled.Visibility,
                tint = XenoColors.AccentCyan,
                title = "Reads the current screen",
                body = "To act, Nazim reads on-screen text and controls via Android's " +
                    "Accessibility service. Screen contents may be sent to the AI model, with " +
                    "passwords, OTPs, and card numbers redacted on-device first."
            )
            Spacer(Modifier.height(12.dp))
            DisclosureItem(
                icon = Icons.Filled.Shield,
                tint = XenoColors.AccentViolet,
                title = "You're always in control",
                body = "Every action is permission-gated. You confirm anything risky, can watch " +
                    "a live activity log, and a STOP button halts everything instantly."
            )
            Spacer(Modifier.height(12.dp))
            DisclosureItem(
                icon = Icons.Filled.Lock,
                tint = XenoColors.Error,
                title = "Never touches sensitive things",
                body = "Banking, payment and authenticator apps, password and OTP fields, and any " +
                    "secured screen are blocked by default. They are never automated silently — " +
                    "in Bypass mode they still require an explicit per-action confirmation."
            )

            Spacer(Modifier.height(28.dp))

            // Affirmative consent — a tappable glass control that lights with the brand accent
            // when ticked. The whole row toggles, so the target is generous and trustworthy.
            ConsentControl(
                checked = consentChecked,
                onCheckedChange = onConsentCheckedChange
            )

            Spacer(Modifier.height(16.dp))

            // Accessibility enable CTA — a quiet glass outlined pill that resolves to a calm
            // Success state once the service is on.
            AccessibilityCta(
                enabled = accessibilityEnabled,
                onClick = onEnableAccessibility
            )

            Spacer(Modifier.height(28.dp))
        }

        // Sticky footer actions, settled into the obsidian below the scroll.
        Column(modifier = Modifier.fillMaxWidth()) {
            ContinueButton(
                enabled = consentChecked,
                onClick = onConsent
            )

            if (onDecline != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth(),
                    shape = XenoShapeTokens.Pill,
                    colors = ButtonDefaults.textButtonColors(contentColor = XenoColors.TextSecondary)
                ) { Text("Not now", style = MaterialTheme.typography.labelLarge) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * A disclosure bullet rendered as a glass card: a tinted icon tile + title + body. Each card
 * carries exactly one accent (DESIGN §7 "one accent per context"), used as a soft glow on the
 * icon tile rather than a loud fill.
 */
@Composable
private fun DisclosureItem(
    icon: ImageVector,
    tint: Color,
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(XenoShapeTokens.Card)
            .background(XenoColors.Surface1)
            .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.Card)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        IconTile(icon = icon, tint = tint)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = XenoColors.TextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = XenoColors.TextSecondary
            )
        }
    }
}

/** A rounded glass tile holding an accent-tinted icon with a faint inner glow of its own hue. */
@Composable
private fun IconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(XenoShapeTokens.CardInner)
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.28f), XenoShapeTokens.CardInner),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * The affirmative-consent control: a full-width glass row that toggles on tap. The leading
 * box and the row's border light with the solid brand accent when ticked, giving a confident
 * "armed" read without a loud fill.
 */
@Composable
private fun ConsentControl(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = XenoColors.AccentSolid
    val borderColor by animateColorAsState(
        targetValue = if (checked) accent.copy(alpha = 0.55f) else XenoColors.GlassStroke,
        animationSpec = tween(Motion.Base, easing = Motion.EaseStandard),
        label = "consent-border"
    )
    val boxFill by animateColorAsState(
        targetValue = if (checked) accent else Color.Transparent,
        animationSpec = tween(Motion.Quick, easing = Motion.EaseStandard),
        label = "consent-box-fill"
    )
    val boxStroke by animateColorAsState(
        targetValue = if (checked) accent else XenoColors.TextTertiary,
        animationSpec = tween(Motion.Quick, easing = Motion.EaseStandard),
        label = "consent-box-stroke"
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else Motion.RestScale,
        animationSpec = Motion.SpringPress,
        label = "consent-press"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(XenoShapeTokens.Control)
            .background(XenoColors.Surface1)
            .border(1.dp, borderColor, XenoShapeTokens.Control)
            .clickableNoRipple(interaction) { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(ConsentBoxShape)
                .background(boxFill)
                .border(1.5.dp, boxStroke, ConsentBoxShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = XenoColors.TextOnAccent,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = "I understand and agree that Nazim may read this screen and control apps " +
                "on my behalf, only for tasks I ask for, under the limits above.",
            style = MaterialTheme.typography.bodyMedium,
            color = XenoColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The "enable accessibility" CTA — a glass outlined pill. While the service is off it reads in
 * the calm cyan ("ASK / safe baseline") voice; once enabled it resolves to a Success-tinted
 * confirmed state with a check.
 */
@Composable
private fun AccessibilityCta(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (enabled) XenoColors.Success else XenoColors.AccentCyan

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressScale else Motion.RestScale,
        animationSpec = Motion.SpringPress,
        label = "a11y-press"
    )

    OutlinedButton(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .scale(scale),
        shape = XenoShapeTokens.Pill,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = accent.copy(alpha = 0.08f),
            contentColor = accent
        )
    ) {
        Icon(
            imageVector = if (enabled) Icons.Filled.CheckCircle else Icons.Filled.Accessibility,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (enabled) "Accessibility enabled" else "Enable accessibility",
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * The prominent footer primary action: a full-width solid-accent pill, gated on consent, with a
 * gentle spring press. When disabled it falls back to a quiet glass surface so it reads as
 * "not yet" rather than broken.
 */
@Composable
private fun ContinueButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) Motion.PressScale else Motion.RestScale,
        animationSpec = Motion.SpringPress,
        label = "continue-press"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .scale(scale),
        shape = XenoShapeTokens.Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = XenoColors.AccentSolid,
            contentColor = XenoColors.TextOnAccent,
            disabledContainerColor = XenoColors.Surface2,
            disabledContentColor = XenoColors.TextTertiary
        )
    ) {
        Text(
            text = "Agree and continue",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * The small consent check box. No semantic token exists for a sub-control radius this tight,
 * so it's a local constant — a soft 7dp square that reads as a checkbox, not a card.
 */
private val ConsentBoxShape = RoundedCornerShape(7.dp)

/**
 * Whole-surface click with no ripple, wired to a shared [interaction] source so press-scale
 * feedback comes from the spring instead. Keeps the glass consent row tappable across its full
 * width without a Material ripple fighting the obsidian aesthetic.
 */
private fun Modifier.clickableNoRipple(
    interaction: MutableInteractionSource,
    onClick: () -> Unit
): Modifier = this.then(
    Modifier.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick
    )
)
