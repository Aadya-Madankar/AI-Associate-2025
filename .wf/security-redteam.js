export const meta = {
  name: 'xeno-security-redteam',
  description: 'Adversarial red-team of the Xeno Live permission system: skeptics try to defeat BLOCKED/secure-context/confirm/killswitch/redaction, findings are verified, real ones are fixed',
  phases: [
    { title: 'Red-team', detail: 'skeptics attack each security-critical surface with a specific exploit goal' },
    { title: 'Verify', detail: 'independent verifier confirms each finding is a real, reachable flaw' },
    { title: 'Fix', detail: 'one fixer per file applies confirmed critical/high findings' },
  ],
}

const SRC = '/Users/aadyamadankar/working/xeno-live/app/src/main/java/com/example'
const ARCH = '/Users/aadyamadankar/working/xeno-live/ARCHITECTURE.md'
const short = p => p.split('/').pop()

const MODEL = `The system (see ${ARCH} §4): autonomy modes ASK/ASK_LESS/AUTO/BYPASS/PLAN set a baseline, but in EVERY mode (incl. BYPASS) the order is: denylist → forced-ask → secure-context block → per-rule+mode. BLOCKED tier (banking/wallet/authenticator apps, FLAG_SECURE windows, password/OTP/CVV fields, keyguard, factory reset) must be unreachable in ASK/ASK_LESS/AUTO/PLAN. Irreversible actions (send/call/purchase/delete) must NEVER auto-run and must show literal params on confirm. Sensitive screen text must be redacted on-device before any cloud call or audit write (only hashed params stored). The kill switch must always halt an in-flight task.`

const FINDINGS_SCHEMA = {
  type: 'object', additionalProperties: false,
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object', additionalProperties: false,
        properties: {
          severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
          title: { type: 'string' },
          file: { type: 'string', description: 'absolute path of the file with the flaw' },
          scenario: { type: 'string', description: 'concrete attack: model output / app context that triggers it' },
          flaw: { type: 'string', description: 'why the current code fails to stop it (cite code)' },
          fix: { type: 'string', description: 'the concrete code change that closes it' },
        },
        required: ['severity', 'title', 'file', 'scenario', 'flaw', 'fix'],
      },
    },
  },
  required: ['findings'],
}

const VERDICT_SCHEMA = {
  type: 'object', additionalProperties: false,
  properties: {
    isReal: { type: 'boolean', description: 'true only if this is a real, reachable exploit given the actual code (not hypothetical)' },
    severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
    reasoning: { type: 'string' },
  },
  required: ['isReal', 'severity', 'reasoning'],
}

const TARGETS = [
  { key: 'engine-bypass', file: `${SRC}/permission/DefaultPermissionEngine.kt`, goal: 'Find any model tool call that reaches execution while it should be BLOCKED or forced to Confirm. Probe the first-match ordering, default branches, and what happens for ActionType.UNKNOWN.' },
  { key: 'engine-escalation', file: `${SRC}/permission/DefaultPermissionEngine.kt`, goal: 'Find any path where an irreversible/GUARDED action auto-runs without Confirm under AUTO/ASK_LESS, or where a standing ALWAYS grant is offered for something that should only ever be ONCE.' },
  { key: 'detector-gap', file: `${SRC}/permission/DefaultSecureContextDetector.kt`, goal: 'Find a secure context that slips past detection: a banking/wallet package not on the denylist, an OTP/CVV/PIN field whose hint/resourceId evades the regex, a password field where isPassword is false, or reliance on FLAG_SECURE (which only blocks screenshots, NOT a11y text).' },
  { key: 'detector-secure', file: `${SRC}/permission/DefaultSecureContextDetector.kt`, goal: 'Try to make inspect() return NONE for a screen that is actually sensitive (empty/null elements, Android 14 isAccessibilityDataSensitive hidden nodes, case/locale tricks in keywords).' },
  { key: 'classifier-irrev', file: `${SRC}/permission/RiskClassifier.kt`, goal: 'Find an irreversible or outbound action type that is classified SAFE (so it could auto-run), or a GUARDED type missing from the table that defaults SAFE.' },
  { key: 'coord-confirm-race', file: `${SRC}/agent/AgentCoordinator.kt`, goal: 'Attack the confirm gate: double resolveConfirm, a stale deferred, a second tool call while one confirm is pending, or resolveConfirm arriving after teardown — anything that executes a denied action or skips the prompt.' },
  { key: 'coord-killswitch', file: `${SRC}/agent/AgentCoordinator.kt`, goal: 'Find a way an in-flight task keeps acting after the kill switch trips, or where beginTask re-arms autonomy back to a non-ASK mode after a panic stop.' },
  { key: 'coord-nullscreen', file: `${SRC}/agent/AgentCoordinator.kt`, goal: 'Probe the null/unreadable-screen path: can a side-effecting action run when the screen could not be inspected (fail-open instead of fail-closed)?' },
  { key: 'mapper-unknown', file: `${SRC}/agent/GeminiToolMapper.kt`, goal: 'Find a tool name or args shape that maps to a reversible/SAFE AgentAction when it should be irreversible/GUARDED (so it auto-runs), or where targetApp is lost so denylist scoping fails.' },
  { key: 'redactor-leak', file: `${SRC}/security/ScreenRedactor.kt`, goal: 'Find sensitive on-screen text (password, OTP, card number, IBAN, token) that survives redaction and would be sent to Gemini or written to the audit log.' },
  { key: 'patterns-gap', file: `${SRC}/security/SensitivePatterns.kt`, goal: 'Find common secret formats the regexes miss (spaced/dashed card numbers, 4-digit OTP, alphanumeric 2FA, "OTP is 123456" prose, IBAN variants).' },
  { key: 'rulestore-standing', file: `${SRC}/permission/DataStoreRuleStore.kt`, goal: 'Find a way a standing/persisted grant is created for an irreversible or forced-ask action, or a session grant that survives revokeSession().' },
  { key: 'ratelimiter-fallback', file: `${SRC}/permission/RateLimiter.kt`, goal: 'Find a way to defeat the consecutive-irreversible cap or the 3-consecutive/20-per-session auto-fallback-to-ASK (counter reset abuse, off-by-one).' },
  { key: 'executor-leak', file: `${SRC}/agent/DefaultPhoneControlExecutor.kt`, goal: 'Confirm whether the executor redacts screen content BEFORE returning it toward the model; find any tool result that carries raw sensitive text or bypasses the redactor.' },
  { key: 'inputtext-password', file: `${SRC}/agent/tools/InputTextTool.kt`, goal: 'Find a way input_text writes into a password/secure field (isPassword check missing, index re-resolved to a different node, set-text on a secure field).' },
  { key: 'a11y-secure-read', file: `${SRC}/accessibility/AgentAccessibilityService.kt`, goal: 'Find where the service reads/serializes a secure or denylisted screen without the redaction/secure flag being set, leaking structure/text upward.' },
]

