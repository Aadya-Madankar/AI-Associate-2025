package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.core.ChatMessage
import com.example.core.Sender
import com.example.ui.theme.Motion
import com.example.ui.theme.XenoColors
import com.example.ui.theme.XenoShapeTokens

// Persona label shown above Nazim's lines. The data model names the AI sender XENO;
// the persona that speaks it is Nazim. Kept local because it's copy, not a theme token.
private const val NAZIM_LABEL = "NAZIM"

/**
 * XENO: Living Presence — an elegant, minimal conversational transcript.
 *
 * The transcript is deliberately quiet: nothing competes with XENO. Lines sit in lots of
 * negative space, separated by generous breathing room, and older lines dissolve into the
 * near-black canvas through a soft top fade so only the most recent exchange has presence.
 *
 * - Nazim (the AI persona, [Sender.XENO]) lines render left-aligned: a tracked-out caps
 *   speaker label in the single accent over calm primary copy, anchored by a slim accent
 *   hairline that breathes with the one shared ambient loop — XENO quietly feeling alive.
 * - User lines render right-aligned in a barely-there frosted pill: a nested glass surface
 *   with a 1px hairline and a faint top-lit sheen. Depth from light, never shadow.
 * - New lines fade + gently rise in on [Motion.EaseEnter] (entrance replays only once per
 *   message id); the list auto-scrolls to the newest line.
 *
 * A single ambient loop drives the accent breath; no avatars, no timestamps, no clutter.
 */
@Composable
fun TranscriptOverlay(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Remember which message ids already finished their fade-in so re-composition
    // (streamed-text edits, scroll) does not replay the entrance animation.
    val seen = remember { mutableStateMapOf<String, Boolean>() }

    // The single ambient loop shared by every Nazim line: one slow accent breath. Keeping it
    // here (one infinite transition for the whole list) honors the "one ambient loop" rule.
    val ambient = rememberInfiniteTransition(label = "transcriptAmbient")
    val breath by ambient.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = Motion.Ambient, easing = Motion.EaseAmbient),
            repeatMode = RepeatMode.Reverse
        ),
        label = "transcriptBreath"
    )

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        // Soft top fade: older lines recede into the canvas instead of cutting a hard edge,
        // so attention stays on the newest exchange. drawWithContent + a dstIn mask keeps this
        // a single cheap layer pass (no per-item overdraw).
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(brush = TopFadeMask, blendMode = BlendMode.DstIn)
            },
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        items(items = messages, key = { it.id }) { message ->
            TranscriptLine(
                message = message,
                breath = breath,
                alreadySeen = seen[message.id] == true,
                onSeen = { seen[message.id] = true }
            )
        }
    }
}

// Top-only fade mask: transparent at the very top → opaque after a short distance. Used with
// BlendMode.DstIn so the list's top edge dissolves into the near-black canvas.
private val TopFadeMask: Brush = Brush.verticalGradient(
    0f to Color.Transparent,
    0.14f to Color.White,
    1f to Color.White
)

@Composable
private fun TranscriptLine(
    message: ChatMessage,
    breath: Float,
    alreadySeen: Boolean,
    onSeen: () -> Unit
) {
    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = Motion.Slow, easing = Motion.EaseEnter),
        label = "transcriptAppear"
    )
    LaunchedEffect(message.id) { onSeen() }

    val progress = if (alreadySeen) 1f else appear

    // Gentle fade + a small upward rise on entrance (graphicsLayer avoids re-layout per frame).
    val lineModifier = Modifier
        .fillMaxWidth()
        .graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 14.dp.toPx()
        }

    when (message.sender) {
        Sender.XENO -> NazimLine(text = message.text, breath = breath, modifier = lineModifier)
        Sender.USER -> UserLine(text = message.text, modifier = lineModifier)
    }
}

@Composable
private fun NazimLine(
    text: String,
    breath: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Slim single-accent hairline spanning the message height; its alpha breathes with the
        // one shared ambient loop so Nazim quietly feels present. One restrained accent — no
        // rainbow, no gradient sweep.
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(XenoShapeTokens.Pill)
                .alpha(breath)
                .background(XenoColors.PersonaAccent)
        )
        Spacer(Modifier.width(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            // Tracked-out caps persona label in the single accent — a calm "telemetry" voice.
            Text(
                text = NAZIM_LABEL,
                style = MaterialTheme.typography.labelSmall,
                color = XenoColors.PersonaAccent
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = XenoColors.TextPrimary,
                modifier = Modifier.widthIn(max = 320.dp)
            )
        }
    }
}

@Composable
private fun UserLine(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Top
    ) {
        // Barely-there frosted pill: a nested Surface2 glass over the canvas, a single hairline
        // edge, and a faint top-lit sheen layered just inside. No shadow — depth from light.
        Box(
            modifier = Modifier
                .widthIn(max = 296.dp)
                .clip(XenoShapeTokens.CardInner)
                .background(XenoColors.Surface2.copy(alpha = 0.7f))
                .background(Brush.verticalGradient(XenoColors.GlassOverlay))
                .border(
                    width = 1.dp,
                    color = XenoColors.GlassStroke,
                    shape = XenoShapeTokens.CardInner
                )
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = XenoColors.TextSecondary
            )
        }
    }
}
