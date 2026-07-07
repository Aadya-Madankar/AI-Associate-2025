export const meta = {
  name: 'xeno-build-wave1-core',
  description: 'Build the core phone-control engine for Xeno Live (accessibility, permission, agent loop, tools, Gemini tool-protocol, audit, redaction, DI) via coherent per-stream generation + adversarial per-file review/fix',
  phases: [
    { title: 'Generate', detail: 'one architect per stream writes its files coherently against the committed contracts' },
    { title: 'Review', detail: '3 adversarial lenses per file: contract-conformance, android-correctness, security' },
    { title: 'Fix', detail: 'one fixer per file applies confirmed high/medium findings' },
  ],
}

const CWD = '/Users/aadyamadankar/working/xeno-live'
const SRC = `${CWD}/app/src/main/java/com/example`
const short = p => p.split('/').pop()

const CONTRACTS = `The committed CONTRACTS already exist on disk — import and implement them, do NOT redefine them:
- com.example.permission.PermissionModel (AutonomyMode, RiskTier, ActionType, AgentAction, SecureReason, PermissionDecision[Allow/Confirm/Block], ConfirmRequest, RuleScope)
- com.example.permission.PermissionEngine (interface), SecureContextDetector (interface), RuleStore (interface)
- com.example.accessibility.ScreenModel (UiElement, ScreenState)
- com.example.accessibility.AccessibilityController (interface) + object Accessibility { var controller }
- com.example.agent.AgentModel (ToolResult[Success/Failure/Completed], ToolDeclaration, AgentTool interface, PhoneControlExecutor interface, AgentStatus)
- com.example.audit.AuditModel (AuditOutcome, AuditEntry, AuditLog interface)
- com.example.voice.VoiceMode, com.example.character.NazimState
- com.example.core (CompanionState, Sender, ChatMessage), com.example.models.Persona`

const RULES = `HARD RULES:
1. Create ONLY new files at the exact paths listed for your stream. Do NOT edit ANY existing file (not the manifest, not build.gradle, not XenoViewModel, not LiveProtocol, not Persona).
2. Depend ONLY on: the committed contracts above, the Android SDK, kotlinx.coroutines, and the cross-stream INTERFACES explicitly listed in your stream's "mayUse". Do NOT reference another stream's concrete class.
3. Production-quality idiomatic Kotlin, targetSdk 36 / minSdk 24. Guard version-specific APIs with Build.VERSION.SDK_INT checks. Add concise KDoc to every public type.
4. It MUST compile against the contracts: correct package, correct imports, correct override signatures. No TODOs that break compilation, no unresolved references.
5. First READ ${CWD}/ARCHITECTURE.md (the authoritative blueprint) and the contract files under ${SRC}/{permission,accessibility,agent,audit,voice,character} before writing.`

const WRITTEN_SCHEMA = {
  type: 'object', additionalProperties: false,
  properties: {
    writtenFiles: { type: 'array', items: { type: 'string' }, description: 'absolute paths actually written' },
    notes: { type: 'string', description: 'cross-stream assumptions, anything integration needs to know' },
  },
  required: ['writtenFiles', 'notes'],
}

const FINDINGS_SCHEMA = {
  type: 'object', additionalProperties: false,
  properties: {
    issues: {
      type: 'array',
      items: {
        type: 'object', additionalProperties: false,
        properties: {
          severity: { type: 'string', enum: ['high', 'medium', 'low'] },
          problem: { type: 'string' },
          fix: { type: 'string', description: 'concrete change to make' },
        },
        required: ['severity', 'problem', 'fix'],
      },
    },
  },
  required: ['issues'],
}

