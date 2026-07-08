package com.example.character

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.models.Persona

private const val TAG = "NazimView"

/**
 * The single on-screen face of Xeno Live: **Nazim**, the photoreal MetaHuman (SCOPE.md §2).
 *
 * Primary path: load `assets/nazim.glb` with SceneView/Filament via [NazimRenderer] and drive
 * its facial morph targets every frame from the live [amplitude] (visemes) and [state]
 * (expression). Uses the SceneView API known to work with `io.github.sceneview:sceneview:2.3.3`.
 *
 * Fallback path: if the GLB is **not** bundled, or anything in the 3D path throws, render
 * [NazimFallbackPortrait] (a stylized realistic portrait + aurora halo) instead. This
 * composable MUST compile and run whether or not the GLB is present, so the SceneView path is
 * guarded both at the asset level ([NazimAssets.hasModel]) and with try/catch inside
 * [NazimRenderer]. The screen is never blank.
 *
 * @param state    expressive state of Nazim (see [NazimState]); drives brows/eyes/idle pose.
 * @param amplitude live 0..1 audio amplitude from the Gemini Live stream; drives lip-sync.
 * @param persona  the active persona (expected to be [NazimPersona.nazim]); supplies the halo
 *                 accent palette for the fallback portrait and ambient orb.
 */
@Composable
fun NazimView(
    state: NazimState,
    amplitude: Float,
    persona: Persona,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Runtime asset probe: only attempt the SceneView path if nazim.glb is actually bundled.
    val hasModel = remember { NazimAssets.hasModel(context) }

    // If the 3D path fails at runtime we flip this and never touch SceneView again this session.
    var sceneFailed by remember { mutableStateOf(false) }

    // Fallback when there is no animated GLB (or the 3D path failed): the procedural vector
    // portrait. The screen is never blank.
    if (!hasModel || sceneFailed) {
        NazimFallbackPortrait(state = state, amplitude = amplitude, persona = persona, modifier = modifier)
        return
    }

    NazimRenderer(
        state = state,
        amplitude = amplitude,
        modifier = modifier,
        onFailure = { t ->
            Log.w(TAG, "SceneView Nazim path failed; falling back to portrait", t)
            sceneFailed = true
        }
    )
}
