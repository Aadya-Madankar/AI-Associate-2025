export const meta = {
  name: 'xeno-ui-polish',
  description: 'Elevate Xeno Live into a premium, clean, attractive Obsidian-Aurora design system: one design lead rewrites the theme coherently, then parallel agents restyle every component/screen against it (signatures preserved, Compose optimized)',
  phases: [
    { title: 'DesignSystem', detail: 'one lead rewrites ui/theme/* into a cohesive premium token system + DESIGN.md' },
    { title: 'Polish', detail: 'parallel: restyle each component/screen against the committed tokens, keep signatures, optimize Compose' },
    { title: 'Review', detail: 'per file: compiles + signature-preserved + visually consistent' },
  ],
}

const CWD = '/Users/aadyamadankar/working/xeno-live'
const UI = `${CWD}/app/src/main/java/com/example/ui`
const CH = `${CWD}/app/src/main/java/com/example/character`
const short = p => p.split('/').pop()

const DIRECTION = `DESIGN DIRECTION — "Obsidian Aurora" (aim for Linear / Arc / Apple-level polish):
- Mood: calm, futuristic, premium, uncluttered. Lots of negative space. Nothing competes with Nazim.
- Base: near-black layered surfaces (e.g. bg ~#0A0A0F, raised ~#14141C, glass overlays with subtle 1dp hairline borders + soft inner glow). Real depth via translucency + blur-like layering, not heavy shadows.
- Accent: a restrained triad — electric violet, cyan, magenta — used MOSTLY as soft gradients/glows and only as small solid hits (active states, the mic, the mode dot). Never rainbow clutter.
- Text: high-contrast off-white primary, muted secondary, dim tertiary; tight letter-spacing on small labels; clear type hierarchy.
- Shape: generous rounded corners (pills ~full, cards 20-28dp, sheets 28dp top), consistent across the app.
- Motion: smooth + organic — a slow breathing aurora, amplitude-reactive glow on the mic + Nazim halo, gentle spring on press, fade/slide for surfaces. Subtle, never busy.
- Color should also subtly reflect the active persona accent + the autonomy mode (e.g. AUTO=violet, BYPASS=amber).`

const WRITTEN = { type: 'object', additionalProperties: false, properties: { writtenFiles: { type: 'array', items: { type: 'string' } }, preservedSymbols: { type: 'array', items: { type: 'string' } }, notes: { type: 'string' } }, required: ['writtenFiles', 'notes'] }
const FINDINGS = { type: 'object', additionalProperties: false, properties: { issues: { type: 'array', items: { type: 'object', additionalProperties: false, properties: { severity: { type: 'string', enum: ['high', 'medium', 'low'] }, problem: { type: 'string' }, fix: { type: 'string' } }, required: ['severity', 'problem', 'fix'] } } }, required: ['issues'] }

// ---- Phase 1: coherent design system ---------------------------------------
phase('DesignSystem')
const ds = await agent(
  `You are the design lead for Xeno Live (Android Jetpack Compose, Material 3). Rewrite the theme into a cohesive, premium design system.

FIRST READ every file under ${UI}/theme/ (Color.kt, Type.kt, Shape.kt, Motion.kt, Theme.kt) and skim a couple of consumers (${UI}/components/StatusPill.kt, ${UI}/components/AuroraOrb.kt, ${CH}/NazimPersona.kt) so you learn the EXACT public token API in use (e.g. the XenoColors object members, the theme function name MyApplicationTheme, any Motion/Shape tokens).

${DIRECTION}

THEN rewrite ${UI}/theme/Color.kt, Type.kt, Shape.kt, Motion.kt, Theme.kt to deliver that direction.
HARD CONSTRAINTS:
1. PRESERVE every existing public declaration name and signature that consumers reference (every current XenoColors member, the theme composable name, every public Motion/Shape/Type symbol). You may CHANGE their values and ADD new tokens, but do NOT rename or remove anything — other files (including non-ui ones like NazimPersona) import these by name and must keep compiling.
2. Keep it valid Compose/Material 3, minSdk 24. The theme must apply a dark color scheme.
3. List the preserved public symbols you kept in 'preservedSymbols'.

Also write ${CWD}/DESIGN.md: a concise token reference (palette with hex, type scale, radii, motion specs, usage rules) so the polish agents and humans share one source of truth.

Return the files you wrote, the preserved symbols, and notes.`,
  { schema: WRITTEN, phase: 'DesignSystem', label: 'design-lead' }
)
const preserved = (ds && ds.preservedSymbols) ? ds.preservedSymbols.join(', ') : 'all existing public theme symbols'
log(`Design system rewritten; preserved: ${preserved.slice(0, 200)}`)

