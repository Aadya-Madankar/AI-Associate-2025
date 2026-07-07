export const meta = {
  name: 'xeno-build-wave2-experience',
  description: 'Build the Xeno Live experience layer: Nazim MetaHuman character, voice foreground service, agent UI (confirm sheet, mode pill, audit/permission/onboarding), and the AgentCoordinator that ties the Wave-1 engine to the UI',
  phases: [
    { title: 'Generate', detail: 'one architect per stream writes its files coherently' },
    { title: 'Review', detail: '3 lenses per file: contract-conformance, android/compose-correctness, security/UX' },
    { title: 'Fix', detail: 'one fixer per file applies confirmed high/medium findings' },
  ],
}

const CWD = '/Users/aadyamadankar/working/xeno-live'
const SRC = `${CWD}/app/src/main/java/com/example`
const short = p => p.split('/').pop()

const CONTRACTS = `Committed CONTRACTS exist on disk — import/implement, do NOT redefine:
- com.example.permission.* (AutonomyMode, RiskTier, ActionType, AgentAction, SecureReason, PermissionDecision, ConfirmRequest, RuleScope, PermissionEngine, SecureContextDetector, RuleStore)
- com.example.accessibility.* (UiElement, ScreenState, AccessibilityController, Accessibility)
- com.example.agent.* (ToolResult, ToolDeclaration, AgentTool, PhoneControlExecutor, AgentStatus)
- com.example.audit.* (AuditOutcome, AuditEntry, AuditLog)
- com.example.voice.VoiceMode, com.example.character.NazimState
- com.example.core.* (CompanionState, Sender, ChatMessage), com.example.models.Persona
WAVE-1 CONCRETES now also exist (READ them for exact signatures before using): com.example.di.ServiceLocator, com.example.agent.DefaultPhoneControlExecutor, com.example.agent.AgentLoopController, com.example.permission.DefaultPermissionEngine, com.example.audit.RoomAuditLog.`

const RULES = `HARD RULES:
1. Create ONLY the new files at the exact paths listed. Do NOT edit existing files (not XenoViewModel, MainActivity, LiveScreen, manifest, or build.gradle) — integration wires you in afterward.
2. Depend only on the contracts, the Android SDK, Jetpack Compose (Material 3, already on classpath), kotlinx.coroutines, and the Wave-1 classes you READ. Do not invent Wave-1 signatures — open the files.
3. Production-quality idiomatic Kotlin/Compose, targetSdk 36 / minSdk 24, version-guarded APIs. KDoc on public types. MUST compile.
4. First READ ${CWD}/ARCHITECTURE.md and ${CWD}/SCOPE.md, plus the contract files, before writing.`

const WRITTEN_SCHEMA = { type: 'object', additionalProperties: false, properties: { writtenFiles: { type: 'array', items: { type: 'string' } }, notes: { type: 'string' } }, required: ['writtenFiles', 'notes'] }
const FINDINGS_SCHEMA = { type: 'object', additionalProperties: false, properties: { issues: { type: 'array', items: { type: 'object', additionalProperties: false, properties: { severity: { type: 'string', enum: ['high', 'medium', 'low'] }, problem: { type: 'string' }, fix: { type: 'string' } }, required: ['severity', 'problem', 'fix'] } } }, required: ['issues'] }

