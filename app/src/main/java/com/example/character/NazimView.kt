package com.example.character

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.models.Persona

private const val TAG = "NazimView"

/**
 * Debug-only animation preview: long-press the avatar to step through each [NazimState] so you can
 * eyeball how the character looks per state. NOTE the bundled model (`nazim.glb`) ships only an
 * `idle` clip, so every state resolves to idle — the visible difference is the head-sway energy and
 * expression, not distinct clips. To preview real Walk/Run/gesture motion, bundle a rig that ships
 * those clips (see [NazimAnimationMap]). After the last state, the cycle returns to live.
 */
private val PREVIEW_CYCLE = listOf(
    NazimState.IDLE, NazimState.LISTENING, NazimState.THINKING, NazimState.SPEAKING,
    NazimState.ACTING, NazimState.WALKING, NazimState.RUNNING
)

/**
 * The single on-screen face of Xeno Live: **Nazim**, rendered from `assets/nazim.glb` via
 * [NazimRenderer] (SceneView/Filament), or the code-drawn [NazimFallbackPortrait] if the GLB is
 * absent or the 3D path throws. The screen is never blank.
 *
 * @param state    expressive state of Nazim (see [NazimState]).
 * @param amplitude live 0..1 audio amplitude; drives lip-sync where the rig supports it.
 * @param persona  the active persona (supplies the fallback portrait's accent palette).
 */
@Composable
fun NazimView(
    state: NazimState,
    amplitude: Float,
    persona: Persona,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasModel = remember { NazimAssets.hasModel(context) }
    var sceneFailed by remember { mutableStateOf(false) }

    // Debug-only state override so every NazimState can be eyeballed; null == follow live state.
    var preview by remember { mutableStateOf<NazimState?>(null) }
    val shown = preview ?: state

    Box(modifier) {
        if (!hasModel || sceneFailed) {
            NazimFallbackPortrait(
                state = shown, amplitude = amplitude, persona = persona,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            NazimRenderer(
                state = shown,
                amplitude = amplitude,
                modifier = Modifier.fillMaxSize(),
                onFailure = { t ->
                    Log.w(TAG, "SceneView Nazim path failed; falling back to portrait", t)
                    sceneFailed = true
                }
            )
        }

        // Transparent long-press catcher ON TOP of the renderer — the SceneView SurfaceView owns
        // its own touch gestures, so the catcher must sit above it to receive the long-press.
        if (BuildConfig.DEBUG) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = {
                            preview = PREVIEW_CYCLE.getOrNull(PREVIEW_CYCLE.indexOf(preview) + 1)
                        })
                    }
            )
        }

        if (BuildConfig.DEBUG && preview != null) {
            Text(
                text = "Preview: ${preview!!.name}",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
