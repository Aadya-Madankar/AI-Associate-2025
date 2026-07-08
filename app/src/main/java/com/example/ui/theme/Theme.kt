package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * XENO: Living Presence — dark color scheme mapped from the Xeno design system.
 *
 * Dark theme only: no dynamic color, no light variant. The whole scheme leans on the SINGLE
 * accent ([AccentSolid]) for primary/secondary/tertiary so Material's own tinting reinforces
 * the calm one-accent direction rather than reintroducing a multi-hue palette. Surface
 * containers map onto the layered near-black stack (BgBase → BgRaised → Surface1 → Surface2)
 * so elevation tinting supports — rather than fights — the hand-built frosted-glass layering.
 */
internal val XenoDarkColorScheme =
  darkColorScheme(
    primary = XenoColors.AccentSolid,
    onPrimary = XenoColors.TextOnAccent,
    primaryContainer = XenoColors.AccentViolet,
    onPrimaryContainer = XenoColors.TextOnAccent,
    inversePrimary = XenoColors.AccentCyan,
    // Secondary stays inside the one accent family (muted blue) — no competing hue.
    secondary = XenoColors.AccentCyan,
    onSecondary = XenoColors.TextOnAccent,
    secondaryContainer = XenoColors.Surface2,
    onSecondaryContainer = XenoColors.TextPrimary,
    // Tertiary deliberately maps to the accent too, keeping Material monochromatic-blue.
    tertiary = XenoColors.AccentSolid,
    onTertiary = XenoColors.TextOnAccent,
    tertiaryContainer = XenoColors.Surface2,
    onTertiaryContainer = XenoColors.TextPrimary,
    background = XenoColors.BgBase,
    onBackground = XenoColors.TextPrimary,
    surface = XenoColors.Surface1,
    onSurface = XenoColors.TextPrimary,
    surfaceVariant = XenoColors.Surface2,
    onSurfaceVariant = XenoColors.TextSecondary,
    surfaceTint = XenoColors.AccentSolid,
    surfaceContainerLowest = XenoColors.BgBase,
    surfaceContainerLow = XenoColors.BgRaised,
    surfaceContainer = XenoColors.BgRaised,
    surfaceContainerHigh = XenoColors.Surface1,
    surfaceContainerHighest = XenoColors.Surface2,
    inverseSurface = XenoColors.TextPrimary,
    inverseOnSurface = XenoColors.BgBase,
    outline = XenoColors.TextTertiary,
    outlineVariant = XenoColors.Surface2,
    error = XenoColors.Error,
    onError = XenoColors.TextOnAccent,
    errorContainer = XenoColors.Surface2,
    onErrorContainer = XenoColors.Error,
    scrim = XenoColors.Scrim,
  )

/**
 * Root theme for Xeno Live. Applies the Living Presence dark scheme, type and shapes.
 *
 * System-bar appearance (edge-to-edge + icon contrast) is NOT set here — [com.example.MainActivity]
 * is the single owner of that, since it draws dark icons for the warm-light (XenoWarm)
 * canvas; a second writer here previously raced it and could leave white-on-cream
 * (invisible) status-bar icons depending on write order.
 */
@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = XenoDarkColorScheme,
    typography = XenoTypography,
    shapes = XenoShapes,
    content = content,
  )
}
