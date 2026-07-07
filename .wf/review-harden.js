export const meta = {
  name: 'xeno-live-audit-113',
  description: 'Hierarchical 113-agent audit/harden of Xeno Live: 100 workers -> 10 sub-managers -> 2 directors -> 1 main manager',
  phases: [
    { title: 'Workers', detail: '100 workers: 10 domains x 10 lenses audit every file/function' },
    { title: 'SubManagers', detail: '10 sub-managers consolidate each domain' },
    { title: 'Directors', detail: '2 directors merge domains by theme' },
    { title: 'Main', detail: '1 main manager produces the final prioritized fix plan + go/no-go' },
  ],
}

const SRC = '/Users/aadyamadankar/working/xeno-live/app/src/main/java/com/example'
const APP = '/Users/aadyamadankar/working/xeno-live/app'

// Condensed contract for adherence checks (full contract was used during authoring).
const CONTRACT = `XENO LIVE contract essentials:
- Gemini Live S2S via OkHttp WebSocket. Model "gemini-3.1-flash-live-preview". Endpoint wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=KEY.
  setup msg: {setup:{model:"models/<model>", generationConfig:{responseModalities:["AUDIO"], speechConfig:{voiceConfig:{prebuiltVoiceConfig:{voiceName}}}}, systemInstruction:{parts:[{text}]}}}.
  audio up: {realtimeInput:{audio:{data:<b64>, mimeType:"audio/pcm;rate=16000"}}}. PCM16 mono 16k in, 24k out.
  incoming: serverContent.modelTurn.parts[].inlineData(audio/pcm)|text ; turnComplete|interrupted|generationComplete ; setupComplete.
- Packages: core(CompanionState{IDLE,CONNECTING,LISTENING,THINKING,SPEAKING,ERROR},Sender{USER,XENO},ChatMessage),
  live(LiveProtocol,GeminiLiveClient w/ Listener{onOpen,onSetupComplete,onAudio,onText,onTurnComplete,onInterrupted,onError,onClosed}),
  audio(AudioCapture.start(onPcm,onAmplitude)/stop/isRecording, AudioStreamPlayer.start/write/clear/stop/onAmplitude),
  avatar(LipSyncController pure-kotlin weights{jawOpen,mouthOpen,mouthFunnel,eyeBlinkLeft,eyeBlinkRight}+headSway; AvatarView SceneView+fallback AuroraOrb),
  models(Persona +voiceName; xeno->Fenrir aria->Aoede maya->Kore atlas->Charon),
  ui.theme(MyApplicationTheme,XenoColors,XenoTypography,XenoShapes,Motion; Obsidian Aurora dark),
  ui.components(AuroraBackground,AuroraOrb,MicButton,StatusPill,TranscriptOverlay,PersonaSwitcher),
  ui.screens(LiveScreen(viewModel)), viewmodels(XenoViewModel AndroidViewModel w/ StateFlows selectedPersona,companionState,messages,amplitude,isConnected,isMicActive,errorMessage,apiKeyMissing; selectPersona,toggleLive,startSession,stopSession,sendText,clearError), MainActivity(thin shell, accompanist RECORD_AUDIO).
- SceneView dep is io.github.sceneview:sceneview (Compose 'SceneView{}' API, v4.x). minSdk24 compileSdk36 Kotlin2.2.10 Java11 Compose BOM 2024.09 Material3.`

const DOMAINS = [
  { name: 'live-pipeline', files: [`${SRC}/live/LiveProtocol.kt`, `${SRC}/live/GeminiLiveClient.kt`] },
  { name: 'audio', files: [`${SRC}/audio/AudioCapture.kt`, `${SRC}/audio/AudioStreamPlayer.kt`] },
  { name: 'avatar-3d', files: [`${SRC}/avatar/AvatarView.kt`, `${SRC}/avatar/LipSyncController.kt`] },
  { name: 'ui-core', files: [`${SRC}/ui/components/AuroraBackground.kt`, `${SRC}/ui/components/AuroraOrb.kt`, `${SRC}/ui/components/MicButton.kt`, `${SRC}/ui/components/StatusPill.kt`] },
  { name: 'ui-overlay', files: [`${SRC}/ui/components/TranscriptOverlay.kt`, `${SRC}/ui/components/PersonaSwitcher.kt`] },
  { name: 'theme', files: [`${SRC}/ui/theme/Color.kt`, `${SRC}/ui/theme/Type.kt`, `${SRC}/ui/theme/Shape.kt`, `${SRC}/ui/theme/Motion.kt`, `${SRC}/ui/theme/Theme.kt`] },
  { name: 'viewmodel-state', files: [`${SRC}/viewmodels/XenoViewModel.kt`, `${SRC}/core/CompanionState.kt`] },
  { name: 'screen-activity', files: [`${SRC}/ui/screens/LiveScreen.kt`, `${SRC}/MainActivity.kt`, `${SRC}/models/Persona.kt`] },
  { name: 'build-config', files: [`${APP}/build.gradle.kts`, `${APP}/src/main/AndroidManifest.xml`] },
  { name: 'cross-cutting', files: [`${SRC}/viewmodels/XenoViewModel.kt`, `${SRC}/ui/screens/LiveScreen.kt`, `${SRC}/live/GeminiLiveClient.kt`, `${SRC}/avatar/AvatarView.kt`] },
]

