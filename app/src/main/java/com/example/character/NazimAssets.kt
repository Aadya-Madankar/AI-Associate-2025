package com.example.character

import android.content.Context
import android.util.Log

/**
 * Centralizes the on-disk location of the **Nazim** MetaHuman model and the runtime
 * existence check that gates the SceneView/Filament render path.
 *
 * Nazim is the single photoreal face of Xeno Live (SCOPE.md §2). The rigged GLB humanoid
 * (with ARKit-style blendshapes) lives under `assets/nazim.glb`. If that asset is **not**
 * bundled, callers must gracefully fall back to [NazimFallbackPortrait] so the screen is
 * never blank.
 *
 * This object performs **no** Filament/SceneView work — it only answers "is the model
 * present?" so the heavy 3D path can be skipped entirely when there is nothing to load.
 */
object NazimAssets {

    private const val TAG = "NazimAssets"

    /** Bare asset file name, as listed by [android.content.res.AssetManager.list]. */
    const val MODEL_ASSET: String = "nazim.glb"

    /**
     * Path passed to SceneView's `ModelLoader.createModelInstance(...)`. SceneView resolves
     * this relative to the `assets/` root, mirroring the `avatar.glb` loading in
     * `com.example.avatar.AvatarView`.
     */
    const val MODEL_PATH: String = "nazim.glb"

    /**
     * Optional single portrait render of Nazim (asset path ① — easiest MetaHuman use: render a
     * front portrait PNG and drop it here). [NazimPortraitAvatar] animates it when the GLB is
     * absent. See NAZIM_ASSETS.md.
     */
    const val PORTRAIT_ASSET: String = "nazim_portrait.png"

    /** True iff a Nazim portrait image is bundled (used as a mid-tier fallback below the GLB). */
    fun hasPortrait(context: Context): Boolean = try {
        context.assets.list("")?.any { it.equals(PORTRAIT_ASSET, ignoreCase = true) } == true
    } catch (t: Throwable) {
        false
    }

    /**
     * Returns `true` iff [MODEL_ASSET] is bundled in the APK's `assets/` directory.
     *
     * Implemented as a cheap, allocation-light `AssetManager.list("")` scan (the GLB sits at
     * the assets root). Any failure to enumerate assets is treated as "model missing" so the
     * fallback portrait is shown rather than crashing. Safe to call from composition.
     */
    fun hasModel(context: Context): Boolean {
        return try {
            val present = context.assets
                .list("")
                ?.any { it.equals(MODEL_ASSET, ignoreCase = true) } == true
            if (!present) {
                Log.i(TAG, "Nazim model '$MODEL_ASSET' not bundled; using fallback portrait")
            }
            present
        } catch (t: Throwable) {
            Log.w(TAG, "Could not enumerate assets; assuming Nazim model absent", t)
            false
        }
    }
}
