package com.example.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * XENO: Living Presence — color tokens.
 *
 * The single source of truth for every hue in the app. The direction is calm, premium and
 * minimal — Linear / Apple restraint. A deep near-black canvas holds ONE quiet ambient glow
 * that breathes slowly; a single elegant accent (soft electric blue, [AccentSolid]) carries
 * the live / active state, the mic and the mode dot. Everything else recedes so XENO — the
 * on-screen person — is unmistakably the hero. Less is more: hairline borders, a barely-there
 * top-lit sheen, no heavy shadows, no rainbow.
 *
 * Layering model (lowest → highest):
 *   BgBase → BgRaised → Surface1 → Surface2 → Surface3, each separated by a single
 *   [GlassStroke] hairline and lit (not shadowed) by [GlassHighlight] / [GlassStrokeStrong].
 *
 * Accent policy:
 *   - [AccentSolid] is THE accent — used sparingly for live/active, the mic, the mode dot.
 *   - The former triad members ([AccentCyan] / [AccentViolet] / [AccentMagenta]) are retained
 *     as NAMES (consumers import them) but are now muted, closely-related blue-family tints so
 *     nothing reads as a rainbow. They sit quietly alongside the single accent.
 *   - [AccentAmber] is the one warm exception, reserved for BYPASS / elevated autonomy.
 *
 * NOTE: every public member below is part of the stable token API consumed across the app
 * (components, screens, NazimPersona). Values changed for the new direction; names are
 * load-bearing and must not be renamed or removed.
 */
object XenoColors {

  // ──────────────────────────────────────────────────────────────────────────
  // Backgrounds / surfaces — a deep, neutral near-black stack. Almost no hue, so
  // the single accent and XENO read clearly against it.
  // ──────────────────────────────────────────────────────────────────────────

  /** App canvas. The deepest layer — a true, calm near-black; everything floats above it. */
  val BgBase = Color(0xFF07080A)

  /** Raised structural layer (nav scrims, low containers) — one barely-perceptible step up. */
  val BgRaised = Color(0xFF0C0E12)

  /** Primary frosted-glass surface for cards, pills and sheets. */
  val Surface1 = Color(0xFF111318)

  /** Elevated glass surface for nested / focused content. */
  val Surface2 = Color(0xFF171A20)

  /** Highest translucent layer — popovers, the very top of stacked sheets. */
  val Surface3 = Color(0x331C2029)

  // ──────────────────────────────────────────────────────────────────────────
  // Strokes / scrims / glow — depth comes from a single hairline + soft top light,
  // never from heavy drop shadows.
  // ──────────────────────────────────────────────────────────────────────────

  /** 1px hairline border that defines every glass edge. Quiet and even. */
  val GlassStroke = Color(0xFFFFFFFF).copy(alpha = 0.07f)

  /** Brighter hairline for the lit TOP edge of a glass surface (the soft sheen). */
  val GlassStrokeStrong = Color(0xFFFFFFFF).copy(alpha = 0.13f)

  /** Faint top-lit inner sheen layered just inside a glass surface. Barely there. */
  val GlassHighlight = Color(0xFFFFFFFF).copy(alpha = 0.045f)

  /** Modal / sheet scrim over the canvas — deep, so the sheet floats clearly. */
  val Scrim = Color(0xFF000000).copy(alpha = 0.66f)

  // ──────────────────────────────────────────────────────────────────────────
  // Accent — ONE restrained, elegant soft electric blue. Used sparingly.
  // The legacy "triad" names are kept but collapsed into closely-related, muted
  // blue tints so the palette reads as a single calm accent, not a rainbow.
  // ──────────────────────────────────────────────────────────────────────────

  /** THE accent — a soft electric blue. Live/active state, the mic, the mode dot, focus. */
  val AccentSolid = Color(0xFF5B8DEF)

  /** Cooler, lighter wash of the accent (was "cyan"). Listening / calm — a quiet cool tint. */
  val AccentCyan = Color(0xFF7FB0F5)

  /** The signature accent tint (was "violet"). Thinking / AUTO / focus — same blue family. */
  val AccentViolet = Color(0xFF6E9BF0)