const LENSES = [
  { key: 'compile', ask: 'COMPILE CORRECTNESS: missing/incorrect imports, wrong package, type mismatches, unresolved references, unused imports, Kotlin syntax. Will kotlinc reject this?' },
  { key: 'contract', ask: 'CONTRACT ADHERENCE: do class names, function signatures, package names, enum values match the shared contract EXACTLY so other files link?' },
  { key: 'api-use', ask: 'API MISUSE: correct usage of Android (AudioRecord/AudioTrack/Base64), Compose/Material3, OkHttp WebSocket, Moshi (@JsonClass+KSP), SceneView 4.x (SceneView{} composable, RenderableManager.setMorphWeights). Flag wrong/nonexistent APIs.' },
  { key: 'concurrency', ask: 'THREADING/CONCURRENCY: main-thread Compose state updates, viewModelScope/Dispatchers, AudioRecord/Track on worker threads, WebSocket callback threads, races, missing synchronization.' },
  { key: 'lifecycle', ask: 'LIFECYCLE/LEAKS: AudioRecord/AudioTrack released, WebSocket closed, coroutine cancellation, ViewModel onCleared, no leaked Context/listeners.' },
  { key: 'edge', ask: 'NULL-SAFETY/EDGE CASES: nullable handling, empty/partial server messages, permission denied, API key missing, barge-in/interruption, reconnect, error states.' },
  { key: 'protocol', ask: 'DATA-FLOW/PROTOCOL: Gemini Live JSON shape correctness, base64 framing, PCM sample-rate handling (16k up/24k down), amplitude wiring, state transitions IDLE->CONNECTING->LISTENING->SPEAKING.' },
  { key: 'design', ask: 'DESIGN FIDELITY: matches Obsidian Aurora tokens (colors/type/shape/motion), minimal premium aesthetic, persona theming, glass/halo treatment.' },
  { key: 'ux-product', ask: 'UX/PRODUCT (PM/CEO lens): does this actually deliver a working, delightful talk-to-the-avatar experience? Missing affordances, confusing states, accessibility, first-run/empty/error UX.' },
  { key: 'perf', ask: 'PERFORMANCE/MEMORY: per-frame allocations, recompositions, buffer copies, audio latency, Canvas/3D cost, leaks/growth.' },
]

const FINDINGS_SCHEMA = {
  type: 'object', additionalProperties: false,
  properties: {
    findings: { type: 'array', items: { type: 'object', additionalProperties: false,
      properties: {
        file: { type: 'string' }, line: { type: 'string' },
        severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
        issue: { type: 'string' }, fix: { type: 'string' },
      }, required: ['file', 'severity', 'issue', 'fix'] } },
    summary: { type: 'string' },
  }, required: ['findings', 'summary'],
}

const REPORT_SCHEMA = {
  type: 'object', additionalProperties: false,
  properties: {
    domain: { type: 'string' },
    blockers: { type: 'array', items: { type: 'string' } },
    majors: { type: 'array', items: { type: 'string' } },
    minors: { type: 'array', items: { type: 'string' } },
    patches: { type: 'array', items: { type: 'object', additionalProperties: false,
      properties: { file: { type: 'string' }, change: { type: 'string' } }, required: ['file', 'change'] } },
    health: { type: 'string', enum: ['green', 'yellow', 'red'] },
  }, required: ['domain', 'blockers', 'majors', 'minors', 'patches', 'health'],
}