// ---- Phase 2: per-component polish against committed tokens ------------------
const TARGETS = [
  { f: `${UI}/components/AuroraBackground.kt`, g: 'a premium, slow-breathing aurora gradient backdrop (full-bleed) that subtly shifts with state + amplitude; cheap to render.' },
  { f: `${UI}/components/AuroraOrb.kt`, g: 'a refined ambient halo/aura (it is now backdrop, NOT the character) — soft, glowing, amplitude-reactive, never garish.' },
  { f: `${UI}/components/MicButton.kt`, g: 'a clean mic "orb" with an amplitude ring + crisp idle/listening/speaking states; satisfying press.' },
  { f: `${UI}/components/StatusPill.kt`, g: 'a minimal frosted-glass status pill: small state dot + concise label, tight typography.' },
  { f: `${UI}/components/TranscriptOverlay.kt`, g: 'an elegant glass transcript: distinct you/Nazim styling, comfortable line-height, gentle fade-in, auto-scroll, no clutter.' },
  { f: `${UI}/components/PersonaSwitcher.kt`, g: 'a clean modal sheet of companions with clear selection state.' },
  { f: `${UI}/agent/ConfirmSheet.kt`, g: 'a premium, instantly-scannable confirm sheet: bold verb, app chip, LITERAL details rows, a clear risk badge, well-spaced Deny / Allow-once / Always actions.' },
  { f: `${UI}/agent/ModePill.kt`, g: 'a refined autonomy mode pill whose accent + dot color encodes the mode (ASK calm → BYPASS amber/armed); tappable to cycle.' },
  { f: `${UI}/agent/AgentConsole.kt`, g: 'a sleek "working…" strip: subtle progress shimmer, current step text, step-count badge; appears/disappears smoothly.' },
  { f: `${UI}/agent/KillSwitchOverlay.kt`, g: 'a clear, urgent-but-tasteful floating STOP (calm pulse, unmistakable, never alarming-ugly).' },
  { f: `${UI}/agent/PlanPreviewScreen.kt`, g: 'a clean numbered plan list with a confident Run / Cancel bar.' },
  { f: `${UI}/agent/AuditLogScreen.kt`, g: 'a tidy, scannable log: timestamp, action, target, outcome chip; calm density.' },
  { f: `${UI}/agent/PermissionCenterScreen.kt`, g: 'a clean settings-style screen for allow/deny lists + special-access grants, with clear section headers.' },
  { f: `${UI}/agent/OnboardingConsentScreen.kt`, g: 'a beautiful, trustworthy first-run: clear capability disclosure, the value, and a confident "Enable Accessibility" CTA.' },
  { f: `${CH}/NazimFallbackPortrait.kt`, g: 'make the no-GLB fallback genuinely gorgeous: a stylized realistic Nazim portrait with the aurora halo + state-reactive light, so it looks intentional, not a placeholder.' },
  { f: `${UI}/screens/LiveScreen.kt`, g: 'polish ONLY the layout/spacing/visual hierarchy and transitions. PRESERVE every viewModel collectAsState and every composable call (NazimView, StatusPill, ModePill, PersonaSwitcher, TranscriptOverlay, AgentConsole, MicButton, KillSwitchOverlay, ConfirmSheet) with their current arguments and the @OptIn — do NOT remove the agent UI or rewire anything.' },
]

const RULES = `RULES:
1. READ the target file AND ${CWD}/DESIGN.md + ${UI}/theme/* first. Use ONLY the committed theme tokens (preserve their names); add a local constant only if a token truly doesn't exist.
2. PRESERVE the file's exact public @Composable signature(s) and package — callers must keep compiling. Restyle the body only.
3. Optimize Compose: hoist state, no allocation/IO in composition, use remember/derivedStateOf, keep animations cheap (single infinite transition where possible), stable params.
4. Must compile (Material 3, minSdk 24). No new dependencies. Do NOT edit files outside the target.`

phase('Polish')
await pipeline(
  TARGETS,
  t => agent(
    `Restyle this Xeno Live component to the premium Obsidian-Aurora design system.\nTARGET: ${t.f}\nGOAL: ${t.g}\n${DIRECTION}\n${RULES}\nReturn the file you wrote.`,
    { schema: WRITTEN, phase: 'Polish', label: `polish:${short(t.f)}` }
  ).then(r => ({ t, written: (r && r.writtenFiles && r.writtenFiles[0]) || t.f })),
  async (built) => {
    if (!built) return null
    const rev = await agent(
      `Review the restyled file ${built.written} (Xeno Live, Compose). READ it + ${CWD}/DESIGN.md + ${UI}/theme/*. Check: (a) it COMPILES (valid Compose/Material3, only existing theme symbols or local fallbacks, correct imports), (b) the public composable signature(s) are UNCHANGED, (c) for LiveScreen.kt specifically: all viewModel wiring + composable calls are preserved, (d) it's visually consistent with the design tokens and not cluttered. Report concrete issues + fixes; empty if good.`,
      { schema: FINDINGS, phase: 'Review', label: `rev:${short(built.written)}` }
    )
    const issues = (rev?.issues || []).filter(i => i.severity !== 'low')
    if (issues.length) {
      await agent(
        `Fix ${built.written}: ${issues.map(i => i.problem + ' -> ' + i.fix).join('; ')}. READ it, Edit it, keep the signature + (for LiveScreen) the wiring intact, keep it compiling against the theme tokens. Don't touch other files.`,
        { phase: 'Review', label: `fix:${short(built.written)}` }
      )
    }
    return built.written
  }
)
return { themeFiles: ds?.writtenFiles || [], polished: TARGETS.map(t => short(t.f)) }
