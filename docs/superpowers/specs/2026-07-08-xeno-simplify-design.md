# Xeno Live — Prune & Wire Simplification

**Date:** 2026-07-08
**Status:** Approved approach: *Prune & wire* (keep every feature, delete dead code, unify duplicates, register all tools).

## Goal

Xeno is a voice assistant living inside an Android phone. Tap the mic once, then talk
hands-free — Nazim answers and operates the phone (opens apps, sets alarms, toggles
settings, types, taps, runs multi-step tasks). The app should feel like one simple thing,
and the codebase should be one simple pipeline.

## Owner decisions (locked)

| Question | Decision |
| --- | --- |
| Features to keep | **All**: Nazim 3D character, full safety engine, skills memory, camera/screen vision |
| Invocation | **Tap mic once → hands-free session** (continues while minimized, via foreground service). No always-listening wake word — wake-word code is deleted. |
| Safety visibility | **Invisible + one settings sheet.** Delete the 4 unwired Obsidian screens. |
| Execution approach | **Prune & wire** — no rewrite of the working core. |
| Autonomy default | **Auto** (prior decision, preserved). |
| UI direction | Warm light minimal (XenoWarm), prior decision, preserved. |

## Current state (from the subsystem deep-read)

The live runtime is already simple: `MainActivity` → `LiveScreen` + `XenoViewModel` →
`GeminiLiveClient` (one WebSocket: audio up, audio + toolCalls down) → `AgentCoordinator`
→ `PermissionEngine` → `ToolRegistry` (18 tools) → `AgentAccessibilityService`.
Around it sit ~10,500 LOC of dead or duplicated code (~43% of 24.3k LOC), plus real bugs.

Key findings:

- **17 of 35 tools never registered** in `ToolRegistry.buildTools` — brightness, DND,
  volume, media play/pause, maps, settings page, Wi-Fi panel, Bluetooth, email draft,
  SMS draft, dial prefill, calendar event, alarm, timer, share, screenshot,
  task_complete(dup). ~1,544 LOC written but invisible to the model.
- **Dead second brain:** `network/GeminiService.kt` (Retrofit) + `utils/` audio helpers
  (SpeechToTextHelper, TextToSpeechHelper, AudioPlayerHelper, AudioRecorderHelper) +
  `voice/TtsConfirmations.kt` — pre-Live relics, zero callers.
- **Three avatar pipelines, one face:** `character/` is live; `avatar/` package and
  `views/FluidAvatarCanvas.kt` (1,422 LOC) are dead; `NazimModelConfig` unread;
  duplicate lip-sync/blink/state-map code inside the live pipeline.
- **Two UI libraries:** warm-light components in `LiveScreen` are live; the Obsidian
  screens (OnboardingConsentScreen, PermissionCenterScreen, PlanPreviewScreen,
  AuditLogScreen) + 7 dead components (~3,257 LOC) are wired to no navigation.
- **VoiceService phantom:** its binder API, wake-word gate, mode controller, and second
  mic are never used; its only real job is the foreground-service notification that keeps
  capture alive while minimized (~80 LOC of value).
- **Security duplication:** 2 sensitive-pattern libraries (`DenyLists` regexes vs
  `SensitivePatterns`), 2 commit-word lists (already drifted), forced-ask set in 4 places,
  3 redaction layers, denylist checked twice, `maxTier()` duplicated.
- **Live bugs:** status-bar icons white-on-cream (two components fight over system bars);
  `inspectWithKeyguard` never called (device-locked block is unwired);
  `XenoViewModel.onError` teardown forgets `stopVision()`; two `JsonFileSkillStore`
  instances race over `nazim_skills.json`; confirm/API-key sheets render dark glass over
  the warm theme; `GestureDispatcher` reads `OverlayBus` so the avatar "walks to" taps,
  adding ~340ms latency while the overlay roams; `ParamsHasher` HMAC is salted with a
  random UUID at its only call site (digest = noise).

## Target design

### UI — one screen, one sheet, one overlay

- **Main screen (`LiveScreen`, kept):** Nazim face (top), transcript, one mic button,
  the existing warm-light autonomy pill (Ask / Ask-less / Auto / Bypass; default Auto —
  the dead Obsidian `ModePill.kt` is what gets deleted), gear icon.
- **Settings sheet (new, single bottom sheet):** autonomy mode picker, kill switch,
  recent-activity list (reads the audit log — wiring the existing dead `recent()` path),
  API key entry (reuse ApiKeysSheet content restyled warm), vision toggles.
  Replaces all four Obsidian screens.
- **Confirm sheet (kept, restyled warm):** appears only for risky actions; shows literal
  details (who/what) before send/call/pay-class actions.
- **Kill-switch overlay (kept)** and **floating Nazim overlay (kept)** for when he
  operates other apps.
- Fix system-bar styling: dark icons on the warm background, one owner.

### Backend — one pipeline

```
mic → AudioCapture → GeminiLiveClient (voice + tools, one WebSocket)
                         ↓ toolCall
        AgentCoordinator → PermissionEngine (one gate) → ToolRegistry (35 tools)
                         → AgentAccessibilityService / direct intents
                         ↓ toolResponse + spoken result + audit entry
```

- **Session lifecycle:** tap mic → foreground service (slimmed `VoiceService`, ~80 LOC:
  notification + lifecycle only) holds mic + WebSocket while minimized; tap again (or
  notification action / kill switch) ends it. Hands-free between taps.