const FINAL_SCHEMA = {
  type: 'object', additionalProperties: false,
  properties: {
    goNoGo: { type: 'string', enum: ['go', 'go-with-fixes', 'no-go'] },
    orderedActions: { type: 'array', items: { type: 'object', additionalProperties: false,
      properties: { priority: { type: 'number' }, file: { type: 'string' }, action: { type: 'string' } },
      required: ['priority', 'file', 'action'] } },
    topRisks: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string' },
  }, required: ['goNoGo', 'orderedActions', 'topRisks', 'summary'],
}

phase('Workers')
const domainReports = await parallel(DOMAINS.map((d) => async () => {
  const fileList = d.files.join(', ')
  const workerFindings = await parallel(LENSES.map((lens) => () =>
    agent(
      `You are a senior Android/Kotlin auditor (WORKER) on domain "${d.name}". Read these files with the Read tool: ${fileList}. ` +
      `Audit ONLY through this lens — ${lens.ask}\n\nShared contract:\n${CONTRACT}\n\n` +
      `Report concrete, real findings (no nitpicks-as-blockers). For each: file, approximate line, severity, the issue, and a precise fix. ` +
      `If the lens finds nothing real, return an empty findings array with a one-line summary. Do NOT edit files.`,
      { label: `${d.name}:${lens.key}`, phase: 'Workers', schema: FINDINGS_SCHEMA }
    ).then(r => ({ lens: lens.key, ...r })).catch(() => null)
  ))
  const valid = workerFindings.filter(Boolean)
  const report = await agent(
    `You are the SUB-MANAGER for domain "${d.name}". Your 10 workers audited ${fileList} across 10 lenses. ` +
    `Here are their findings as JSON:\n${JSON.stringify(valid).slice(0, 12000)}\n\n` +
    `Consolidate: dedupe overlapping findings, drop false positives, keep only real issues. Classify into blockers (won't compile / breaks core voice or avatar), majors (works but wrong/risky), minors (polish). ` +
    `Provide concrete patches {file, change} for the blockers and majors. Set health green/yellow/red. Be terse and concrete.`,
    { label: `mgr:${d.name}`, phase: 'SubManagers', schema: REPORT_SCHEMA }
  ).catch(() => ({ domain: d.name, blockers: [], majors: [], minors: [], patches: [], health: 'yellow' }))
  return report
}))

phase('Directors')
const reportsJson = (slice) => JSON.stringify(slice).slice(0, 16000)
const platformReports = domainReports.slice(0, 5)   // live, audio, avatar, ui-core, ui-overlay
const experienceReports = domainReports.slice(5)     // theme, viewmodel, screen, build, cross-cutting
const directorPrompt = (title, scope, reports) =>
  `You are a DIRECTOR over the "${title}" area (${scope}). Merge these domain sub-manager reports:\n${reportsJson(reports)}\n\n` +
  `Produce: the top cross-domain blockers, integration risks where domains meet, and a single ordered fix plan for your area. Be concrete and ruthless about what actually stops the app from building/working.`
const [director1, director2] = await parallel([
  () => agent(directorPrompt('Platform/Engine', 'live + audio + avatar + ui components', platformReports), { label: 'director:platform', phase: 'Directors' }),
  () => agent(directorPrompt('Experience/Integration', 'theme + viewmodel + screen + build + cross-cutting', experienceReports), { label: 'director:experience', phase: 'Directors' }),
])

phase('Main')
const final = await agent(
  `You are the MAIN ENGINEERING MANAGER for Xeno Live. Two directors report to you.\n\n` +
  `PLATFORM DIRECTOR:\n${director1}\n\nEXPERIENCE DIRECTOR:\n${director2}\n\n` +
  `Produce the FINAL plan: goNoGo (go / go-with-fixes / no-go), a single globally-ordered action list (priority number, file, precise action) covering every blocker and major in dependency order so the app COMPILES then WORKS end-to-end (voice S2S + avatar + UI), and the top risks. Be specific and actionable — this list will be executed verbatim.`,
  { label: 'main-manager', phase: 'Main', schema: FINAL_SCHEMA }
)

return {
  agentCount: domainReports.length /*sub-managers*/ + (DOMAINS.length * LENSES.length) /*workers*/ + 2 /*directors*/ + 1 /*main*/,
  domainHealth: domainReports.map(r => ({ domain: r.domain, health: r.health, blockers: r.blockers.length, majors: r.majors.length })),
  final,
}
