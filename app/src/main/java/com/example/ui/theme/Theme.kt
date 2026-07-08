package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * XENO: Warm Light color scheme mapped from [XenoWarm] — the single, app-wide design language.
 *
 * Light theme only: no dynamic color, no dark variant. Surface containers map onto the warm
 * canvas / frosted-glass stack so Material's own elevation tinting supports — rather than
 * fights — the hand-built glass layering already used throughout the app.
 */
internal val XenoLightColorScheme =
  lightColorScheme(
    primary = XenoWarm.TextPrimary,
    onPrimary = XenoWarm.TextOnDark,
    primaryContainer = XenoWarm.Iris3,
    onPrimaryContainer = XenoWarm.TextOnDark,
    inversePrimary = XenoWarm.Iris4,
    secondary = XenoWarm.Iris4,
    onSecondary = XenoWarm.TextOnDark,
    secondaryContainer = XenoWarm.BgMid,
    onSecondaryContainer = XenoWarm.TextPrimary,
    tertiary = XenoWarm.TextPrimary,
    onTertiary = XenoWarm.TextOnDark,
    tertiaryContainer = XenoWarm.BgMid,
    onTertiaryContainer = XenoWarm.TextPrimary,
    background = XenoWarm.BgTop,
    onBackground = XenoWarm.TextPrimary,
    surface = XenoWarm.Surface,
    onSurface = XenoWarm.TextPrimary,
    surfaceVariant = XenoWarm.BgMid,
    onSurfaceVariant = XenoWarm.TextSecondary,
    surfaceTint = XenoWarm.TextPrimary,
    surfaceContainerLowest = XenoWarm.BgTop,
    surfaceContainerLow = XenoWarm.BgMid,
    surfaceContainer = XenoWarm.BgMid,
    surfaceContainerHigh = XenoWarm.Surface,
    surfaceContainerHighest = XenoWarm.SurfaceStrong,
    inverseSurface = XenoWarm.TextPrimary,
    inverseOnSurface = XenoWarm.BgTop,
    outline = XenoWarm.TextTertiary,
    outlineVariant = XenoWarm.BgMid,
    error = XenoWarm.Error,
    onError = XenoWarm.TextOnDark,
    errorContainer = XenoWarm.BgMid,
    onErrorContainer = XenoWarm.Error,
    scrim = XenoWarm.Scrim,
  )

/**
 * Root theme for Xeno Live. Applies the Warm Light scheme, type and shapes.
 *
 * System-bar appearance (edge-to-edge + icon contrast) is NOT set here — [com.example.MainActivity]
 * is the single owner of that, since it draws dark icons for the warm-light (XenoWarm)
 * canvas; a second writer here previously raced it and could leave white-on-cream
 * (invisible) status-bar icons depending on write order.
 */
@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = XenoLightColorScheme,
    typography = XenoTypography,
    shapes = XenoShapes,
    content = content,
  )
}