// ---- Stream definitions -----------------------------------------------------
const A = `${SRC}/accessibility`
const P = `${SRC}/permission`
const AG = `${SRC}/agent`
const T = `${SRC}/agent/tools`
const STREAMS = [
  {
    name: 'accessibility-engine',
    mayUse: 'contracts only',
    guidance: `Implement the read+act engine. The service implements AccessibilityController and publishes itself to Accessibility.controller in onServiceConnected (clear on unbind). ScreenReader does a DFS of getRootInActiveWindow(), keeps only nodes that isVisibleToUser() AND (clickable|editable|scrollable|longClickable|text!=null), assigns indices, caches index->Rect, version-guards recycle() (API<33). GestureDispatcher builds tap/swipe/longpress GestureDescriptions. NodeActionExecutor does performAction with dispatchGesture fallback at bounds center. takeScreenshot is API30+ and rate-limited. Manifest registration is done later by integration — just make the class compile.`,
    files: [
      { path: `${A}/ScreenReader.kt`, role: 'DFS AccessibilityNodeInfo tree -> List<UiElement> + index->Rect cache; visible/interactive filter; version-guarded recycle; cap ~30 elements' },
      { path: `${A}/GestureDispatcher.kt`, role: 'tap/swipe/long-press via GestureDescription/StrokeDescription on a Handler; suspend wrappers over dispatchGesture callbacks' },
      { path: `${A}/NodeActionExecutor.kt`, role: 'click/setText/scroll by index via performAction, fallback to GestureDispatcher tap at bounds center' },
      { path: `${A}/GlobalActions.kt`, role: 'thin wrappers over performGlobalAction (BACK/HOME/RECENTS/NOTIFICATIONS)' },
      { path: `${A}/ScreenSerializer.kt`, role: 'ScreenState -> compact JSON string (org.json) for the model; omit nulls; include index/role/text/bounds/flags' },
      { path: `${A}/AccessibilityAvailability.kt`, role: 'object: isServiceEnabled(context) via Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES; intent to ACTION_ACCESSIBILITY_SETTINGS' },
      { path: `${A}/AgentAccessibilityService.kt`, role: 'AccessibilityService implementing AccessibilityController; tracks foreground package via onAccessibilityEvent; wires ScreenReader/NodeActionExecutor/GestureDispatcher/GlobalActions; sets Accessibility.controller' },
      { path: `${CWD}/app/src/main/res/xml/agent_a11y_config.xml`, role: 'accessibility-service config: canRetrieveWindowContent=true, canPerformGestures=true, typeAllMask, feedbackGeneric, flagRetrieveInteractiveWindows|flagReportViewIds' },
    ],
  },
  {
    name: 'permission-impl',
    mayUse: 'contracts only',
    guidance: `Implement the permission system. RiskClassifier maps ActionType->RiskTier (see ARCHITECTURE.md §5 table) and computes effective = MAX(static, context); irreversible types (SEND_MESSAGE/PLACE_CALL/MAKE_PURCHASE/DELETE_DATA) never qualify SAFE. DefaultSecureContextDetector returns a SecureReason from a ScreenState (denylisted package, password element, OTP/CVV/PIN regex on text/hint/resourceId, secure flag). DefaultPermissionEngine applies first-match: denylist -> forced-ask -> secure-context -> per-rule+mode, returning PermissionDecision. DataStoreRuleStore + AutonomyModeStore use androidx.datastore.preferences (already a dependency, uncomment if needed via integration — for now use Preferences DataStore API). RateLimiter is a token bucket + consecutive-irreversible cap + auto-fallback counters (3 consecutive blocks or 20/session -> ASK). KillSwitch exposes a StateFlow<Boolean> and trigger().`,
    files: [
      { path: `${P}/DenyLists.kt`, role: 'curated banking/wallet/authenticator package set + sensitive keyword regexes (OTP|CVV|PIN|password|card number|IBAN), user-editable later' },
      { path: `${P}/RiskClassifier.kt`, role: 'static ActionType->RiskTier table; classify(action,context): RiskTier = MAX(static, runtime)' },
      { path: `${P}/DefaultSecureContextDetector.kt`, role: 'implements SecureContextDetector: ScreenState -> SecureReason' },
      { path: `${P}/DefaultPermissionEngine.kt`, role: 'implements PermissionEngine: 4-layer first-match -> PermissionDecision; builds ConfirmRequest with literal params' },
      { path: `${P}/DataStoreRuleStore.kt`, role: 'implements RuleStore over Preferences DataStore; session vs persisted grants' },
      { path: `${P}/AutonomyModeStore.kt`, role: 'persist AutonomyMode + expose Flow<AutonomyMode>; default ASK' },
      { path: `${P}/RateLimiter.kt`, role: 'token bucket + consecutive-irreversible cap + auto-fallback counters' },
      { path: `${P}/KillSwitch.kt`, role: 'object/class with StateFlow<Boolean> armed; trigger() sets tripped; reset()' },
    ],
  },
  {
    name: 'agent-core',
    mayUse: 'contracts + com.example.accessibility.AccessibilityController + com.example.permission.* + com.example.audit.AuditLog + com.example.live tool DTOs (com.example.live.LiveTool etc.) which the gemini-tool-protocol stream defines',
    guidance: `Implement the agent loop + executor. DefaultPhoneControlExecutor holds a ToolRegistry (Map<String,AgentTool>), implements declarations() and execute(): it looks up the tool, runs it, returns ToolResult; unknown tool -> Failure. AgentLoopController enforces a step cap (~25), keeps a ring buffer of (name+argsHash+screenHash) to detect repeats/stuck, and a cancellation flag. AgentToolSchemas provides JSON-schema strings. GeminiToolMapper converts ToolDeclaration->LiveTool DTO and a LiveFunctionCall(name,args)->AgentAction (mapping tool name+args to ActionType + params + reversibility, used by the permission gate). It is OK to reference com.example.live.LiveTool / LiveFunctionDeclaration / LiveFunctionCall by name (the gemini-tool-protocol stream creates them this same wave).`,
    files: [
      { path: `${AG}/ToolRegistry.kt`, role: 'builds + holds all AgentTool instances from an android Context; exposes byName + declarations()' },
      { path: `${AG}/DefaultPhoneControlExecutor.kt`, role: 'implements PhoneControlExecutor using ToolRegistry' },
      { path: `${AG}/AgentLoopController.kt`, role: 'step cap, repeat/stuck detection (ring buffer), cancellation token; pure logic, unit-testable' },
      { path: `${AG}/AgentToolSchemas.kt`, role: 'object exposing parametersJsonSchema strings for each tool' },
      { path: `${AG}/GeminiToolMapper.kt`, role: 'ToolDeclaration<->LiveTool DTO; LiveFunctionCall -> AgentAction classification (ActionType, params, reversible)' },
    ],
  },
  {
    name: 'agent-tools-accessibility',
    mayUse: 'contracts + com.example.accessibility.Accessibility/AccessibilityController',
    guidance: `Each file is ONE class implementing com.example.agent.AgentTool. UI tools use Accessibility.controller (null-check, return ToolResult.Failure if not ready). declaration carries a ToolDeclaration(name, description, parametersJsonSchema as a JSON string). execute() parses args (Map<String,Any?>), performs the action via the controller, and returns ToolResult.Success (including a fresh ScreenState from readScreen() where useful) or Failure. GetScreenTool returns the current ScreenState. TaskCompleteTool returns ToolResult.Completed. Keep each class small and consistent.`,
    files: [
      { path: `${T}/GetScreenTool.kt`, role: 'get_screen -> reads & returns ScreenState' },
      { path: `${T}/TapTool.kt`, role: 'tap {index} via controller.tap' },
      { path: `${T}/InputTextTool.kt`, role: 'input_text {index,text}; refuse if target element.password' },
      { path: `${T}/SwipeTool.kt`, role: 'swipe {x1,y1,x2,y2,durationMs?}' },
      { path: `${T}/ScrollTool.kt`, role: 'scroll {index,forward}' },
      { path: `${T}/LongPressTool.kt`, role: 'long_press {index}' },
      { path: `${T}/PressBackTool.kt`, role: 'press_back' },
      { path: `${T}/PressHomeTool.kt`, role: 'press_home' },
      { path: `${T}/PressRecentsTool.kt`, role: 'press_recents' },
      { path: `${T}/OpenNotificationsTool.kt`, role: 'open_notifications' },
      { path: `${T}/TakeScreenshotTool.kt`, role: 'take_screenshot (API30+); returns success/failure note (no raw image to model)' },
      { path: `${T}/TaskCompleteTool.kt`, role: 'task_complete {success,summary} -> ToolResult.Completed' },
    ],
  },
  {
    name: 'agent-tools-intent',
    mayUse: 'contracts + android Context (constructor arg)',
    guidance: `Each file is ONE AgentTool that launches an Intent via a Context (constructor: class XxxTool(private val context: Context)). ALWAYS resolveActivity() guard before startActivity and add FLAG_ACTIVITY_NEW_TASK. Prefer drafts (ACTION_DIAL, ACTION_SENDTO) over committed sends. Return Success with a short message; Failure if no handler. Use AlarmClock.* for alarm/timer, CalendarContract for events, geo:/google.navigation: for maps, ACTION_WEB_SEARCH for search, Settings.ACTION_* for settings pages.`,
    files: [
      { path: `${T}/OpenAppTool.kt`, role: 'open_app {package|appName} via getLaunchIntentForPackage' },
      { path: `${T}/OpenUrlTool.kt`, role: 'open_url {url} ACTION_VIEW' },
      { path: `${T}/WebSearchTool.kt`, role: 'web_search {query} ACTION_WEB_SEARCH' },
      { path: `${T}/MapsNavigateTool.kt`, role: 'navigate {destination} google.navigation:q=' },
      { path: `${T}/ShareTool.kt`, role: 'share {text} ACTION_SEND + chooser' },
      { path: `${T}/DialPrefillTool.kt`, role: 'dial {number} ACTION_DIAL (no auto-call)' },
      { path: `${T}/SmsDraftTool.kt`, role: 'sms_draft {number,body} ACTION_SENDTO smsto: (user presses send)' },
      { path: `${T}/EmailDraftTool.kt`, role: 'email_draft {to,subject,body} ACTION_SENDTO mailto:' },
      { path: `${T}/SetAlarmTool.kt`, role: 'set_alarm {hour,minute,message?} AlarmClock.ACTION_SET_ALARM' },
      { path: `${T}/SetTimerTool.kt`, role: 'set_timer {seconds,message?} AlarmClock.ACTION_SET_TIMER' },
      { path: `${T}/AddCalendarEventTool.kt`, role: 'add_event {title,beginMs,endMs?} CalendarContract Insert' },
      { path: `${T}/OpenSettingsPageTool.kt`, role: 'open_settings {page} Settings.ACTION_* allowlist' },
    ],
  },
  {
    name: 'agent-tools-system',
    mayUse: 'contracts + android Context (constructor arg)',
    guidance: `Each file is ONE AgentTool. Constructor takes Context. TorchTool uses CameraManager.setTorchMode. MediaVolumeTool uses AudioManager STREAM_MUSIC with FLAG_SHOW_UI. MediaPlayPauseTool uses AudioManager.dispatchMediaKeyEvent. BrightnessTool uses Settings.System (note WRITE_SETTINGS needed -> if not granted, return Failure with guidance). DndTool uses NotificationManager.setInterruptionFilter (ACCESS_NOTIFICATION_POLICY). WifiPanelTool/BluetoothSettingsTool just deep-link to the settings panel (toggling is blocked on modern Android). PlaceCallTool and SendMessageDraftTool are GUARDED — they still only PREPARE (dial prefill / sms draft) unless a committed variant is explicitly needed; mark reversible=false in their mapping.`,
    files: [
      { path: `${T}/TorchTool.kt`, role: 'torch {on} CameraManager.setTorchMode' },
      { path: `${T}/MediaVolumeTool.kt`, role: 'set_volume {level0to100} AudioManager STREAM_MUSIC' },
      { path: `${T}/MediaPlayPauseTool.kt`, role: 'media_play_pause dispatchMediaKeyEvent' },
      { path: `${T}/BrightnessTool.kt`, role: 'set_brightness {level0to100} Settings.System (WRITE_SETTINGS guarded)' },
      { path: `${T}/DndTool.kt`, role: 'set_dnd {on} NotificationManager.setInterruptionFilter (policy access guarded)' },
      { path: `${T}/WifiPanelTool.kt`, role: 'open_wifi_panel Settings.Panel.ACTION_WIFI' },
      { path: `${T}/BluetoothSettingsTool.kt`, role: 'open_bluetooth_settings ACTION_BLUETOOTH_SETTINGS' },
    ],
  },
  {
    name: 'gemini-tool-protocol',
    mayUse: 'moshi (@JsonClass) like the existing LiveProtocol.kt',
    guidance: `Create a NEW file with the Moshi DTOs for Gemini Live function-calling, matching the style of the existing com.example.live.LiveProtocol.kt (which you should READ first). Do NOT edit LiveProtocol.kt. Provide: LiveTool(functionDeclarations: List<LiveFunctionDeclaration>), LiveFunctionDeclaration(name, description, parameters: Map<String,Any?>? as a raw schema), incoming LiveToolCall(functionCalls: List<LiveFunctionCall>), LiveFunctionCall(id, name, args: Map<String,Any?>?), LiveToolCallCancellation(ids: List<String>?), and the outgoing LiveToolResponseRequest(toolResponse: LiveToolResponse(functionResponses: List<LiveFunctionResponse>)), LiveFunctionResponse(id, name, response: Map<String,Any?>). Use Moshi types compatible with the existing setup (Map fields may need a Moshi adapter note in KDoc).`,
    files: [
      { path: `${SRC}/live/ToolProtocol.kt`, role: 'Moshi DTOs for tools/toolCall/toolResponse/cancellation (new file; does not touch LiveProtocol.kt)' },
    ],
  },
  {
    name: 'audit-data',
    mayUse: 'contracts + androidx.room',
    guidance: `Room persistence for the audit log. AuditEntity is the @Entity; AuditDao the DAO (insert + recent); AuditDatabase the RoomDatabase; RoomAuditLog implements com.example.audit.AuditLog over the DAO (suspend, IO dispatcher); AuditMappers convert entity<->AuditEntry; ParamsHasher hashes literal params (SHA-256 hex) so raw values are never stored. Room + KSP are already configured in build.gradle.`,
    files: [
      { path: `${SRC}/audit/AuditEntity.kt`, role: 'Room @Entity mirroring AuditEntry (paramsHash only)' },
      { path: `${SRC}/audit/AuditDao.kt`, role: '@Dao insert + recent(limit)' },
      { path: `${SRC}/audit/AuditDatabase.kt`, role: '@Database RoomDatabase with singleton builder' },
      { path: `${SRC}/audit/RoomAuditLog.kt`, role: 'implements AuditLog over the DAO' },
      { path: `${SRC}/audit/AuditMappers.kt`, role: 'entity<->AuditEntry mapping' },
      { path: `${SRC}/audit/ParamsHasher.kt`, role: 'SHA-256 hex of a params Map' },
    ],
  },
  {
    name: 'security-redaction',
    mayUse: 'contracts',
    guidance: `On-device redaction applied to a ScreenState before it is serialized to the cloud. SensitivePatterns holds regexes (OTP, CVV, card numbers, IBAN, emails optional, long digit runs). ScreenRedactor returns a copy of ScreenState with sensitive text replaced by a placeholder and marks secure=true when a password/OTP element is present. RedactionPolicy toggles strictness.`,
    files: [
      { path: `${SRC}/security/SensitivePatterns.kt`, role: 'regexes for OTP/CVV/card/IBAN/secret-shaped strings' },
      { path: `${SRC}/security/ScreenRedactor.kt`, role: 'ScreenState -> redacted ScreenState' },
      { path: `${SRC}/security/RedactionPolicy.kt`, role: 'policy flags (strict/lenient)' },
    ],
  },
  {
    name: 'di-bootstrap',
    mayUse: 'ALL contracts + the concrete impls created this wave (DefaultPermissionEngine, DefaultSecureContextDetector, DataStoreRuleStore, AutonomyModeStore, RateLimiter, KillSwitch, DefaultPhoneControlExecutor, ToolRegistry, RoomAuditLog, ScreenRedactor)',
    guidance: `A simple manual ServiceLocator (object) providing lazily-initialized singletons given an Application Context: accessibility controller accessor, ToolRegistry, DefaultPhoneControlExecutor, DefaultPermissionEngine (+ detector + rule store + classifier), AutonomyModeStore, RateLimiter, KillSwitch, RoomAuditLog, ScreenRedactor. This is the ONE file allowed to reference concrete impls by name. Provide init(app: Application). Keep it null-safe and side-effect free until init. (XenoViewModel will be wired to this during integration — do not edit XenoViewModel here.)`,
    files: [
      { path: `${SRC}/di/ServiceLocator.kt`, role: 'manual DI singletons wiring all impls; init(app)' },
    ],
  },
]

