package com.example.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * XENO: Warm Light — the single, app-wide color language. Cream-and-peach with a faint
 * iridescent edge, soft frosted-white glass, and warm-charcoal text — the Apple-Intelligence /
 * Pixel-search aesthetic the product is going for. Every screen and overlay consumes these
 * (the legacy dark "Obsidian" token set has been retired).
 */
object XenoWarm {

  // -- Canvas: a warm-white → cream → peach wash with a cool iridescent corner. --
  val BgTop = Color(0xFFFDFCF9)
  val BgMid = Color(0xFFFBF2E8)
  val BgPeach = Color(0xFFF8DFC6)
  val BgLilac = Color(0xFFEDE9F7)
  val BgGold = Color(0xFFF6E2C4)

  /** Warm radial glow behind XENO (breathes with amplitude). */
  val GlowWarm = Color(0xFFFFD7A6)
  /** Cool iridescent counter-glow for the faint pearlescent shimmer. */
  val GlowCool = Color(0xFFCFE0FF)
  val GlowLilac = Color(0xFFE3D6FB)

  // -- Text: warm charcoal stack on the light wash. --
  val TextPrimary = Color(0xFF2E2A26)
  val TextSecondary = Color(0xFF7C746B)
  val TextTertiary = Color(0xFFAAA095)
  val TextOnDark = Color(0xFFFDFBF7)

  // -- Frosted-white glass surfaces + hairlines. --
  val Surface = Color(0xFFFFFFFF)
  val SurfaceStrong = Color(0xFFFFFFFF)
  /** Hairline edge — a whisper of warm charcoal. */
  val Hairline = Color(0x142E2A26)
  /** Top-lit sheen on frosted glass. */
  val Sheen = Color(0x99FFFFFF)
  val Scrim = Color(0x33FFFFFF)

  // -- Iridescent accent sweep (the bottom-left orb / live mic ring in the reference). --
  val Iris1 = Color(0xFFFBC2A6)
  val Iris2 = Color(0xFFF3A9C6)
  val Iris3 = Color(0xFFC3A8F0)
  val Iris4 = Color(0xFFA6C4F3)
  val Iris5 = Color(0xFFAEEBDD)
  val Iridescent = listOf(Iris1, Iris2, Iris3, Iris4, Iris5)

  // -- Mode dots (quiet, warm). --
  val DotAsk = Color(0xFFE0A75A)
  val DotAskLess = Color(0xFF7FB0E6)
  val DotAuto = Color(0xFF63C28C)
  val DotBypass = Color(0xFFEB7A6B)

  val Success = Color(0xFF3FAE78)
  val Error = Color(0xFFD9534F)
  val Warning = Color(0xFFC98A2E)

  /** The full warm canvas wash, top-left → bottom-right. */
  val Canvas = listOf(BgTop, BgMid, BgGold, BgPeach)
}
