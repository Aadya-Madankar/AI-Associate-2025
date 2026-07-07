package com.example.character

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.example.models.Persona

/**
 * Photoreal Nazim from a single portrait render (`assets/nazim_portrait.png`) — the easiest way
 * to put a real MetaHuman on screen WITHOUT the full GLB pipeline: render a front portrait of the
 * MetaHuman and drop it in. The still image is given life with an aurora halo, a slow breathing
 * scale, and an amplitude-driven presence so it reads as alive rather than a static photo.
 *
 * If the asset is missing it degrades to the procedural [NazimFallbackPortrait]. For TRUE facial
 * lip-sync + body animation, use the animated GLB path ([NazimView] → NazimRenderer); see
 * NAZIM_ASSETS.md.
 */
@Composable
fun NazimPortraitAvatar(
    state: NazimState,
    amplitude: Float,
    persona: Persona,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val image: ImageBitmap? = remember {
        runCatching {
            context.assets.open(NazimAssets.PORTRAIT_ASSET).use { stream ->
                BitmapFactory.decodeStream(stream)?.asImageBitmap()
            }
        }.getOrNull()
    }

    if (image == null) {
        NazimFallbackPortrait(state = state, amplitude = amplitude, persona = persona, modifier = modifier)
        return
    }

    val breathe = rememberInfiniteTransition(label = "nazimBreathe")
    val breath by breathe.animateFloat(
        initialValue = 0.995f,
        targetValue = 1.012f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "nazimBreathScale"
    )
    // Speaking/acting gives a touch more presence with the live audio amplitude.
    val active = state == NazimState.SPEAKING || state == NazimState.ACTING
    val presence = breath + if (active) amplitude * 0.02f else 0f

    val haloColor = persona.primaryColors.firstOrNull() ?: Color(0xFF9C27B0)
    val haloStrength = 0.18f + amplitude * 0.22f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(haloColor.copy(alpha = haloStrength), Color.Transparent)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = image,
            contentDescription = "Nazim",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .scale(presence)
        )
    }
}