  /** Slightly warmer-leaning blue (was "magenta"). Speaking — still within the one accent. */
  val AccentMagenta = Color(0xFF8FA8F2)

  /** Amber — BYPASS / elevated-autonomy signal. The single warm exception, never decorative. */
  val AccentAmber = Color(0xFFE0A75A)

  // ──────────────────────────────────────────────────────────────────────────
  // Text — crisp off-white primary down to a dim tertiary, neutral and modern.
  // ──────────────────────────────────────────────────────────────────────────

  /** Primary text — clean off-white, never pure #FFFFFF (kinder on OLED black). */
  val TextPrimary = Color(0xFFF3F5F9)

  /** Secondary text — calm neutral grey for supporting copy. */
  val TextSecondary = Color(0xFF9AA1AE)

  /** Tertiary text — dim, for hints, timestamps, tracked-out caps labels, disabled state. */
  val TextTertiary = Color(0xFF5C6370)

  /** Text/icon color that sits on a solid accent fill. */
  val TextOnAccent = Color(0xFF050A14)

  // ──────────────────────────────────────────────────────────────────────────
  // Semantic — muted; these never shout against the near-black base.
  // ──────────────────────────────────────────────────────────────────────────

  val Success = Color(0xFF4DD3A3)
  val Error = Color(0xFFEC6A78)
  val Warning = Color(0xFFE0B062)

  // ──────────────────────────────────────────────────────────────────────────
  // Persona + autonomy-mode accents — color stays within the single accent family
  // so mode is read by subtle tint + the dot, not by competing hues. Amber alone
  // breaks out, to mark elevated autonomy. Consumers read these directly.
  // ──────────────────────────────────────────────────────────────────────────

  /** Default persona accent (XENO) — the one soft electric blue. */
  val PersonaAccent = AccentSolid

  /** AUTO autonomy mode — the accent blue, the trusted default-on signal. */
  val ModeAuto = AccentSolid

  /** ASK / ASK-LESS — the cooler blue tint, the safe baseline. */
  val ModeAsk = AccentCyan

  /** PLAN — the signature accent tint, "show me first". */
  val ModePlan = AccentViolet

  /** BYPASS — amber, the deliberate warm elevated-autonomy mark. */
  val ModeBypass = AccentAmber

  // ──────────────────────────────────────────────────────────────────────────
  // Gradients — soft, single-accent light. The last stop is transparent so every
  // halo dissolves into the near-black instead of cutting a hard edge. No rainbow.
  // ──────────────────────────────────────────────────────────────────────────

  /** Primary accent sweep — a gentle light→base blue. Mic fill, accent strokes. */
  val BrandGradient = listOf(AccentCyan, AccentSolid)

  /** Three-stop accent sweep within the one blue family (light → accent → deep). */
  val BrandTriad = listOf(AccentCyan, AccentSolid, AccentViolet)

  /**
   * The single ambient presence glow — one calm accent radiating then fully dissolving into
   * the canvas. This is the slow-breathing background light that replaces the old aurora.
   */
  val AuroraHalo = listOf(
    AccentSolid.copy(alpha = 0.22f),
    AccentSolid.copy(alpha = 0.08f),
    Color(0x000A0E1A),
  )

  /** Vertical glass sheen (top-lit highlight → transparent) for the soft surface top edge. */
  val GlassOverlay = listOf(GlassHighlight, Color(0x00FFFFFF))
}

/**
 * XENO: Warm Light — the light, premium, airy palette for the main companion surface
 * (`LiveScreen`). Cream-and-peach with a faint iridescent edge, soft frosted-white glass, and
 * warm-charcoal text — the Apple-Intelligence / Pixel-search aesthetic the product is going for.
 * Kept separate from [XenoColors] (the dark token set still used by secondary screens) so the
 * two never fight; LiveScreen and its chrome consume these.
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
  val DotPlan = Color(0xFF9B8CE0)
  val DotBypass = Color(0xFFEB7A6B)

  val Success = Color(0xFF3FAE78)
  val Error = Color(0xFFD9534F)
  val Warning = Color(0xFFC98A2E)

  /** The full warm canvas wash, top-left → bottom-right. */
  val Canvas = listOf(BgTop, BgMid, BgGold, BgPeach)
}