- **Register the 16 missing tools** (of the 17 unwired files, `TaskCompleteTool` is a
  duplicate of the built-in `task_complete` and is deleted instead) — bringing the
  advertised set from 18 to 34 + `task_complete`. Each schema must satisfy Gemini Live
  constraints:
  no `additionalProperties`, no `$ref`/`$schema` (violations reject the whole setup with
  close 1007). One schema per tool — inline in the tool file; delete the
  `AgentToolSchemas` duplicates except name constants + shared helpers.
  Screen-independent tools (alarms, DND, brightness, open app…) must not require a
  readable a11y screen (prior decision, preserved).
- **Single sources of truth:**
  - One sensitive-pattern library: fold `DenyLists` field/value regexes into
    `SensitivePatterns`; detector and redactor both consume it.
  - One commit-word list; engine Layer 2b is the single commit-tap enforcement point
    (classifier's duplicate tap-label escalation removed; unresolved-label fails closed).
  - One forced-ask/irreversible set in `PermissionModel`, referenced by engine,
    classifier, rule store, mapper.
  - One `JsonFileSkillStore` instance (ServiceLocator's), passed into `ToolRegistry`.
  - One arg-coercion helper file replacing 8 copy-pasted `intArg`/`boolArg`.
  - Audit: merge `AuditEntry`/`AuditEntity`/`AuditMappers` into one Room entity;
    keep the write path; wire `recent()` into the settings-sheet activity list.
- **Wire the missing safety check:** call `inspectWithKeyguard` so device-locked blocks
  actually fire.
- **Decouple avatar from input path:** `GestureDispatcher` no longer waits on
  `OverlayBus` walk-to animation; overlay animates opportunistically.
- **Replace `ParamsHasher`** usage with the UUID already used at the call site
  (deterministic dedup can return later by removing the random salt first).

### Deletions (no user-visible change)

| What | ~LOC |
| --- | --- |
| Obsidian screens ×4 + dead components ×7 + `ModePill`, dead theme tokens | 3,257 |
| `views/FluidAvatarCanvas.kt` + duplicate `CompanionState` | 1,422 |
| `avatar/` package, `NazimModelConfig`, dead halves of animation map/portrait fallback | 990 |
| Dead audio/STT/TTS: `utils/` helpers ×4, `TtsConfirmations` | 505 |
| `network/GeminiService.kt` + Retrofit/converter Gradle deps | 104 |
| VoiceService phantom (binder, `WakeWordGate`, `VoiceMode`, `VoiceModeController`, second mic) | ~345 |
| Duplicate skill tools in `SkillModel.kt`, `AgentState.kt`, `AgentToolSchemas` dead 250 LOC, `TaskCompleteTool` dup | ~730 |
| Dead permission API (`RedactionPolicy.LENIENT`→file, PLAN mode, `THIS_APP_SESSION`, rule-store extras, rate-limiter/kill-switch dead members, 3-arg classify) | ~600 |
| Reflective FLAG_SECURE probe (blocked by non-SDK restrictions on all real devices; keyguard/denylist/password signals carry the load) | ~75 |
| `ParamsHasher` + Keystore dep | 110 |

Net: **~24.3k → ~13–14k LOC**, 137 → ~95 files, same features + 17 newly working tools.

Note: `AutonomyMode.PLAN` is removed as a mode (it was unreachable); "preview the plan"
remains available conversationally — the user can ask Nazim what he intends to do.

### Not doing (explicitly out)

- Always-listening wake word (owner decision — tap once, then hands-free).
- Navigation graph / multiple activities — one screen + sheets stays.
- New features beyond the settings sheet and the 17 tool registrations.
- Rewrites of `XenoViewModel`/`AgentCoordinator` beyond the listed fixes
  (unify the four teardown blocks into one `stopEverything()`; keep the rest).

## Error handling

- Session errors (WebSocket drop, mic loss): one teardown path (`stopEverything()`)
  stops audio, vision, overlay, agent loop — fixing the current onError drift.
- Tool failures return structured `toolResponse` errors so the model can recover or
  report by voice; permission denials speak the reason (existing behavior, kept).
- Fail-closed stays: null screen → block screen-dependent actions; unresolved tap
  label → GUARDED; secure context → hard block, spoken aloud.

## Testing & acceptance

- Existing unit tests keep passing, minus assertions on deleted members
  (KillSwitch/RateLimiter dead API, ParamsHasherTest — removed with their subjects).
- `./gradlew :app:assembleDebug` green after every phase; each phase is one reviewable
  git commit (repo now has git; baseline committed).
- Acceptance script on device:
  1. Tap mic → converse with Nazim (voice in/out, lip-sync).
  2. Minimize the app → keep talking → still responds; notification shows session.
  3. "Set a 10-minute timer", "turn on the torch", "set an alarm for 7", "turn down
     the volume", "brightness to max", "DND on" — all execute (newly wired tools).
  4. "Text mom I'll be late" → confirm sheet with literal details → confirm by tap.
  5. Banking app / password field → hard block, spoken refusal. Locked device → block.
  6. Settings sheet: change mode, view recent activity (audit entries), kill switch.
  7. Status-bar icons visible on warm background; sheets match warm theme.

## Phases (implementation order)

1. **Prune** — delete all dead code listed above; build + tests green. (Zero risk, huge clarity win; do first so every later diff is readable.)
2. **Unify** — single sources of truth (patterns, commit words, forced-ask, skill store, arg coercion, audit entity, schema copies); build + tests green.
3. **Wire tools** — register 16 tools (17 minus task_complete dup), validate schemas against Live constraints, on-device smoke test.
4. **Fix bugs** — system bars, keyguard wiring, teardown unification, gesture/overlay decoupling, ParamsHasher removal.
5. **Settings sheet + warm restyle** — new sheet (mode, kill switch, activity, API key, vision), restyle confirm sheet, delete last Obsidian remnants.
6. **Verify** — full acceptance script on device.