const LENSES = [
  { key: 'contract', q: 'Does it correctly import and conform to the committed contracts (right package, exact override signatures, sealed/enum members)? Any unresolved reference, wrong import, or signature mismatch that would FAIL kotlin compilation? Does it accidentally redefine a contract type or edit an existing file?' },
  { key: 'android', q: 'Are the Android APIs used correctly for minSdk 24 / targetSdk 36? Version-gated APIs guarded with Build.VERSION.SDK_INT? Intents resolveActivity-guarded with NEW_TASK? Threading/coroutine misuse? Resource/XML validity? Anything that compiles but crashes at runtime?' },
  { key: 'security', q: 'Permission/secure-context correctness: does it respect the SAFE/GUARDED/BLOCKED model, refuse password fields, avoid leaking sensitive data, and never bypass the permission gate? For tools: is the risk classification + reversibility honest?' },
]

function architectPrompt(s) {
  const fileList = s.files.map(f => `  - ${f.path}\n      ${f.role}`).join('\n')
  return `You are the lead engineer for the "${s.name}" stream of the Xeno Live phone-control agent (Android/Kotlin/Compose, package root com.example).

${CONTRACTS}

Your stream "mayUse": ${s.mayUse}

Stream guidance: ${s.guidance}

Write these files (create each with the Write tool, exact paths):
${fileList}

${RULES}

When done, return the list of files you actually wrote and any cross-stream notes integration must know.`
}

