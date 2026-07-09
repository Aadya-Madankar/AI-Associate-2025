package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A one-shot **glass shatter** burst, played when XENO breaks out of the app to roam your screen:
 * a white flash, jagged cracks racing out from the avatar, then the pane breaks into shards that
 * fly outward and fade. Pure [Canvas] — no assets. Increment [trigger] to (re)play; the effect
 * self-hides when the ~1.1s animation finishes, so it costs nothing at rest.
 *
 * @param trigger        a monotonically increasing key; each new value replays the burst.
 * @param originYFraction vertical origin of the shatter (where the avatar stands), 0..1 of height.
 */
@Composable
fun GlassShatter(
    trigger: Int,
    modifier: Modifier = Modifier,
    originYFraction: Float = 0.16f,
    tint: Color = Color.White
) {
    if (trigger <= 0) return

    val progress = remember(trigger) { Animatable(0f) }
    var done by remember(trigger) { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        done = false
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1100, easing = FastOutLinearInEasing))
        done = true
    }
    if (done) return

    // Deterministic geometry per trigger (stable across frames): cracks (angle + per-segment
    // jitter) and shards (angle, distance factor, spin).
    val rng = remember(trigger) { Random(trigger * 9973L + 7L) }
    val cracks = remember(trigger) {
        List(14) { i ->
            val ang = (i / 14f) * 2f * PI.toFloat() + (rng.nextFloat() - 0.5f) * 0.32f
            ang to List(6) { (rng.nextFloat() - 0.5f) }   // perpendicular jitter fraction per segment
        }
    }
    val shards = remember(trigger) {
        List(18) {
            Triple(
                rng.nextFloat() * 2f * PI.toFloat(),   // direction
                0.45f + rng.nextFloat(),               // distance factor
                (rng.nextFloat() - 0.5f) * 900f         // spin degrees
            )
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val p = progress.value
        val origin = Offset(size.width * 0.5f, size.height * originYFraction)
        val maxLen = size.maxDimension

        // Initial white flash (first ~12%).
        val flash = (1f - p / 0.12f).coerceIn(0f, 1f) * 0.35f
        if (flash > 0f) drawRect(color = tint.copy(alpha = flash))

        // Cracks race out (front-loaded) then fade as shards take over.
        val crackP = (p / 0.4f).coerceIn(0f, 1f)
        val crackAlpha = (1f - (p - 0.35f) / 0.35f).coerceIn(0f, 1f)
        if (crackAlpha > 0f) {
            for ((ang, jitters) in cracks) {
                val perp = ang + PI.toFloat() / 2f
                val path = Path().apply {
                    moveTo(origin.x, origin.y)
                    val steps = jitters.size
                    for (s in 1..steps) {
                        val t = s / steps.toFloat()
                        val len = maxLen * crackP * t
                        val jit = if (s < steps) jitters[s - 1] * 44f else 0f
                        lineTo(
                            origin.x + cos(ang) * len + cos(perp) * jit,
                            origin.y + sin(ang) * len + sin(perp) * jit
                        )
                    }
                }
                // Dark under-stroke first so the crack reads on a light background, white on top.
                drawPath(path, color = Color(0x33000000).copy(alpha = 0.35f * crackAlpha), style = Stroke(width = 6f))
                drawPath(path, color = tint.copy(alpha = 0.95f * crackAlpha), style = Stroke(width = 2.5f))
            }
        }

        // Shards fly out and fade in the second half.
        val shardP = ((p - 0.32f) / 0.68f).coerceIn(0f, 1f)
        if (shardP > 0f) {
            val shardAlpha = 1f - shardP
            for ((ang, dist, spin) in shards) {
                val travel = maxLen * 0.55f * shardP * dist
                val cx = origin.x + cos(ang) * travel
                val cy = origin.y + sin(ang) * travel
                val sz = 30f * (1f - shardP * 0.45f)
                translate(cx, cy) {
                    rotate(spin * shardP, pivot = Offset.Zero) {
                        val tri = Path().apply {
                            moveTo(0f, -sz)
                            lineTo(sz * 0.9f, sz * 0.75f)
                            lineTo(-sz * 0.8f, sz * 0.6f)
                            close()
                        }
                        drawPath(tri, color = Color(0x22000000).copy(alpha = 0.3f * shardAlpha), style = Stroke(width = 3f))
                        drawPath(tri, color = tint.copy(alpha = 0.55f * shardAlpha))
                        drawPath(tri, color = tint.copy(alpha = 0.95f * shardAlpha), style = Stroke(width = 1.5f))
                    }
                }
            }
        }
    }
}