const CH = `${SRC}/character`
const VO = `${SRC}/voice`
const UI = `${SRC}/ui/agent`
const STREAMS = [
  {
    name: 'nazim-character',
    mayUse: 'contracts + the EXISTING avatar/AvatarView.kt SceneView pattern + Compose',
    guidance: `Build Nazim, the single photoreal MetaHuman face of the app (SCOPE.md §2). CRITICAL: first READ ${SRC}/avatar/AvatarView.kt and ${SRC}/avatar/LipSyncController.kt and ${SRC}/ui/components/AuroraOrb.kt — reuse their EXACT SceneView/Filament API usage (the io.github.sceneview:sceneview 2.3.3 dependency is already wired and working there). NazimView is a Compose @Composable taking (state: NazimState, amplitude: Float, persona: Persona, modifier). It loads assets/nazim.glb via the same SceneView API AvatarView uses; if the asset is missing OR SceneView throws, it MUST gracefully fall back to NazimFallbackPortrait (a tasteful Compose rendering: a realistic-styled portrait card + the aurora orb halo) so the screen is never blank. VisemeController maps amplitude (and optional phoneme hints) to mouth-open/jaw blendshape weights. NazimExpressionController maps NazimState -> expression/idle animation selection. NazimAssets centralizes asset path + existence check. NazimPersona exposes a single Persona named "Nazim" whose systemInstruction teaches the model it can control the phone via the agent tools and must be concise and confirm risky actions. Wrap ALL SceneView calls in try/catch.`,
    files: [
      { path: `${CH}/NazimAssets.kt`, role: 'object: assets/nazim.glb path + hasModel(context) existence check' },
      { path: `${CH}/VisemeController.kt`, role: 'amplitude(+phoneme?) -> blendshape weights (jaw/mouth), smoothed' },
      { path: `${CH}/NazimExpressionController.kt`, role: 'NazimState -> expression preset + idle/breathe/blink animation selection' },
      { path: `${CH}/NazimFallbackPortrait.kt`, role: '@Composable realistic portrait + aurora halo fallback when no GLB / SceneView unavailable' },
      { path: `${CH}/NazimRenderer.kt`, role: 'SceneView/Filament GLB load + per-frame blendshape/transform updates, mirroring AvatarView.kt API usage; try/catch guarded' },
      { path: `${CH}/NazimView.kt`, role: '@Composable NazimView(state,amplitude,persona,modifier): renders NazimRenderer, falls back to NazimFallbackPortrait on any failure' },
      { path: `${CH}/NazimPersona.kt`, role: 'the single Nazim Persona (voice + tool-aware systemInstruction)' },
    ],
  },
  {
    name: 'voice-pipeline',
    mayUse: 'contracts + EXISTING audio/AudioCapture.kt + utils/TextToSpeechHelper.kt',
    guidance: `Add hands-free capability. READ ${SRC}/audio/AudioCapture.kt and ${SRC}/utils/TextToSpeechHelper.kt first. VoiceService is a foreground service (foregroundServiceType=microphone) that owns a single AudioCapture and exposes start/stop + a VoiceMode StateFlow; it posts an ongoing notification with a STOP action (channel created here). WakeWordGate is an INTERFACE plus a simple EnergyWakeWordGate stub (no external dep — a placeholder that can later be swapped for Porcupine/Vosk) so it compiles standalone. VoiceModeController switches IDLE/CONVERSATION/COMMAND. TtsConfirmations wraps TextToSpeechHelper for spoken confirmations in command mode. Manifest registration + FGS permissions are added by integration — just compile.`,
    files: [
      { path: `${VO}/WakeWordGate.kt`, role: 'interface WakeWordGate + EnergyWakeWordGate stub (pluggable, no external dep)' },
      { path: `${VO}/VoiceModeController.kt`, role: 'holds VoiceMode StateFlow; transitions IDLE/CONVERSATION/COMMAND' },
      { path: `${VO}/TtsConfirmations.kt`, role: 'wraps TextToSpeechHelper to speak confirmations/results' },
      { path: `${VO}/VoiceService.kt`, role: 'FGS(type=microphone) owning AudioCapture; STOP notification; bind API' },
    ],
  },
  {
    name: 'agent-ui',
    mayUse: 'contracts + Compose Material 3',
    guidance: `Compose surfaces for the agent (Material 3, dark Obsidian-Aurora aesthetic consistent with ui/theme). Pure composables driven by state + callbacks; no business logic. ConfirmSheet renders a ConfirmRequest (action verb, target app, LITERAL params, risk badge) with Allow once / Always allow / Deny buttons mapping to RuleScope. ModePill shows the AutonomyMode and cycles it. PlanPreviewScreen lists a sequence of AgentAction for PLAN mode. AuditLogScreen lists AuditEntry rows. PermissionCenterScreen manages allow/deny lists + special-permission grant deep-links. OnboardingConsentScreen is the first-run prominent disclosure + affirmative consent + accessibility-enable CTA. KillSwitchOverlay is a persistent floating STOP. AgentConsole shows the live AgentStatus / current step.`,
    files: [
      { path: `${UI}/ConfirmSheet.kt`, role: '@Composable ModalBottomSheet from ConfirmRequest; onDecision(RuleScope?, allowed:Boolean)' },
      { path: `${UI}/ModePill.kt`, role: '@Composable AutonomyMode pill + onCycle' },
      { path: `${UI}/PlanPreviewScreen.kt`, role: '@Composable list of AgentAction (PLAN mode) + onRun/onCancel' },
      { path: `${UI}/AuditLogScreen.kt`, role: '@Composable list of AuditEntry' },
      { path: `${UI}/PermissionCenterScreen.kt`, role: '@Composable allow/deny lists + special-access grant CTAs' },
      { path: `${UI}/OnboardingConsentScreen.kt`, role: '@Composable first-run disclosure + consent + enable-accessibility CTA' },
      { path: `${UI}/KillSwitchOverlay.kt`, role: '@Composable persistent floating STOP button' },
      { path: `${UI}/AgentConsole.kt`, role: '@Composable live AgentStatus / current-step strip' },
    ],
  },
  {
    name: 'agent-coordinator',
    mayUse: 'contracts + Wave-1 concretes via com.example.di.ServiceLocator (READ ServiceLocator.kt, DefaultPhoneControlExecutor.kt, DefaultPermissionEngine.kt, AgentLoopController.kt, GeminiToolMapper.kt, RoomAuditLog.kt for exact signatures)',
    guidance: `AgentCoordinator is the bridge XenoViewModel will delegate to (integration does the wiring; do NOT edit XenoViewModel). It is constructed with an Application and pulls singletons from ServiceLocator. It exposes StateFlows: autonomyMode, pendingConfirm (ConfirmRequest?), agentStatus (AgentStatus). It offers: suspend handleToolCalls(calls): a list of function responses — for each call it maps to an AgentAction (GeminiToolMapper), runs the PermissionEngine with the current mode + latest ScreenState, and either executes (DefaultPhoneControlExecutor), blocks (returns an error response the model sees), or suspends on a Confirm by emitting pendingConfirm and awaiting resolveConfirm(); records each to the AuditLog; respects AgentLoopController step/stuck limits and the KillSwitch. resolveConfirm(allowed, scope) completes the pending action. setMode(mode) updates + persists. READ the Wave-1 files for exact constructor/method names. Use kotlinx.coroutines (CompletableDeferred for the confirm gate).`,
    files: [
      { path: `${SRC}/agent/AgentCoordinator.kt`, role: 'ties permission+executor+loop+audit+killswitch to UI state; the seam XenoViewModel delegates to' },
    ],
  },
]