function reviewPrompt(file, lens, s) {
  return `Adversarially review the single file ${file} from the "${s.name}" stream of an Android/Kotlin app (package root com.example).
First READ the file. Then READ the relevant contract files under ${SRC}/{permission,accessibility,agent,audit} to check conformance.
${CONTRACTS}
Focus EXCLUSIVELY on this lens: ${lens.q}
Report concrete, real issues only (no style nits). For each, give the severity and the exact fix. If the file is correct on this lens, return an empty issues array.`
}

function fixPrompt(file, issues) {
  const list = issues.map((i, n) => `${n + 1}. [${i.severity}] ${i.problem}\n   FIX: ${i.fix}`).join('\n')
  return `Apply these confirmed review findings to the single file ${file} (Android/Kotlin). READ it first, then Edit it to resolve EVERY item below WITHOUT changing its public contract or touching other files. Keep it compiling against the committed contracts.\n\n${list}\n\nReturn a one-line summary of what you changed.`
}

// ---- Orchestration ----------------------------------------------------------
phase('Generate')
const perStream = await pipeline(
  STREAMS,
  // Stage 1: coherent generation
  s => agent(architectPrompt(s), { schema: WRITTEN_SCHEMA, phase: 'Generate', label: `arch:${s.name}` }),
  // Stage 2: per-file adversarial review + fix (no barrier between streams)
  async (built, s) => {
    const files = (built && built.writtenFiles && built.writtenFiles.length) ? built.writtenFiles : s.files.map(f => f.path)
    const results = await parallel(files.map(file => async () => {
      const reviews = await parallel(
        LENSES.map(lens => () =>
          agent(reviewPrompt(file, lens, s), { schema: FINDINGS_SCHEMA, phase: 'Review', label: `rev:${lens.key}:${short(file)}` })
        )
      )
      const issues = reviews.filter(Boolean).flatMap(r => r.issues || []).filter(i => i.severity === 'high' || i.severity === 'medium')
      if (!issues.length) return { file, fixed: false, issues: 0 }
      await agent(fixPrompt(file, issues), { phase: 'Fix', label: `fix:${short(file)}` })
      return { file, fixed: true, issues: issues.length }
    }))
    return { stream: s.name, files: results }
  }
)

const flat = perStream.filter(Boolean)
const totalFiles = flat.reduce((n, s) => n + (s.files ? s.files.length : 0), 0)
const fixedFiles = flat.reduce((n, s) => n + (s.files ? s.files.filter(f => f.fixed).length : 0), 0)
log(`Wave 1 done: ${flat.length} streams, ${totalFiles} files, ${fixedFiles} fixed after review`)
return { streams: flat, totalFiles, fixedFiles }
