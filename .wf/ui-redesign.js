export const meta = {
  name: 'xeno-ui-redesign',
  description: 'New clean/minimal UI + theme for XENO — a calm "person living inside the phone": one design lead rewrites the theme to a restrained premium aesthetic, then agents restyle every surface against it (signatures + wiring preserved).',
  phases: [
    { title: 'Theme', detail: 'design lead rewrites ui/theme/* to the new minimal aesthetic + DESIGN.md' },
    { title: 'Restyle', detail: 'restyle each surface against the new tokens, preserve signatures + wiring' },
    { title: 'Review', detail: 'per file: compiles, signatures + call sites intact' },
  ],
}

const CWD = '/Users/aadyamadankar/working/xeno-live'
const UI = `${CWD}/app/src/main/java/com/example/ui`
const CH = `${CWD}/app/src/main/java/com/example/character`
const short = p => p.split('/').pop()

const DIRECTION = `NEW DIRECTION — "XENO: Living Presence" (calm, premium, minimal — think Linear/Apple restraint):
- The feeling: a real person is quietly present with you. XENO (the character) is the HERO; everything else recedes into the background. Less is more — strip clutter, lots of negative space.
- Canvas: deep near-black. Replace the busy multi-colour aurora with a calmer, more sophisticated single ambient glow that breathes slowly.
- Accent: pick ONE restrained, elegant accent (e.g. a soft electric blue, or a warm gold) used sparingly for the live/active state, the mic, and the mode dot. NOT a rainbow. Secondary tints are muted.
- Surfaces: barely-there frosted glass, 1px hairline borders, soft top-lit sheen — no heavy shadows or gradients everywhere.
- Type: crisp, modern, clear hierarchy; tight tracking on small caps labels; generous line-height.
- Motion: subtle and organic — a slow breathing presence and amplitude-reactive glow; nothing busy.`

const WRITTEN = { type: 'object', additionalProperties: false, properties: { writtenFiles: { type: 'array', items: { type: 'string' } }, notes: { type: 'string' } }, required: ['writtenFiles', 'notes'] }
const FIND = { type: 'object', additionalProperties: false, properties: { issues: { type: 'array', items: { type: 'object', additionalProperties: false, properties: { severity: { type: 'string', enum: ['high', 'medium', 'low'] }, problem: { type: 'string' }, fix: { type: 'string' } }, required: ['severity', 'problem', 'fix'] } } }, required: ['issues'] }

phase('Theme')
const ds = await agent(
  `You are the design lead for XENO (Android Jetpack Compose, Material 3). Rewrite the theme to the NEW direction below.
FIRST READ ${UI}/theme/Color.kt, Type.kt, Shape.kt, Motion.kt, Theme.kt and a couple of consumers (${UI}/components/StatusPill.kt, ${CH}/NazimPersona.kt) to learn the EXACT public token API in use (every XenoColors member, the theme fn name, Motion/Shape/Type symbols).
${DIRECTION}
THEN rewrite ${UI}/theme/Color.kt, Type.kt, Shape.kt, Motion.kt, Theme.kt to deliver it.
HARD CONSTRAINTS: PRESERVE every existing public declaration NAME that consumers reference (every current XenoColors member, the theme composable, every public Motion/Shape/Type symbol) — other files (incl. NazimPersona) import these by name and must keep compiling. You may CHANGE their VALUES and ADD new tokens; do NOT rename or remove. Keep a dark color scheme. Also overwrite ${CWD}/DESIGN.md with a concise token reference. Return files written + the preserved symbols.`,
  { schema: WRITTEN, phase: 'Theme', label: 'design-lead' }
)
log('Theme rewritten; restyling surfaces')

const RULES = `RULES: READ the target + ${CWD}/DESIGN.md + ${UI}/theme/* first. Use ONLY the committed theme tokens (preserve their names). PRESERVE the file's exact public @Composable signature(s) and package, AND every existing call into it / out of it — for ${UI}/screens/LiveScreen.kt you MUST keep every viewModel.* call and every child composable call (NazimView, StatusPill, ModePill, PersonaSwitcher, TranscriptOverlay, AgentConsole, MicButton, VisionControls, KillSwitchOverlay, ConfirmSheet, ApiKeysSheet, the key IconButton, @OptIn) with their current arguments — only restyle layout/spacing/visuals. Optimize Compose (no work in composition, cheap animations, stable params). Must compile, Material 3, minSdk 24. No new deps. Edit only the target.`
const TARGETS = [
  { f: `${UI}/components/AuroraBackground.kt`, g: 'a calm single-hue ambient glow that breathes slowly (replace the busy multi-colour aurora); cheap to render.' },
  { f: `${CH}/NazimFallbackPortrait.kt`, g: 'XENO as a calm present figure — refined, minimal, the hero; restrained accent halo, state-reactive light.' },
  { f: `${UI}/components/MicButton.kt`, g: 'a clean mic orb, single accent, crisp idle/listening/speaking states.' },
  { f: `${UI}/components/StatusPill.kt`, g: 'a minimal hairline-glass status pill, quiet.' },
  { f: `${UI}/components/TranscriptOverlay.kt`, g: 'an elegant minimal transcript, lots of breathing room, gentle fade.' },
  { f: `${UI}/agent/ModePill.kt`, g: 'a refined mode pill, accent dot encodes the mode, minimal.' },
  { f: `${UI}/agent/AgentConsole.kt`, g: 'a quiet working strip with the per-step state, minimal.' },
  { f: `${UI}/agent/ConfirmSheet.kt`, g: 'a clean, instantly-scannable confirm sheet.' },
  { f: `${UI}/agent/ApiKeysSheet.kt`, g: 'a clean keys sheet consistent with the new theme.' },
  { f: `${UI}/screens/LiveScreen.kt`, g: 'ONLY layout/spacing/hierarchy/transitions — calm, minimal, XENO-forward. Preserve ALL wiring + composable calls + the key icon button + @OptIn exactly.' },
]
phase('Restyle')
await pipeline(
  TARGETS,
  t => agent(`Restyle ${t.f} to the new XENO theme.\nGOAL: ${t.g}\n${DIRECTION}\n${RULES}\nReturn the file written.`,
    { schema: WRITTEN, phase: 'Restyle', label: `restyle:${short(t.f)}` }).then(r => ({ t, w: (r && r.writtenFiles && r.writtenFiles[0]) || t.f })),
  async (b) => {
    if (!b) return null
    const rev = await agent(`Review ${b.w} (XENO, Compose). READ it + ${CWD}/DESIGN.md + ${UI}/theme/*. Check: (a) COMPILES (only existing theme symbols or local fallbacks, correct imports), (b) public composable signature(s) UNCHANGED, (c) for LiveScreen.kt: every viewModel call + child composable call + key button + @OptIn preserved, (d) visually consistent + minimal. Concrete issues + fixes; empty if good.`,
      { schema: FIND, phase: 'Review', label: `rev:${short(b.w)}` })
    const issues = (rev?.issues || []).filter(i => i.severity !== 'low')
    if (issues.length) await agent(`Fix ${b.w}: ${issues.map(i => i.problem + ' -> ' + i.fix).join('; ')}. READ it, Edit it, keep signature + wiring intact, keep compiling against the theme tokens. Don't touch other files.`, { phase: 'Review', label: `fix:${short(b.w)}` })
    return b.w
  }
)
return { theme: ds?.writtenFiles, restyled: TARGETS.map(t => short(t.f)) }