function skepticPrompt(t) {
  return `You are a security red-teamer attacking the Xeno Live phone-control agent (Android/Kotlin). Your job is to BREAK it, not praise it.
${MODEL}

TARGET FILE: ${t.file}
EXPLOIT GOAL: ${t.goal}

READ the target file AND the contracts/related files it depends on (under ${SRC}/permission, ${SRC}/agent, ${SRC}/security, ${SRC}/accessibility). Think like an attacker who controls the model's tool-call output and the on-screen content. Report ONLY real, reachable flaws given the ACTUAL code — for each, the concrete scenario, the code-level reason it works, and the exact fix. Set file to the absolute path of the file that must change. If you genuinely cannot break it on this goal, return an empty findings array (do not invent issues).`
}

function verifyPrompt(f) {
  return `Independently verify this claimed security flaw in the Xeno Live agent by READING the cited file and its dependencies. Be skeptical of the claim — default to isReal=false unless the exploit is genuinely reachable in the real code.
FILE: ${f.file}
CLAIM: ${f.title}
SCENARIO: ${f.scenario}
ALLEGED FLAW: ${f.flaw}
PROPOSED FIX: ${f.fix}
${MODEL}
Decide isReal (true only if a real, reachable exploit) with reasoning and the true severity.`
}

function fixPrompt(file, items) {
  const list = items.map((x, n) => `${n + 1}. [${x.severity}] ${x.title}\n   SCENARIO: ${x.scenario}\n   FLAW: ${x.flaw}\n   FIX: ${x.fix}`).join('\n\n')
  return `Apply these CONFIRMED security fixes to ${file} (Android/Kotlin). READ it first, then Edit to close EVERY item below. Keep the public contract stable and the file compiling against the existing contracts. Prefer minimal, surgical, defensive changes (fail closed). Do not touch other files unless absolutely required (note it if you must).\n\n${list}\n\nReturn a one-line summary of each change.`
}

// ---- Orchestration ----------------------------------------------------------
phase('Red-team')
const raw = await parallel(TARGETS.map(t => () =>
  agent(skepticPrompt(t), { schema: FINDINGS_SCHEMA, phase: 'Red-team', label: `rt:${t.key}` })
    .then(r => (r?.findings || []).map(f => ({ ...f, file: f.file || t.file })))
))
const findings = raw.filter(Boolean).flat()
log(`Red-team surfaced ${findings.length} candidate findings`)

phase('Verify')
const verified = await parallel(findings.map(f => () =>
  agent(verifyPrompt(f), { schema: VERDICT_SCHEMA, phase: 'Verify', label: `v:${short(f.file)}` })
    .then(v => ({ ...f, verdict: v }))
))
const real = verified.filter(x => x.verdict?.isReal && (x.verdict.severity === 'critical' || x.verdict.severity === 'high'))
log(`Confirmed ${real.length} real critical/high findings of ${findings.length}`)

phase('Fix')
const byFile = {}
for (const x of real) (byFile[x.file] ||= []).push(x)
const fixes = await parallel(Object.entries(byFile).map(([file, items]) => () =>
  agent(fixPrompt(file, items), { phase: 'Fix', label: `fix:${short(file)}` }).then(() => file)
))
log(`Patched ${fixes.filter(Boolean).length} files`)

return {
  candidates: findings.length,
  confirmedRealHighCrit: real.length,
  fixedFiles: fixes.filter(Boolean),
  allFindings: verified.map(x => ({ severity: x.severity, title: x.title, file: short(x.file), isReal: x.verdict?.isReal, verdictSeverity: x.verdict?.severity })),
}
