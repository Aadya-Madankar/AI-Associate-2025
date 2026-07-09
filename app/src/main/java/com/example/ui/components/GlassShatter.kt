package com.example.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieAnimatable
import com.airbnb.lottie.compose.rememberLottieComposition

/**
 * The **glass-shatter motion graphic** played once when XENO breaks out of the app to roam.
 *
 * This plays a real Lottie (After Effects) animation bundled at `assets/glass_break.json` — drop
 * your chosen glass-break animation there (LottieFiles / IconScout export, or any `.json`/`.lottie`).
 * If no asset is bundled, this renders nothing (no placeholder), so the app is unaffected until a
 * motion graphic is supplied. Increment [trigger] to (re)play; it self-hides when the clip ends.
 *
 * @param trigger a monotonically increasing key; each new value replays the animation once.
 */
@Composable
fun GlassShatter(
    trigger: Int,
    modifier: Modifier = Modifier
) {
    if (trigger <= 0) return

    val composition by rememberLottieComposition(LottieCompositionSpec.Asset(GLASS_ASSET))
    if (composition == null) return // no motion graphic bundled yet — render nothing

    val animatable = rememberLottieAnimatable()
    var done by remember(trigger) { mutableStateOf(false) }

    LaunchedEffect(trigger, composition) {
        done = false
        animatable.snapTo(composition, progress = 0f)
        animatable.animate(composition, iterations = 1)
        done = true
    }
    if (done) return

    LottieAnimation(
        composition = composition,
        progress = { animatable.progress },
        modifier = modifier.fillMaxSize()
    )
}

/** The bundled glass-break Lottie asset; drop a `.json` here to supply the motion graphic. */
private const val GLASS_ASSET = "glass_break.json"
