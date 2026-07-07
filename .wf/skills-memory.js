export const meta = {
  name: 'xeno-skills-memory',
  description: 'A per-step agent state machine + on-device self-authored skills: Nazim records a sequence of phone tools (each step a tracked state), saves it as a named skill on-device (JSON file, no DB), and recalls/replays it later through the permission gate.',
  phases: [
    { title: 'Build', detail: 'agent state machine + skill model + on-device store + save/list/recall tools' },
    { title: 'Integrate', detail: 'wire state machine into the coordinator/UI, and skills into registry/mapper/schemas/classifier/prompt' },
    { title: 'Review', detail: 'per-file compile + contract conformance' },
  ],
}

const SRC = '/Users/aadyamadankar/working/xeno-live/app/src/main/java/com/example'
const short = p => p.split('/').pop()

const SKILL_CONTRACT = `ON-DEVICE SKILL MEMORY (all storage on the phone — no DB, no cloud):
package com.example.skill
- data class SkillStep(val tool: String, val args: Map<String, Any?> = emptyMap())
- data class Skill(val name: String, val description: String, val steps: List<SkillStep>, val createdAtMs: Long)
- interface SkillStore { fun all(): List<Skill>; fun get(name: String): Skill?; fun save(skill: Skill); fun delete(name: String) }
- class JsonFileSkillStore(context: android.content.Context) : SkillStore — persists to File(context.filesDir, "nazim_skills.json") as a JSON array; thread-safe (synchronized); fail-soft (missing/corrupt file = empty list). THIS FILE is the on-device memory.
Tools implement com.example.agent.AgentTool (val declaration: com.example.agent.ToolDeclaration(name, description, parametersJsonSchema:String); suspend fun execute(callId, args): com.example.agent.ToolResult). Construct each with a SkillStore. READ ${SRC}/agent/AgentModel.kt for the exact signatures.
- SaveSkillTool "save_skill": args {name, description, steps:[{tool, args}]} -> store.save(Skill(..., createdAtMs=System.currentTimeMillis())) -> Success("Saved skill '<name>'").
- ListSkillsTool "list_skills": no args -> Success(data = mapOf("skills" to store.all().map { mapOf("name" to it.name, "description" to it.description, "steps" to it.steps.size) })).
- RecallSkillTool "recall_skill": args {name} -> Success(data = mapOf("name" to .., "steps" to steps.map{mapOf("tool" to it.tool,"args" to it.args)})) so the MODEL re-issues them as normal permission-gated tool calls; Failure if not found.
JSON numbers arrive as Double — handle defensively.`

const STATE_CONTRACT = `AGENT STATE MACHINE (per-step):
package com.example.agent
- enum class AgentState { IDLE, PLANNING, OBSERVING, CLASSIFYING, AWAITING_CONFIRM, EXECUTING, OBSERVING_RESULT, STEP_DONE, BLOCKED, STUCK, FAILED, COMPLETE, CANCELLED }
- data class AgentStepRecord(val index: Int, val tool: String, val args: Map<String, Any?> = emptyMap(), val state: AgentState, val ok: Boolean)
Pure data, no Android deps. These let the coordinator expose the current step state + a recorded step history (which a skill can be saved from).`

const WRITTEN = { type: 'object', additionalProperties: false, properties: { writtenFiles: { type: 'array', items: { type: 'string' } }, notes: { type: 'string' } }, required: ['writtenFiles', 'notes'] }
const REVIEW = { type: 'object', additionalProperties: false, properties: { issues: { type: 'array', items: { type: 'object', additionalProperties: false, properties: { severity: { type: 'string', enum: ['high', 'medium', 'low'] }, problem: { type: 'string' }, fix: { type: 'string' } }, required: ['severity', 'problem', 'fix'] } } }, required: ['issues'] }

phase('Build')
const buildTargets = [
  { f: `${SRC}/agent/AgentState.kt`, g: 'the AgentState enum + AgentStepRecord', c: STATE_CONTRACT },
  { f: `${SRC}/skill/SkillModel.kt`, g: 'SkillStep + Skill + SkillStore interface', c: SKILL_CONTRACT },
  { f: `${SRC}/skill/JsonFileSkillStore.kt`, g: 'JsonFileSkillStore(context): SkillStore -> filesDir/nazim_skills.json', c: SKILL_CONTRACT },
  { f: `${SRC}/skill/tools/SaveSkillTool.kt`, g: 'save_skill AgentTool', c: SKILL_CONTRACT },
  { f: `${SRC}/skill/tools/ListSkillsTool.kt`, g: 'list_skills AgentTool', c: SKILL_CONTRACT },
  { f: `${SRC}/skill/tools/RecallSkillTool.kt`, g: 'recall_skill AgentTool', c: SKILL_CONTRACT },
]
await parallel(buildTargets.map(t => () =>
  agent(`Build ${t.f}.\nGOAL: ${t.g}\n${t.c}\nProduction Kotlin, minSdk 24, must compile. Do not edit other files. Return the file written.`,
    { schema: WRITTEN, phase: 'Build', label: `build:${short(t.f)}` })
))