const LENSES = [
  { key: 'contract', q: 'Correct imports + conformance to committed contracts and the ACTUAL Wave-1 signatures (open the referenced files)? Any unresolved reference, wrong constructor/override, or redefinition that FAILS kotlin compilation?' },
  { key: 'compose', q: 'Correct Jetpack Compose / Android usage for minSdk 24 / targetSdk 36? Composable state hoisting, no side effects in composition, remember/derivedStateOf where needed, SceneView calls try/catch-guarded with a working fallback, service/notification/FGS APIs correct and version-guarded?' },
  { key: 'security', q: 'Does the UI/coordinator faithfully enforce the permission model — literal params shown for irreversible actions, Block truly blocks, kill-switch reachable, no auto-allow of GUARDED, no sensitive data rendered/logged raw?' },
]

function architectPrompt(s) {
  const fileList = s.files.map(f => `  - ${f.path}\n      ${f.role}`).join('\n')
  return `You are the lead engineer for the "${s.name}" stream of Xeno Live (Android/Kotlin/Compose, package root com.example).\n\n${CONTRACTS}\n\nYour stream "mayUse": ${s.mayUse}\n\nStream guidance: ${s.guidance}\n\nWrite these files (Write tool, exact paths):\n${fileList}\n\n${RULES}\n\nReturn the files you actually wrote + cross-stream notes for integration.`
}
function reviewPrompt(file, lens, s) {
  return `Adversarially review the single file ${file} from the "${s.name}" stream (Android/Kotlin/Compose, com.example). READ it, then READ the relevant contract + Wave-1 files to check conformance.\n${CONTRACTS}\nFocus ONLY on this lens: ${lens.q}\nReport concrete real issues only, each with severity + exact fix. Empty array if clean.`
}
function fixPrompt(file, issues) {
  const list = issues.map((i, n) => `${n + 1}. [${i.severity}] ${i.problem}\n   FIX: ${i.fix}`).join('\n')
  return `Apply these confirmed findings to ${file} (Android/Kotlin/Compose). READ it, then Edit to resolve EVERY item without changing its public contract or touching other files. Keep it compiling.\n\n${list}\n\nReturn a one-line summary.`
}

phase('Generate')
const perStream = await pipeline(
  STREAMS,
  s => agent(architectPrompt(s), { schema: WRITTEN_SCHEMA, phase: 'Generate', label: `arch:${s.name}` }),
  async (built, s) => {
    const files = (built && built.writtenFiles && built.writtenFiles.length) ? built.writtenFiles : s.files.map(f => f.path)
    const results = await parallel(files.map(file => async () => {
      const reviews = await parallel(LENSES.map(lens => () => agent(reviewPrompt(file, lens, s), { schema: FINDINGS_SCHEMA, phase: 'Review', label: `rev:${lens.key}:${short(file)}` })))
      const issues = reviews.filter(Boolean).flatMap(r => r.issues || []).filter(i => i.severity === 'high' || i.severity === 'medium')
      if (!issues.length) return { file, fixed: false }
      await agent(fixPrompt(file, issues), { phase: 'Fix', label: `fix:${short(file)}` })
      return { file, fixed: true, issues: issues.length }
    }))
    return { stream: s.name, files: results }
  }
)
const flat = perStream.filter(Boolean)
log(`Wave 2 done: ${flat.length} streams`)
return { streams: flat }
