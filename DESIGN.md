# XENO: Living Presence — Design Tokens

Calm, premium, minimal (Linear / Apple restraint). A deep near-black canvas, ONE quiet
ambient presence glow that breathes slowly, and a SINGLE elegant accent — a soft electric
blue — used sparingly for the live/active state, the mic and the mode dot. Frosted glass,
1px hairlines, soft top-lit sheen, no heavy shadows, no rainbow. XENO (the on-screen person)
is the hero; everything else recedes.

Dark theme only. All tokens live under `com.example.ui.theme`
(`Color.kt`, `Type.kt`, `Shape.kt`, `Motion.kt`, `Theme.kt`). Names are a stable public API
consumed across the app (components, screens, `NazimPersona`) — **values change, names don't.**

---

## Color — `XenoColors` (object)

### Backgrounds / surfaces (lowest → highest)
| Token | Value | Use |
|---|---|---|
| `BgBase` | `#07080A` | App canvas, the deepest near-black |
| `BgRaised` | `#0C0E12` | Raised structural layer (nav scrims, low containers) |
| `Surface1` | `#111318` | Primary frosted-glass surface (cards, pills, sheets) |
| `Surface2` | `#171A20` | Elevated glass surface (nested / focused content) |
| `Surface3` | `#1C2029` @ 20% | Highest translucent layer (popovers, top of stacked sheets) |

### Strokes / scrim / sheen (depth from light, not shadow)
| Token | Value | Use |
|---|---|---|
| `GlassStroke` | white @ 7% | 1px hairline defining every glass edge |
| `GlassStrokeStrong` | white @ 13% | Lit top edge of a glass surface |
| `GlassHighlight` | white @ 4.5% | Faint top-lit inner sheen |
| `GlassOverlay` | `[GlassHighlight → transparent]` | Vertical top-lit sheen gradient |
| `Scrim` | black @ 66% | Modal / sheet scrim |

### Accent — ONE soft electric blue (the legacy "triad" names collapse into one blue family)
| Token | Value | Use |
|---|---|---|
| `AccentSolid` | `#5B8DEF` | **THE accent** — live/active, mic, mode dot, focus |
| `AccentCyan` | `#7FB0F5` | Cooler/lighter wash (listening) — same blue family |
| `AccentViolet` | `#6E9BF0` | Signature accent tint (thinking / AUTO / focus) |
| `AccentMagenta` | `#8FA8F2` | Slightly warmer-leaning blue (speaking) |
| `AccentAmber` | `#E0A75A` | The one warm exception — BYPASS / elevated autonomy |

> Note: `AccentCyan/Violet/Magenta` are intentionally NOT a rainbow anymore — they are muted,
> closely-related blue tints retained only so existing consumers keep compiling. Reach for
> `AccentSolid` first.

### Text
| Token | Value | Use |
|---|---|---|
| `TextPrimary` | `#F3F5F9` | Primary off-white (never pure white) |
| `TextSecondary` | `#9AA1AE` | Supporting copy, neutral grey |
| `TextTertiary` | `#5C6370` | Hints, timestamps, caps labels, disabled |
| `TextOnAccent` | `#050A14` | Text/icon on a solid accent fill |

### Semantic (muted)
| Token | Value |
|---|---|
| `Success` | `#4DD3A3` |
| `Error` | `#EC6A78` |
| `Warning` | `#E0B062` |

### Persona / autonomy mode (stays within the single accent; amber alone breaks out)
| Token | Maps to | Meaning |
|---|---|---|
| `PersonaAccent` | `AccentSolid` | XENO's accent |
| `ModeAuto` | `AccentSolid` | AUTO — trusted default-on |
| `ModeAsk` | `AccentCyan` | ASK / ASK-LESS — safe baseline |
| `ModePlan` | `AccentViolet` | PLAN — show me first |
| `ModeBypass` | `AccentAmber` | BYPASS — elevated autonomy |