phase('Integrate')
// A) Skills wiring (additive; does NOT touch AgentCoordinator).
const integA = await agent(
  `Wire the on-device skill tools into the agent. READ each file and ADD entries (don't rewrite); keep compiling, don't weaken existing security.
1. ${SRC}/permission/PermissionModel.kt — add ActionType values: SAVE_SKILL, RECALL_SKILL, LIST_SKILLS.
2. ${SRC}/permission/RiskClassifier.kt — classify those three as RiskTier.SAFE (local memory only, reversible). Do NOT change any existing classification.
3. ${SRC}/agent/AgentToolSchemas.kt — add name constants SAVE_SKILL="save_skill", RECALL_SKILL="recall_skill", LIST_SKILLS="list_skills" + a parametersJsonSchema for each.
4. ${SRC}/agent/GeminiToolMapper.kt — NAME_TO_TYPE: those constants -> the new ActionTypes (reversible; NOT in IRREVERSIBLE_TYPES).
5. ${SRC}/di/ServiceLocator.kt — lazily provide a singleton com.example.skill.SkillStore = JsonFileSkillStore(requireContext()).
6. ${SRC}/agent/ToolRegistry.kt — construct the SkillStore (from ServiceLocator or new JsonFileSkillStore(context)) and register SaveSkillTool, ListSkillsTool, RecallSkillTool.
7. ${SRC}/character/NazimPersona.kt — add one concise line: after a multi-step task the user may want again, he can save it as a named skill (save_skill), list saved skills (list_skills), and recall one to repeat it (recall_skill, then redo the steps) — all stored on THIS phone.
READ all targets + ${SRC}/skill/*, ${SRC}/agent/AgentModel.kt first. Report what changed per file.`,
  { schema: WRITTEN, phase: 'Integrate', label: 'integrate:skills' }
)

// B) State-machine wiring into the coordinator + UI (depends only on AgentState; serialized after A
//    only to avoid two agents editing ServiceLocator/Persona at once — different files mostly).
const integB = await agent(
  `Wire the per-step AgentState machine into the coordinator + UI so every step has a visible state. READ each file first; ADD without weakening the existing red-team-hardened gate logic.
1. ${SRC}/agent/AgentCoordinator.kt — add: private val _agentState = MutableStateFlow(AgentState.IDLE) + public val agentState: StateFlow<AgentState>; private val _stepHistory = MutableStateFlow<List<AgentStepRecord>>(emptyList()) + public val stepHistory: StateFlow<List<AgentStepRecord>>. In handleToolCalls set _agentState=PLANNING; in handleOne set the state at each transition: OBSERVING (before latestScreen), CLASSIFYING (before permissionEngine.decide), AWAITING_CONFIRM (when emitting pendingConfirm), EXECUTING (before runExecutor), OBSERVING_RESULT then STEP_DONE (after), BLOCKED (on Block), COMPLETE (on task_complete/finishTask), CANCELLED (onKillSwitchTripped), FAILED (on a failure result). Append an AgentStepRecord to _stepHistory for each executed step. beginTask() resets _stepHistory=emptyList() and _agentState=PLANNING; finishTask() sets _agentState=COMPLETE then IDLE. Do NOT change the decision order, the confirm gate, the rate limiter, or any security check — only ADD state emissions + history.
2. ${SRC}/viewmodels/XenoViewModel.kt — expose: val agentState = agentCoordinator.agentState (and val stepHistory = agentCoordinator.stepHistory).
3. ${SRC}/ui/agent/AgentConsole.kt — show the current AgentState label (e.g. a small uppercased status word) alongside the existing step text; keep the @Composable signature compatible (add an optional state param with a default, or read it via a new param) — if you change its signature, ALSO update the call site in ${SRC}/ui/screens/LiveScreen.kt to pass viewModel.agentState. Keep everything compiling.
READ ${SRC}/agent/AgentCoordinator.kt, ${SRC}/agent/AgentState.kt, ${SRC}/viewmodels/XenoViewModel.kt, ${SRC}/ui/agent/AgentConsole.kt, ${SRC}/ui/screens/LiveScreen.kt first. Report what changed per file.`,
  { schema: WRITTEN, phase: 'Integrate', label: 'integrate:statemachine' }
)

phase('Review')
const files = [...new Set([...buildTargets.map(t => t.f), ...(integA?.writtenFiles || []), ...(integB?.writtenFiles || [])])]
const reviews = await parallel(files.map(f => () =>
  agent(`Review ${f} (Xeno Live, Kotlin/Compose). READ it + relevant contracts (${SRC}/agent/AgentModel.kt, ${SRC}/agent/AgentState.kt, ${SRC}/permission/PermissionModel.kt). Check it COMPILES (correct imports, real AgentTool/ToolResult/StateFlow signatures, enum members exist, composable signatures + call sites consistent, no unresolved refs) and that NO existing security check was weakened. Concrete compile-risk/security issues + fixes; empty if good.`,
    { schema: REVIEW, phase: 'Review', label: `rev:${short(f)}` })
    .then(r => ({ f, issues: (r?.issues || []).filter(i => i.severity !== 'low') }))
))
for (const r of reviews.filter(Boolean)) {
  if (r.issues.length) {
    await agent(`Fix ${r.f}: ${r.issues.map(i => i.problem + ' -> ' + i.fix).join('; ')}. READ it, Edit it, keep it compiling, don't weaken security or change composable call sites without updating both ends. One-line summary.`, { phase: 'Review', label: `fix:${short(r.f)}` })
  }
}
return { built: buildTargets.map(t => short(t.f)), skillsWiring: integA?.writtenFiles, stateWiring: integB?.writtenFiles }
