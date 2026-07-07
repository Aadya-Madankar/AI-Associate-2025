package com.example.ui.agent

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import com.example.ui.theme.XenoShapeTokens

/**
 * Clean sheet to manage XENO's Gemini API keys, entered in-app and stored on the phone.
 *
 * XENO uses the keys in order and automatically fails over to the next one if a connection
 * errors (e.g. a key hits its Live-API quota), so adding a couple of keys keeps voice reliable.
 *
 * Styling follows XENO: Living Presence (DESIGN.md): a 26dp-top frosted sheet over a deep
 * scrim, ONE calm ambient presence glow that breathes slowly behind the header (the single
 * accent — [XenoColors.AccentSolid], never a rainbow), generous negative space, hairline
 * borders and a soft top-lit sheen. Keys live in quiet nested glass rows; the primary "Add"
 * action carries a gentle spring press. Only the key icon and the add action wear the accent —
 * everything else recedes so XENO stays the hero.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysSheet(
    keys: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var input by remember { mutableStateOf("") }

    // ONE ambient loop for the whole sheet (motion budget): a slow breath that drifts the
    // single presence glow's alpha behind the header. Cheap — a single infinite transition,
    // no recomposition churn — and the only animated background element on the sheet.
    val breath by rememberInfiniteTransition(label = "keys-presence")
        .animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.Ambient, easing = Motion.EaseAmbient),
                repeatMode = RepeatMode.Reverse
            ),
            label = "keys-breath"
        )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = XenoShapeTokens.Sheet,
        containerColor = XenoColors.BgRaised,
        contentColor = XenoColors.TextPrimary,
        scrimColor = XenoColors.Scrim
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // --- Header: the single ambient presence glow + title + lede ----------
            // A frosted pane lit from the top edge; behind it, ONE slow-breathing accent
            // halo dissolves into the canvas. Only the key icon wears the accent.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(XenoShapeTokens.Card)
                    .background(XenoColors.Surface1)
                    .drawBehind {
                        // The single presence glow — one calm accent radiating from the top
                        // and dissolving fully into the frosted surface.
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
                    .padding(horizontal = 22.dp, vertical = 22.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // The single accent — a quiet hairline glass tile holding the key icon.
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(XenoShapeTokens.Control)
                                .background(XenoColors.AccentSolid.copy(alpha = 0.12f))
                                .border(1.dp, XenoColors.AccentSolid.copy(alpha = 0.30f), XenoShapeTokens.Control),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Key,
                                contentDescription = null,
                                tint = XenoColors.AccentSolid,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = "API keys",
                            style = MaterialTheme.typography.titleLarge,
                            color = XenoColors.TextPrimary
                        )
                    }
                    Text(
                        text = "Paste one or more Gemini API keys. XENO uses them in order and " +
                            "switches to the next automatically if one fails or hits its quota. " +
                            "Keys stay on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = XenoColors.TextSecondary
                    )
                }
            }

            // --- Add a key: hairline input + gentle-spring primary action ----------
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text("Paste a Gemini API key") },
                    shape = XenoShapeTokens.Control,
                    keyboardActions = KeyboardActions(
                        onDone = { if (input.isNotBlank()) { onAdd(input.trim()); input = "" } }
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = XenoColors.AccentSolid,
                        unfocusedBorderColor = XenoColors.GlassStroke,
                        cursorColor = XenoColors.AccentSolid,
                        focusedTextColor = XenoColors.TextPrimary,
                        unfocusedTextColor = XenoColors.TextPrimary,
                        focusedContainerColor = XenoColors.Surface1,
                        unfocusedContainerColor = XenoColors.Surface1,
                        focusedLabelColor = XenoColors.AccentSolid,
                        unfocusedLabelColor = XenoColors.TextTertiary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                AddKeyAction(
                    enabled = input.isNotBlank(),
                    onClick = { if (input.isNotBlank()) { onAdd(input.trim()); input = "" } }
                )
            }

            // --- Stored keys: empty hint, or a quiet nested glass list -------------
            if (keys.isEmpty()) {
                EmptyKeysNote()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "${keys.size} KEY${if (keys.size == 1) "" else "S"} · USED TOP TO BOTTOM",
                        style = MaterialTheme.typography.labelMedium,
                        color = XenoColors.TextTertiary
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = (keys.size.coerceAtMost(5) * 64).dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(keys, key = { it }) { key ->
                            KeyRow(masked = maskKey(key), onRemove = { onRemove(key) })
                        }
                    }
                }
            }
        }
    }
}

/** The prominent, full-width "Add key" action — solid accent fill, gentle spring press. */
@Composable
private fun AddKeyAction(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) Motion.PressScale else Motion.RestScale,
        animationSpec = Motion.SpringPress,
        label = "add-key-press"
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .scale(scale),
        shape = XenoShapeTokens.Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = XenoColors.AccentSolid,
            contentColor = XenoColors.TextOnAccent,
            disabledContainerColor = XenoColors.Surface2,
            disabledContentColor = XenoColors.TextTertiary
        )
    ) {
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text("Add key", style = MaterialTheme.typography.labelLarge)
    }
}

/** A single stored key — masked value in a quiet nested glass row with a muted remove control. */
@Composable
private fun KeyRow(
    masked: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(XenoShapeTokens.Control)
            .background(XenoColors.Surface2)
            .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.Control)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // The single accent dot — XENO's one elegant accent, used sparingly.
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(XenoShapeTokens.Pill)
                .background(XenoColors.AccentSolid)
        )
        Text(
            text = masked,
            style = MaterialTheme.typography.bodyMedium,
            color = XenoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "Remove key",
                tint = XenoColors.Error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Calm hint shown when no keys have been added yet — a quiet hairline glass note. */
@Composable
private fun EmptyKeysNote(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(XenoShapeTokens.CardInner)
            .background(XenoColors.Surface1)
            .border(1.dp, XenoColors.GlassStroke, XenoShapeTokens.CardInner)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(
            text = "No keys added yet — XENO will use the build-time key if one is bundled.",
            style = MaterialTheme.typography.bodySmall,
            color = XenoColors.TextTertiary
        )
    }
}

private fun maskKey(k: String): String =
    if (k.length <= 12) k else k.take(7) + "…" + k.takeLast(4)