### Gradients (single-accent; last stop transparent so halos dissolve into the canvas)
| Token | Stops |
|---|---|
| `BrandGradient` | `[AccentCyan → AccentSolid]` |
| `BrandTriad` | `[AccentCyan → AccentSolid → AccentViolet]` |
| `AuroraHalo` | the **single ambient presence glow**: `AccentSolid 22% → 8% → transparent` |
| `GlassOverlay` | `[GlassHighlight → transparent]` |

---

## Type — `XenoTypography` (Material3 `Typography`)

System sans (`FontFamily.SansSerif`), minSdk-24 safe. Crisp modern hierarchy: displays/headlines
run tight negative tracking; body is airy with generous line-height; small caps labels are
composed and tracked tightly (not wide/shouty).

| Slot | Size / Line | Weight | Tracking |
|---|---|---|---|
| `displayLarge` | 40 / 46 | SemiBold | -0.8 |
| `displayMedium` | 32 / 40 | SemiBold | -0.6 |
| `headlineLarge` | 26 / 34 | SemiBold | -0.4 |
| `headlineMedium` | 22 / 30 | Medium | -0.3 |
| `titleLarge` | 18 / 25 | SemiBold | -0.2 |
| `titleMedium` | 16 / 23 | Medium | -0.1 |
| `bodyLarge` | 17 / 27 | Normal | 0 |
| `bodyMedium` | 15 / 23 | Normal | 0 |
| `bodySmall` | 13 / 19 | Normal | 0 |
| `labelLarge` | 15 / 20 | SemiBold | 0 |
| `labelMedium` | 12 / 16 | Medium | 0.4 (render caps) |
| `labelSmall` | 11 / 14 | SemiBold | 0.6 (render caps) |

---

## Shape — `XenoShapes` (Material slots) + `XenoShapeTokens` (object)

Restrained, calm-pane rounding. Pills full-radius; cards 16–22dp; sheet 26dp top.

**`XenoShapes`:** extraSmall 9 · small 14 · medium 20 · large 26 · extraLarge 32 (dp)

**`XenoShapeTokens`:**
| Token | Value |
|---|---|
| `Pill` | 50% (full-radius) |
| `Card` | 22dp |
| `CardInner` | 16dp |
| `Sheet` | top 26dp, bottom square |
| `Control` | 14dp |

---

## Motion — `Motion` (object)

Subtle, organic, never busy: one slow breathing presence glow, an amplitude-reactive glow on
the mic/XENO, and gentle press feedback that settles without showy overshoot.

**Easings:** `EaseStandard` (gentle) · `EaseEnter` (decelerate) · `EaseExit` (accelerate) ·
`EaseAmbient` (sine-like — the breath)

**Durations (ms):** `Quick` 200 · `Base` 320 · `Slow` 540 · `Ambient` 5200 (one breath) ·
`Reactive` 130 (voice follow) · `Drift` 30000 (barely-perceptible glow drift)

**Springs / scale:** `SpringPress` (soft, ~no overshoot) · `SpringSoft` (no-bounce settle) ·
`PressScale` 0.97 · `RestScale` 1.0

---

## Theme — `MyApplicationTheme(content)` + `XenoDarkColorScheme`

`MyApplicationTheme` applies `XenoDarkColorScheme` + `XenoTypography` + `XenoShapes`, draws
edge-to-edge with transparent system bars and light (white) bar icons. The Material scheme is
intentionally monochromatic-blue: primary/secondary/tertiary all map into the single accent
family so Material's own tinting never reintroduces a multi-hue palette.

---

## Usage rules

- **One accent.** Default to `AccentSolid`. Only ever break to `AccentAmber` for BYPASS.
- **Light, not shadow.** Build depth with `GlassStroke` + `GlassHighlight`/`GlassStrokeStrong`,
  never drop shadows or stacked gradients.
- **One ambient loop.** At most one breathing element (`Ambient`) plus one `Reactive` element
  on screen at a time — nothing should compete with XENO.
- **Negative space first.** Generous padding and line-height; strip clutter.
