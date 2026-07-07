package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.models.Persona
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import com.example.ui.theme.XenoShapeTokens

// Obsidian Aurora: a card avatar's halo radius is a fraction of the avatar size, so the
// triad wash blooms from one quadrant and dissolves before the circular edge.
private const val AvatarSizeDp = 56f

/**
 * Glass bottom sheet for choosing the active companion persona.
 *
 * Presents a horizontal snap row of persona cards; each card carries a halo hue
 * derived from its own [Persona.primaryColors]. Selecting a card invokes
 * [onSelect]; dismissing the sheet (scrim/back/drag-down) invokes [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaSwitcher(
    personas: List<Persona>,
    selectedId: String,
    onSelect: (Persona) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // One ambient breath drives every selected halo in the sheet — a single shared
    // transition keeps the motion budget to one loop (per the design system).
    val breath = rememberInfiniteTransition(label = "personaBreath")
    val breathPulse by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Ambient, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathPulse"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = XenoColors.BgRaised,
        contentColor = XenoColors.TextPrimary,
        scrimColor = XenoColors.Scrim,
        shape = XenoShapeTokens.Sheet,
        dragHandle = { SheetHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Choose your companion",
                color = XenoColors.TextPrimary,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Each presence carries its own voice and aura",
                color = XenoColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp)
            )
            Spacer(Modifier.height(22.dp))
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(items = personas, key = { it.id }) { persona ->
                    PersonaCard(
                        persona = persona,
                        selected = persona.id == selectedId,
                        breathPulse = breathPulse,
                        onClick = { onSelect(persona) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(XenoShapeTokens.Pill)
                .background(XenoColors.GlassStrokeStrong)
        )
    }
}

@Composable
private fun PersonaCard(
    persona: Persona,
    selected: Boolean,
    breathPulse: Float,
    onClick: () -> Unit
) {
    val haloHue = persona.primaryColors.firstOrNull() ?: XenoColors.AccentViolet
    val secondaryHue = persona.primaryColors.getOrNull(1) ?: XenoColors.AccentSolid

    // Selection reads as a brighter top-lit glass edge tinted toward the persona hue;
    // unselected cards keep a quiet hairline so nothing competes for attention.
    val cardBorder: Brush = remember(selected, haloHue, secondaryHue) {
        if (selected) {
            Brush.verticalGradient(
                listOf(
                    haloHue.copy(alpha = 0.85f),
                    secondaryHue.copy(alpha = 0.45f)
                )
            )
        } else {
            SolidColor(XenoColors.GlassStroke)
        }
    }

    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .width(154.dp)
            .clip(XenoShapeTokens.Card)
            .background(if (selected) XenoColors.Surface2 else XenoColors.Surface1)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                brush = cardBorder,
                shape = XenoShapeTokens.Card
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.Start
    ) {
        PersonaAvatar(
            label = persona.name.take(1).uppercase(),
            haloHue = haloHue,
            secondaryHue = secondaryHue,
            selected = selected,
            breathPulse = breathPulse
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = persona.name,
            color = XenoColors.TextPrimary,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = persona.title,
            color = XenoColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = persona.description,
            color = XenoColors.TextTertiary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (selected) {
            Spacer(Modifier.height(14.dp))
            SelectedChip(haloHue = haloHue)
        }
    }
}

@Composable
private fun PersonaAvatar(
    label: String,
    haloHue: Color,
    secondaryHue: Color,
    selected: Boolean,
    breathPulse: Float
) {
    // The avatar sits on quiet glass; only the selected persona's halo "breathes",
    // honoring the one-ambient-loop motion budget.
    val haloAlpha = if (selected) 0.30f + 0.22f * breathPulse else 0f

    Box(
        modifier = Modifier.size(AvatarSizeDp.dp),
        contentAlignment = Alignment.Center
    ) {
        // Soft persona-tinted aurora bloom behind the avatar (dissolves to transparent).
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(haloAlpha)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                haloHue,
                                secondaryHue.copy(alpha = 0.4f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            haloHue.copy(alpha = if (selected) 0.95f else 0.55f),
                            secondaryHue.copy(alpha = if (selected) 0.65f else 0.30f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = XenoColors.GlassStrokeStrong,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = XenoColors.TextOnAccent,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SelectedChip(haloHue: Color) {
    Row(
        modifier = Modifier
            .clip(XenoShapeTokens.Pill)
            .background(haloHue.copy(alpha = 0.14f))
            .border(
                width = 1.dp,
                color = haloHue.copy(alpha = 0.30f),
                shape = XenoShapeTokens.Pill
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(haloHue)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "ACTIVE",
            color = haloHue,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
