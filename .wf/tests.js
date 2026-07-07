export const meta = {
  name: 'xeno-unit-tests',
  description: 'Write focused JVM/Robolectric unit tests for the security-critical pure-logic components, one writer + one reviewer per component',
  phases: [
    { title: 'Write', detail: 'one agent per component writes a focused unit test against the real code' },
    { title: 'Review', detail: 'a reviewer checks the test compiles + actually asserts the safety behavior' },
  ],
}

const SRC = '/Users/aadyamadankar/working/xeno-live/app/src/main/java/com/example'
const TEST = '/Users/aadyamadankar/working/xeno-live/app/src/test/java/com/example'
const short = p => p.split('/').pop()

const RULES = `RULES:
1. READ the target source file(s) first; assert against the ACTUAL public API (do not assume).
2. Put the test at the given path under app/src/test/java/com/example. Use JUnit4 (org.junit.Test).
3. If the code touches android.* (e.g. android.graphics.Rect via ScreenState/UiElement, or a Context),
   annotate the class @RunWith(org.robolectric.RobolectricTestRunner::class) and @org.robolectric.annotation.Config(sdk=[34]) — Robolectric + AndroidX test are already on the test classpath. Pure-logic targets need no runner.
4. Tests MUST compile and assert the real safety behavior (the bullet points given), not trivial getters.
5. Create ONLY the one test file. Do not edit production code.`

const WRITTEN = { type: 'object', additionalProperties: false, properties: { path: { type: 'string' }, notes: { type: 'string' } }, required: ['path'] }
const REVIEW = { type: 'object', additionalProperties: false, properties: { issues: { type: 'array', items: { type: 'object', additionalProperties: false, properties: { severity: { type: 'string', enum: ['high', 'medium', 'low'] }, problem: { type: 'string' }, fix: { type: 'string' } }, required: ['severity', 'problem', 'fix'] } } }, required: ['issues'] }

const TARGETS = [
  { name: 'RiskClassifierTest', src: `${SRC}/permission/RiskClassifier.kt`, robolectric: false, assert: 'irreversible/outbound types (SEND_MESSAGE, PLACE_CALL, MAKE_PURCHASE, DELETE_DATA) are never SAFE; blocked-tier types classify BLOCKED; benign types (OPEN_APP, SET_ALARM, TORCH) classify SAFE.' },
  { name: 'GeminiToolMapperTest', src: `${SRC}/agent/GeminiToolMapper.kt`, robolectric: false, assert: 'an UNKNOWN tool name maps to a NON-reversible AgentAction (forced through Confirm); known irreversible tools are reversible=false; targetApp is extracted from args for open_app; toLiveTool wraps declarations.' },
  { name: 'SensitivePatternsTest', src: `${SRC}/security/SensitivePatterns.kt`, robolectric: false, assert: 'matches card numbers (spaced/dashed), 4-8 digit OTPs, "OTP is 123456" prose, and IBAN-shaped strings; does not match ordinary words.' },
  { name: 'ScreenRedactorTest', src: `${SRC}/security/ScreenRedactor.kt`, robolectric: true, assert: 'given a ScreenState with a password element and OTP-looking text, the redacted copy removes/masks the sensitive text and marks secure=true; benign text is preserved.' },
  { name: 'DefaultSecureContextDetectorTest', src: `${SRC}/permission/DefaultSecureContextDetector.kt`, robolectric: true, assert: 'a denylisted banking package, a password element, and an OTP field each yield a non-NONE SecureReason; a benign screen yields NONE; null screen yields NONE.' },
  { name: 'AgentLoopControllerTest', src: `${SRC}/agent/AgentLoopController.kt`, robolectric: false, assert: 'the step cap is enforced (StepCapReached after N steps); repeating the same action on an unchanged screen trips Stuck; reset() clears counters; cancel() yields Cancelled.' },
  { name: 'RateLimiterTest', src: `${SRC}/permission/RateLimiter.kt`, robolectric: false, assert: 'consecutive irreversible actions hit the cap; the 3-consecutive-block / per-session auto-fallback fires (onBlock returns true at threshold); resetSession clears counters.' },
  { name: 'KillSwitchTest', src: `${SRC}/permission/KillSwitch.kt`, robolectric: false, assert: 'trigger() flips tripped/isTripped and invokes registered listeners once with the reason; reset() clears it; double-trigger does not double-invoke.' },
  { name: 'ParamsHasherTest', src: `${SRC}/audit/ParamsHasher.kt`, robolectric: false, assert: 'hashing is deterministic for equal maps, differs for different maps, and the raw values never appear in the hash output.' },
]

phase('Write')
await pipeline(
  TARGETS,
  t => {
    const path = `${TEST}/${t.src.includes('/permission/') ? 'permission' : t.src.includes('/agent/') ? 'agent' : t.src.includes('/security/') ? 'security' : 'audit'}/${t.name}.kt`
    return agent(
      `Write a focused unit test ${t.name} for ${t.src} in the Xeno Live Android app.\nTARGET FILE: ${t.src}\nPATH: ${path}\nASSERT THESE BEHAVIORS: ${t.assert}\nRobolectric needed: ${t.robolectric}.\n${RULES}\nReturn the path you wrote.`,
      { schema: WRITTEN, phase: 'Write', label: `test:${t.name}` }
    ).then(r => ({ ...t, path: (r && r.path) || path }))
  },
  async (built) => {
    if (!built) return null
    const reviews = await agent(
      `Review the unit test at ${built.path} for the Xeno Live app. READ it and its target ${built.src}. Check: (a) it compiles against the real API, (b) it actually asserts the required safety behavior (${built.assert}), (c) Robolectric runner present iff android.* is touched. Report concrete issues with fixes; empty if good.`,
      { schema: REVIEW, phase: 'Review', label: `rev:${built.name}` }
    )
    const issues = (reviews?.issues || []).filter(i => i.severity !== 'low')
    if (issues.length) {
      await agent(
        `Fix the unit test ${built.path}: ${issues.map(i => i.problem + ' -> ' + i.fix).join('; ')}. READ it, Edit it, keep it compiling and asserting the behavior. Don't edit production code.`,
        { phase: 'Review', label: `fix:${built.name}` }
      )
    }
    return built.path
  }
)
return { wrote: TARGETS.map(t => t.name) }
