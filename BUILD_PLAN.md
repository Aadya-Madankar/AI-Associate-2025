# Xeno Live — 600-Agent Build Plan

The build is partitioned so a **fleet of ~600 agents** each owns a real, distinct piece of
work. This is not 600 agents doing the same thing — it's the codebase decomposed into
~190 independent build units, each passed through a **build → review → verify** pipeline,
plus research, integration, adversarial security review, and compile-fix rounds.

> Runtime note: the orchestrator runs ~16 agents concurrently and caps lifetime agents
> below 1000, so the fleet executes in waves over time. Parallel builders only ever create
> **new files in their own package** (zero write-conflicts); **shared files** (manifest,
> gradle, ViewModel, DI graph) are touched only in a **serialized integration phase**.

---

## Agent math (target ≈ 600)

| Stream | Build units (files/components) | × pipeline | Agents |
| --- | ---: | --- | ---: |
| 0. Research (running) | 8 | ×1 | 8 |
| 1. Contracts & scaffolding | 10 | ×1 (serialized, no conflict) | 10 |
| 2. Accessibility control engine | 18 | ×3 (build/review/verify) | 54 |
| 3. Screen representation (set-of-marks) | 8 | ×3 | 24 |
| 4. Agent loop / orchestrator | 12 | ×3 | 36 |
| 5. Gemini function-calling integration | 8 | ×3 | 24 |
| 6. Tool implementations (1 agent per tool) | 36 | ×2 (build/verify) | 72 |
| 7. Permission & autonomy core | 12 | ×3 | 36 |
| 8. Consent UI + permission center | 14 | ×3 | 42 |
| 9. Audit log + Room data layer | 12 | ×3 | 36 |
| 10. Secure-screen / PII redaction | 8 | ×3 | 24 |
| 11. Voice pipeline (wake word, STT, TTS, FG service) | 12 | ×3 | 36 |
| 12. **Nazim** MetaHuman (render, visemes, states, assets) | 16 | ×3 | 48 |
| 13. UI screens (home, console, settings, onboarding, plan, audit) | 18 | ×2 | 36 |
| 14. Services / lifecycle / boot / notifications | 8 | ×2 | 16 |
| 15. Integration & wiring (shared files, serialized) | 12 | ×1 | 12 |
| 16. Adversarial security + permission red-team panel | — | panel | 16 |
| 17. Compile-fix loop (iterate until green) | — | rounds | ~20 |
| 18. Tests + docs polish | 20 | ×1 | 20 |
| **Total** | **≈190 units** | | **≈ 600** |

Numbers flex ±; the orchestrator scales each stream to hit the target without padding.

---

## Orchestration phases (deterministic workflow)

**Phase 0 — Research** *(running now, 8 experts)*
Accessibility API, agent loop, permission model, competitive teardown, action catalog,
voice pipeline, Play policy, existing-codebase map. Output hardens this plan.

**Phase 1 — Contracts & scaffolding** *(serialized, ~10 agents)*
Define the interfaces/package skeletons FIRST so parallel builders code against stable
contracts: `AgentTool`, `ScreenState`, `ActionRequest`, `PermissionDecision`,
`AutonomyMode`, `AuditEntry`, `RiskTier`, etc. New packages:
```
com.example.agent.*          orchestrator, loop, planner
com.example.agent.tools.*    one file per tool
com.example.accessibility.*  service, node serializer, gesture/action executor
com.example.permission.*     modes, classifier, gate, consent
com.example.security.*       secure-screen + PII redaction
com.example.audit.*          Room entities/dao + log
com.example.voice.*          wake word, STT command mode, TTS, FG service
com.example.character.*      Nazim render, visemes, state machine
com.example.ui.agent.*       console, permission center, plan view, audit viewer
```

**Phase 2 — Parallel build** *(the bulk, ~450 agents)*
Each build unit → an agent that writes ONE new file in its assigned package, then a
reviewer agent, then a verifier agent. Streams 2–14 run concurrently; no two agents write
the same file. Tool implementations (stream 6) fan out one-agent-per-tool (open app,
alarm, timer, torch, wifi, bt, dnd, volume, brightness, media, navigate, web search,
screenshot, notifications, call-draft, sms-draft, calendar, scroll, tap, type, back/home,
app-switch, …).

**Phase 3 — Integration** *(serialized, ~12 agents)*
The only agents allowed to edit shared files: `AndroidManifest.xml` (accessibility service,
FG services, permissions), `app/build.gradle.kts` + `libs.versions.toml` (deps:
accessibility config, Porcupine/Vosk, sceneview already present), `XenoViewModel`
(wire agent + permission flows into the existing `SessionListener`), `LiveProtocol`/
`GeminiLiveClient` (add `toolCall`/`toolResponse`), `MainActivity` (nav + onboarding),
DI/bootstrap. Run one at a time to avoid merge conflicts.

**Phase 4 — Adversarial security & permission red-team** *(panel, ~16 agents)*
Independent skeptics try to defeat the permission system: can a Blocked action slip
through? Does `FLAG_SECURE` detection hold? Can screen data leak un-redacted to Gemini?
Is the kill-switch always reachable? Majority-vote on each finding; fixes fed back.

**Phase 5 — Compile-fix loop** *(~20 agents, iterate)*
Run the Gradle build; route compile errors to fixer agents; repeat until green (or report
the residual blockers honestly). Real APKs are validated against the SDK at
`/Users/aadyamadankar/Library/Android/sdk`.

**Phase 6 — Tests, screenshots & docs** *(~20 agents)*
Unit + Robolectric + Roborazzi screenshot tests per major component; update README,
architecture doc, privacy disclosure, and the in-app consent copy.

---

## Conflict-avoidance rules (non-negotiable for the fleet)

1. A parallel builder creates **new files only**, exclusively within its assigned package.
2. **Shared files** (manifest, gradle, version catalog, `XenoViewModel`, `MainActivity`,
   `GeminiLiveClient`, `LiveProtocol`, DI) are edited **only** in Phase 3, serialized.
3. Every builder codes against the **Phase 1 contracts** — no inventing new public types
   that another stream depends on.
4. Each unit ships with its own KDoc + a focused test; the verifier confirms it compiles in
   isolation against the contracts.

---

## What "done" looks like for the fleet

Maps 1:1 to `SCOPE.md` §8 acceptance. The compile-fix loop (Phase 5) is what turns
"hundreds of files" into a buildable APK in a **good state**; if any acceptance item can't
be met in the run (e.g. missing Nazim GLB asset), it's reported plainly with the fallback
in place, not silently skipped.
