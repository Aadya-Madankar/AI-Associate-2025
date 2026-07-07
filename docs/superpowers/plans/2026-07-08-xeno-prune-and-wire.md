# Xeno Prune & Wire Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shrink Xeno Live from ~24.3k to ~13–14k LOC with zero feature loss, register the 16 written-but-unwired voice tools, fix the known live bugs, and add one warm-light settings sheet — per `docs/superpowers/specs/2026-07-08-xeno-simplify-design.md`.

**Architecture:** The live pipeline (LiveScreen + XenoViewModel → GeminiLiveClient → AgentCoordinator → PermissionEngine → ToolRegistry → AgentAccessibilityService) is kept untouched except for the listed fixes. Everything else is deletion, deduplication, and wiring.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Gemini Live WebSocket (OkHttp/Moshi), Room, DataStore, AccessibilityService, SceneView/Filament.

## Global Constraints

- Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export ANDROID_HOME="$HOME/Library/Android/sdk"; ./gradlew :app:assembleDebug` must pass after EVERY task.
- Unit tests: `./gradlew :app:testDebugUnitTest` must pass after every task (adjusting/removing tests only where their subject is deleted by that task).
- Gemini Live tool schemas MUST NOT contain `additionalProperties`, `$ref`, or `$schema` at any nesting depth — a single violation rejects the whole Live `setup` (WebSocket close 1007) and the app "can't connect".
- Screen-independent tools (alarms, timers, DND, brightness, open app, settings pages…) must NOT require a readable a11y screen.
- Autonomy default stays **AUTO**; UI language is **XenoWarm** (warm light), not dark.
- Every task ends in its own git commit. No commit may leave the build red.
- Source root: `app/src/main/java/com/example/` (called `SRC` below). All `file:line` anchors are from the 2026-07-08 baseline commit `f813796`.

---

## Phase 1 — Prune (dead code only; zero behavior change)

### Task 1: Delete the dead Obsidian UI (11 files)

**Files:**
- Delete: `SRC/ui/agent/OnboardingConsentScreen.kt`, `SRC/ui/agent/PermissionCenterScreen.kt`, `SRC/ui/agent/PlanPreviewScreen.kt`, `SRC/ui/agent/AuditLogScreen.kt`
- Delete: `SRC/ui/agent/ModePill.kt`, `SRC/ui/components/StatusPill.kt`, `SRC/ui/components/MicButton.kt`, `SRC/ui/components/TranscriptOverlay.kt`, `SRC/ui/components/AuroraBackground.kt`, `SRC/ui/agent/VisionControls.kt`, `SRC/ui/agent/AgentConsole.kt`

**Interfaces:** Produces nothing; later tasks rely on these files being gone (Task 15 deletes the dark theme tokens whose only consumers are these files).

- [ ] **Step 1: Verify each file has zero external references** (all were grep-verified dead at baseline; re-verify since files may have drifted):

```bash
cd /Users/aadyamadankar/working/xeno-live
for c in OnboardingConsentScreen PermissionCenterScreen PlanPreviewScreen AuditLogScreen ModePill StatusPill MicButton TranscriptOverlay AuroraBackground VisionControls AgentConsole; do
  echo "== $c =="; grep -rn "\b$c(" app/src --include="*.kt" | grep -v "ui/agent/$c.kt" | grep -v "ui/components/$c.kt"
done
```
Expected: no hits outside each file itself (imports inside the 11 doomed files referencing each other are fine).

- [ ] **Step 2: Delete the 11 files** with `git rm`.
- [ ] **Step 3: Build + tests**: both gradle commands above. Expected: green. If a stray import breaks (e.g. a doomed file was imported by another doomed file), that's expected — they're deleted together.
- [ ] **Step 4: Commit** `prune: delete unwired Obsidian screens and superseded components (-3.2k LOC)`

### Task 2: Delete the two dead avatar pipelines and dead Nazim halves

**Files:**
- Delete: `SRC/views/FluidAvatarCanvas.kt` (1,422 LOC), `SRC/avatar/AvatarView.kt`, `SRC/avatar/LipSyncController.kt`, `SRC/character/NazimModelConfig.kt`, `SRC/character/NazimPortraitAvatar.kt`
- Modify: `SRC/character/NazimAssets.kt` (remove `hasPortrait` / `PORTRAIT_ASSET`), `SRC/character/NazimView.kt` (remove the portrait rung of the fallback ladder — GLB → fallback bust directly), `SRC/character/NazimAnimationMap.kt` (keep only `clipCandidates`, `genericIdleCandidates`, `firstPresent`, `resolveClip`; delete `idleClipCandidates`, `resolveIdleClip`, `jawOpenCandidates`, `mouthFunnelCandidates`, `mouthPuckerCandidates`, `oculusVisemeNames`, `visemeCandidates`, `allMouthMorphCandidates`, `resolveJawMorph`, `resolveVisemeMorph`, `resolveVisemeMorphMap`, `hasAnyMouthMorph`), `SRC/character/VisemeController.kt` (delete `Viseme` enum, `setViseme`, and the viseme shaping/decay blocks at :41-59, :110-117, :144-167, :203-210 — amplitude-only path stays), `SRC/character/NazimExpressionController.kt` (delete unused getters :133-137)

**Interfaces:** `NazimView`, `NazimRenderer`, `VisemeController` (amplitude API), `NazimExpressionController` keep their current public signatures as used by `LiveScreen`/`XenoOverlayService`.

- [ ] **Step 1: Re-verify dead** (same grep pattern as Task 1) for `FluidAvatarCanvas`, `AvatarView`, `LipSyncController`, `NazimModelConfig`, `NazimPortraitAvatar`, and for each deleted member name within `character/`.
- [ ] **Step 2: Delete files, apply the modifications.** In `NazimView.kt` the fallback decision becomes: GLB present → renderer; else → `NazimFallbackPortrait` (the code-drawn bust). No portrait-PNG branch.
- [ ] **Step 3: Build + tests.** Expected green — the renderer only calls `resolveClip`; nothing calls the deleted members.
- [ ] **Step 4: Commit** `prune: single avatar pipeline — delete FluidAvatarCanvas, avatar/, dead Nazim config/portrait/viseme halves (-2.4k LOC)`

### Task 3: Delete the dead second brain (Retrofit + old audio stack)

**Files:**
- Delete: `SRC/network/GeminiService.kt`, `SRC/utils/SpeechToTextHelper.kt`, `SRC/utils/TextToSpeechHelper.kt`, `SRC/utils/AudioPlayerHelper.kt`, `SRC/utils/AudioRecorderHelper.kt`, `SRC/voice/TtsConfirmations.kt`
- Modify: `app/build.gradle.kts:96,104` — remove the `retrofit` and `converter-moshi` dependencies (KEEP `moshi-kotlin`/codegen — `live/LiveProtocol.kt` uses them). Also remove their entries from `gradle/libs.versions.toml` if now unreferenced.

- [ ] **Step 1: Re-verify dead** (grep class names as in Task 1; `TextToSpeechHelper` may be referenced by `TtsConfirmations` — both die together).
- [ ] **Step 2: Delete files + dependency lines.**
- [ ] **Step 3: Build + tests.** Expected green.
- [ ] **Step 4: Commit** `prune: delete dead Retrofit Gemini client and pre-Live audio helpers (-0.6k LOC, -2 deps)`

### Task 4: Slim voice/ to a minimal foreground service (tap-once → hands-free stays)

**Files:**
- Delete: `SRC/voice/WakeWordGate.kt`, `SRC/voice/VoiceMode.kt`, `SRC/voice/VoiceModeController.kt`
- Modify: `SRC/voice/VoiceService.kt` — keep ONLY: foreground-service promotion + notification (with its STOP action) and the kill-switch listener (`VoiceService.kt:97-117`). Delete: `LocalBinder`, `setPcmListener`, `setOnWake`, `startVoice`, `startConversation`, `startCommand`, `endCommand`, amplitude/isCapturing accessors, its private `AudioCapture` instance (`VoiceService.kt:60`), the wake-word gate construction (`:66`), and mode-dependent notification text (`:259-263` — use one static "Xeno is listening — tap to stop" line).

**Interfaces:**
- Consumes: whatever `startService`/`stopService` calls `XenoViewModel` already makes (grep `VoiceService` in `SRC/viewmodels/XenoViewModel.kt` and `AndroidManifest.xml` and preserve exactly those entry points).
- Produces: the same `Intent`-based start/stop contract; the session's mic remains `XenoViewModel`'s own `AudioCapture` (`XenoViewModel.kt:130`) — unchanged.

- [ ] **Step 1: Map live entry points**: `grep -rn "VoiceService" app/src --include="*.kt" --include="*.xml"`. Preserve every hit outside `voice/`.
- [ ] **Step 2: Apply deletions + slim the service** (~90 LOC result).
- [ ] **Step 3: Build + tests.** Then manual sanity: the service still compiles as a `foregroundServiceType="microphone"` service per its manifest entry.
- [ ] **Step 4: Commit** `prune: voice/ → minimal mic foreground service; delete unreachable wake-word/mode machinery (-0.4k LOC)`

### Task 5: Delete dead agent-layer code

**Files:**
- Delete: `SRC/agent/AgentState.kt`, `SRC/agent/tools/TaskCompleteTool.kt`
- Modify: `SRC/skill/SkillModel.kt` — delete the duplicate embedded tool classes (lines ~213–379: `SaveSkillTool`/`ListSkillsTool`/`RecallSkillTool` copies; the registry uses `skill/tools/*` versions). Keep `Skill`, `SkillStep`, `SkillStore`, `JsonFileSkillStore`, JSON helpers.
- Modify: `SRC/agent/AgentToolSchemas.kt` — keep the tool-name constants and `TASK_COMPLETE_SCHEMA`; delete all other per-tool schema strings plus `byName` (:313) and `schemaFor` (:336) (each live tool already owns its inline schema).
- Modify: `SRC/agent/ToolRegistry.kt` — delete `has()` (:63), `all()` (:66), `names` (:57) (no callers).
- Modify: `SRC/di/ServiceLocator.kt` — delete dead accessors `skillStore` (:254-256), `accessibilityController` (:142-143), `resetForTesting()` (:331-345).

- [ ] **Step 1: Re-verify dead** (grep each member; note `AgentLoopControllerTest`/`GeminiToolMapperTest` must not reference deleted members — if they do, that specific assertion moves or dies with its subject).
- [ ] **Step 2: Apply.**
- [ ] **Step 3: Build + tests.** Expected green.
- [ ] **Step 4: Commit** `prune: delete duplicate skill tools, dead schemas/registry/DI surface (-0.8k LOC)`

### Task 6: Prune dead permission/security surface

**Files:**
- Delete: `SRC/security/RedactionPolicy.kt` — hard-code STRICT behavior inside `SRC/security/ScreenRedactor.kt` (only `ScreenRedactor.INSTANCE` with STRICT was ever used).
- Delete: `SRC/audit/ParamsHasher.kt` + `app/src/test/java/com/example/audit/ParamsHasherTest.kt`. At the single call site `SRC/agent/AgentCoordinator.kt:579` (which already salts with `UUID.randomUUID()`, making the HMAC digest pure noise) store `UUID.randomUUID().toString()` directly. Add comment: `// ponytail: random id, not a hash — deterministic dedup needs the salt removed first anyway`.
- Modify: `SRC/permission/PermissionModel.kt` — delete `AutonomyMode.PLAN` (engine branch `DefaultPermissionEngine.kt:156-161`, store guard `DataStoreRuleStore.kt:94`; `LiveScreen.kt:705-709` already skips it). Delete `RuleScope.THIS_APP_SESSION` + the `appWildcardKey` machinery (`DataStoreRuleStore.kt:136-141,232-237`) + its `ConfirmSheet.kt` label branch.
- Modify: `SRC/permission/DataStoreRuleStore.kt` — delete `revokePersisted` (:167), `clearAllPersisted` (:181), `activeRuleKeys` (:188), `awaitLoaded` (:191), `rulesVersion` (:63).
- Modify: `SRC/permission/RateLimiter.kt` — delete `fallbackTriggered`/`shouldFallbackToAsk` (:57-59,:110); keep `snapshot()` ONLY if `RateLimiterTest` needs it, else delete both and trim the test.
- Modify: `SRC/permission/KillSwitch.kt` — delete `onTrigger` ctor param, `lastReason` (:55-57), `clearListeners` (:103); trim `KillSwitchTest` assertions on them.
- Modify: `SRC/permission/DefaultSecureContextDetector.kt` — delete `inspectHidden` (:117). **KEEP `inspectWithKeyguard` (:102)** — Task 12 wires it.
- Modify: `SRC/accessibility/AgentAccessibilityService.kt:172-245` — delete the reflective FLAG_SECURE probe (`SecureFlag` enum, lazy `Method` accessor, per-window inspection): blocked by non-SDK restrictions on all real devices, always returns UNKNOWN. Keyguard + denylist + password-field signals carry the real load. The `ScreenState` secure fields stay; the reflective feeder goes.

- [ ] **Step 1: Apply deletions**, adjusting `KillSwitchTest`/`RateLimiterTest` (remove assertions on deleted members only — every other assertion must still pass).
- [ ] **Step 2: Build + tests.** Expected green.
- [ ] **Step 3: Commit** `prune: dead permission/security API, PLAN mode, reflective FLAG_SECURE probe, ParamsHasher (-0.7k LOC)`

---

## Phase 2 — Unify (single sources of truth)

### Task 7: One sensitive-pattern library, one commit-word list, one forced-ask set

**Files:**
- Modify: `SRC/security/SensitivePatterns.kt` — becomes the ONLY secret-shape library. Fold in `DenyLists.sensitiveFieldRegex`/`sensitiveValueRegex` semantics (`SRC/permission/DenyLists.kt:123,133`): merge any concept (OTP/CVV/PIN/card/IBAN/SSN variants) missing from `SensitivePatterns.labelPatterns`/value patterns, then delete those two regexes from `DenyLists` and point `DefaultSecureContextDetector`, `ScreenReader.kt:214`, `NodeActionExecutor.kt:103` at `SensitivePatterns`.
- Modify: commit-word list — merge `RiskClassifier.dangerousTapLabel` words (`RiskClassifier.kt:86` — `place call`, `call now`, `authorize`) into `DenyLists.commitButtonRegex` (`DenyLists.kt:147`), then delete `dangerousTapLabel` and the classifier's tap-label escalation; engine Layer 2b (`DefaultPermissionEngine.kt:109-122`) is the single commit-tap enforcement point. The "unresolved tap label fails closed to GUARDED" rule moves into Layer 2b. Delete the second index-resolution helper (engine has near-identical `:315-322` and `:333-342` — keep one). Delete the duplicate `maxTier()` (`RiskClassifier.kt:272` vs `DefaultPermissionEngine.kt:345` — keep the classifier's, engine imports it). Delete the zero-caller 3-arg `classify` overload (`RiskClassifier.kt:265`).
- Modify: forced-ask/irreversible set → ONE `val FORCED_ASK_TYPES: Set<ActionType>` in `SRC/permission/PermissionModel.kt`; `DefaultPermissionEngine.forcedAskTypes` (:39), `RiskClassifier.irreversibleTypes` (:51), `DataStoreRuleStore.NEVER_STANDING_TYPES` (:216), and `GeminiToolMapper.IRREVERSIBLE_TYPES` (`GeminiToolMapper.kt:193-202`) all reference it (mapper may add-to but not fork it).

**Interfaces:**
- Produces: `SensitivePatterns.matchesSensitiveField(label: CharSequence): Boolean` and `matchesSensitiveValue(text: CharSequence): Boolean` (or keep the existing member names if different — match current `SensitivePatterns` API; the point is single ownership, not renaming).
- Produces: `PermissionModel.FORCED_ASK_TYPES: Set<ActionType>`.

- [ ] **Step 1: Write the union test first** in `SensitivePatternsTest`: for every literal example currently matched by the DenyLists regexes (extract representative strings: `"OTP"`, `"CVV"`, `"PIN code"`, `"card number"`, `"IBAN"`, plus each existing test example), assert the unified `SensitivePatterns` matches. Run — the new cases may FAIL before the merge.
- [ ] **Step 2: Merge patterns; run tests until green.**
- [ ] **Step 3: Apply the commit-list + forced-ask consolidation.** `RiskClassifierTest` must keep passing minus deleted-member assertions.
- [ ] **Step 4: Build + full tests. Commit** `unify: one sensitive-pattern library, one commit-word list, one forced-ask set`

### Task 8: One arg-coercion helper + one RMS helper

**Files:**
- Create: `SRC/agent/tools/ToolArgs.kt`
- Modify: `SRC/agent/tools/TapTool.kt:74`, `InputTextTool.kt:163`, `ScrollTool.kt:86,94`, `SwipeTool.kt:104`, `LongPressTool.kt:141`, `SRC/agent/DefaultPhoneControlExecutor.kt:78` — replace private copies with the shared helper.
- Modify: `SRC/audio/` — add `internal fun pcm16Rms(buffer: ByteArray, length: Int): Float` in one file; replace the three byte-identical implementations in `AudioCapture`, `AudioStreamPlayer` (WakeWordGate's copy died in Task 4).

**Interfaces:**
- Produces: `ToolArgs.intArg(args: Map<String, Any?>, key: String): Int?` and `ToolArgs.boolArg(args: Map<String, Any?>, key: String): Boolean?` — exact behavior copied from the existing `TapTool.intArg` (accepts Number and numeric String; null otherwise). Copy the strictest existing variant; do not invent new coercions.

```kotlin
package com.example.agent.tools

/** Shared arg coercion for model-supplied tool args (numbers may arrive as Double or String). */
internal object ToolArgs {
    fun intArg(args: Map<String, Any?>, key: String): Int? = when (val v = args[key]) {
        is Number -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt()
        else -> null
    }
    fun boolArg(args: Map<String, Any?>, key: String): Boolean? = when (val v = args[key]) {
        is Boolean -> v
        is String -> v.trim().lowercase().takeIf { it == "true" || it == "false" }?.toBoolean()
        else -> null
    }
}
```
(Reconcile with the actual existing implementations before replacing — the existing semantics WIN over this sketch if they differ.)

- [ ] **Step 1: Read the existing copies; unify to the strictest common behavior; replace all call sites.**
- [ ] **Step 2: Build + tests. Commit** `unify: shared ToolArgs coercion + single pcm16Rms helper`

### Task 9: One audit entity

**Files:**
- Modify: `SRC/audit/` — merge `AuditEntry`/`AuditEntity`/`AuditMappers` into ONE Room `@Entity` used directly (fields are identical; enum-name mapping is a 5-line `TypeConverter`). Delete `AuditOutcome.UNDONE` + `undoToken` (nothing writes them). **KEEP `AuditLog.recent()`/`AuditDao.recent()`** — Task 14 wires the settings sheet to it.

**Interfaces:**
- Produces: `RoomAuditLog.append(...)` unchanged signature for `AgentCoordinator`; `suspend fun recent(limit: Int): List<AuditRecord>` (whatever the merged entity type is named — `AuditRecord`) for the settings sheet.

- [ ] **Step 1: Merge; bump the Room `@Database` version with `fallbackToDestructiveMigration()`** (audit history is diagnostic, not user data — a wipe on upgrade is acceptable; note it in the commit message).
- [ ] **Step 2: Build + tests. Commit** `unify: single Room audit entity; keep write path + recent() reader`

---

## Phase 3 — Wire the 16 tools

### Task 10: Register all 16 unwired tools

**Files:**
- Modify: `SRC/agent/ToolRegistry.kt:97-123` (`buildTools`) and imports
- Modify: `SRC/agent/GeminiToolMapper.kt:150-177` (`NAME_TO_TYPE`)
- Test: `app/src/test/java/com/example/agent/GeminiToolMapperTest.kt`

**Interfaces:**
- Consumes: existing tool classes in `SRC/agent/tools/` — constructors verified at baseline: `TakeScreenshotTool()` (no args); all others take `(context)`.
- Produces: `ToolRegistry.declarations()` now returns 34 declarations + `task_complete`.

- [ ] **Step 1: Add to `buildTools` after `TorchTool(context)`:**

```kotlin
                // System toggles + hardware
                BrightnessTool(context),
                DndTool(context),
                MediaVolumeTool(context),
                MediaPlayPauseTool(context),
                WifiPanelTool(context),
                BluetoothSettingsTool(context),
                // Intents / deep links (SAFE drafts — nothing sends without a user tap)
                MapsNavigateTool(context),
                OpenSettingsPageTool(context),
                EmailDraftTool(context),
                SmsDraftTool(context),
                DialPrefillTool(context),
                AddCalendarEventTool(context),
                SetAlarmTool(context),
                SetTimerTool(context),
                ShareTool(context),
                // Screen capture (a11y takeScreenshot, API 30+)
                TakeScreenshotTool(),
```
with the matching imports.

- [ ] **Step 2: Add the 14 missing `NAME_TO_TYPE` entries** (`BRIGHTNESS`/`SET_DND` already exist). Use `AgentToolSchemas` constants where they exist; otherwise the literal names verified at baseline:

```kotlin
            // System toggles
            "set_volume" to ActionType.MEDIA_VOLUME,
            "media_play_pause" to ActionType.MEDIA_PLAY_PAUSE,
            "open_wifi_panel" to ActionType.WIFI_PANEL,
            "open_bluetooth_settings" to ActionType.BLUETOOTH_SETTINGS,
            // Intents / deep links
            "navigate" to ActionType.MAPS_NAVIGATE,
            "open_settings" to ActionType.OPEN_SETTINGS_PAGE,
            "email_draft" to ActionType.EMAIL_DRAFT,
            "sms_draft" to ActionType.SMS_DRAFT,
            "dial" to ActionType.DIAL_PREFILL,
            "add_event" to ActionType.ADD_CALENDAR_EVENT,
            "set_alarm" to ActionType.SET_ALARM,
            "set_timer" to ActionType.SET_TIMER,
            "share" to ActionType.SHARE,
            // Screen capture
            "take_screenshot" to ActionType.TAKE_SCREENSHOT,
```

- [ ] **Step 3: Write the failing test first** (add to `GeminiToolMapperTest`):

```kotlin
    @Test
    fun `every registered tool maps to a known action type`() {
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext())
        val mapper = GeminiToolMapper()
        val unknown = registry.declarations().map { it.name }
            .filter { mapper.actionTypeFor(it) == ActionType.UNKNOWN }
        assertEquals(emptyList<String>(), unknown)
    }
```
(If `GeminiToolMapperTest` is a plain JUnit test without Robolectric, put this test in a new Robolectric test class instead — `ToolRegistry` needs a `Context`.)

- [ ] **Step 4: Run test → FAIL before Step 1/2 applied, PASS after.** Full build + tests green.
- [ ] **Step 5: Commit** `wire: register 16 tools — alarms, timers, brightness, DND, volume, media, maps, settings, wifi/bt, drafts, calendar, share, screenshot`

### Task 11: Build-time guard for Gemini Live schema constraints

**Files:**
- Test: create `app/src/test/java/com/example/agent/ToolSchemaContractTest.kt` (Robolectric)

**Interfaces:** Consumes `ToolRegistry.declarations()` and `GeminiToolMapper.toLiveFunctionDeclaration`.

- [ ] **Step 1: Write the test** — walks every declaration's parsed schema recursively and rejects the keys that kill the Live setup, and asserts every schema parses:

```kotlin
package com.example.agent

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToolSchemaContractTest {

    private val forbiddenKeys = setOf("additionalProperties", "\$ref", "\$schema")

    @Test
    fun `no tool schema contains keys that reject the Gemini Live setup`() {
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext())
        val mapper = GeminiToolMapper()
        val violations = mutableListOf<String>()
        for (decl in registry.declarations()) {
            val live = mapper.toLiveFunctionDeclaration(decl)
            val schema = live.parameters
            if (schema == null) {
                // Parameter-less tool is fine — but a NON-blank schema string that failed to
                // parse silently degrades to null; that hides a broken schema. Catch it:
                if (decl.parametersJsonSchema.isNotBlank()) violations += "${decl.name}: schema failed to parse"
                continue
            }
            findForbidden(decl.name, schema, violations)
        }
        assertTrue("Schema violations (would close Live socket with 1007): $violations", violations.isEmpty())
    }

    private fun findForbidden(tool: String, node: Any?, out: MutableList<String>) {
        when (node) {
            is Map<*, *> -> node.forEach { (k, v) ->
                if (k in forbiddenKeys) out += "$tool: contains $k"
                findForbidden(tool, v, out)
            }
            is List<*> -> node.forEach { findForbidden(tool, it, out) }
        }
    }
}
```

- [ ] **Step 2: Run it.** If any of the 16 newly wired schemas violates the contract, FIX THE TOOL'S INLINE SCHEMA (remove the forbidden key), not the test. Re-run to green.
- [ ] **Step 3: Commit** `wire: schema contract test — Live-setup-killing keys can never ship again`

---

## Phase 4 — Fix live bugs

### Task 12: One owner for system bars + wire the keyguard block

**Files:**
- Modify: `SRC/MainActivity.kt:42-49` and `SRC/ui/theme/Theme.kt:70-79` — today they write contradictory system-bar styles (last write wins → white icons on cream). DELETE the write in `Theme.kt`; `MainActivity` becomes the single owner and sets **dark icons on light bars** (`isAppearanceLightStatusBars = true` via `WindowInsetsControllerCompat`).
- Modify: `SRC/agent/AgentCoordinator.kt` (pre-action gate around `:535`, where `controller.readScreen(redact=false)` feeds `permissionEngine.decide()`): replace the plain `inspect(...)` call path so a locked device blocks. `DefaultSecureContextDetector.inspectWithKeyguard(...)` (:102) already implements it — wire a `KeyguardManager.isKeyguardLocked` check in (get `KeyguardManager` from the app context via `ServiceLocator`). `SecureReason.KEYGUARD` then actually fires; the engine and message strings already handle it.
- Test: extend `DefaultSecureContextDetectorTest` with: keyguard locked → result is secure with `SecureReason.KEYGUARD`.

- [ ] **Step 1: Write the failing keyguard test; run → FAIL** (nothing calls `inspectWithKeyguard`).
- [ ] **Step 2: Wire it; test PASSES.**
- [ ] **Step 3: System-bar fix** (delete Theme.kt write; assert visually in Task 17's device pass).
- [ ] **Step 4: Build + tests. Commit** `fix: dark status icons on warm bg (single owner); device-locked now actually blocks actions`

### Task 13: One teardown path + decouple avatar from tap latency

**Files:**
- Modify: `SRC/viewmodels/XenoViewModel.kt` — extract ONE `private fun stopEverything()` replacing the four drifting copies at `:436-450`, `:481-490`, `:646-673`, `:675-689`. It must stop: audio capture, audio player, vision (`stopVision()` — the one `onError` currently forgets), agent loop, overlay state, and reset UI state flows. All four sites call it (plus their site-specific extras, e.g. error message).
- Modify: `SRC/accessibility/GestureDispatcher.kt:36-38` — remove the `OverlayBus` wait: dispatch the gesture immediately, and PUBLISH the tap point to `OverlayBus` fire-and-forget so the roaming Nazim overlay can still animate toward it without delaying input (-340ms per tap while roaming).

- [ ] **Step 1: Apply both; build + tests.**
- [ ] **Step 2: Commit** `fix: single stopEverything() teardown (onError no longer leaks vision); taps no longer wait for avatar walk animation`

---

## Phase 5 — Settings sheet + one design language

### Task 14: The settings sheet

**Files:**
- Create: `SRC/ui/agent/SettingsSheet.kt`
- Modify: `SRC/ui/screens/LiveScreen.kt` — add a gear `IconButton` (top-right, next to the existing mode chip at `:463`); tapping opens `SettingsSheet` as a Material 3 `ModalBottomSheet`. Fold `ApiKeysSheet`'s field content in and delete `SRC/ui/agent/ApiKeysSheet.kt` (its state plumbing in `XenoViewModel` is reused as-is).

**Interfaces:**
- Consumes: `AutonomyModeStore` (mode get/set — same calls the mode chip uses today), `KillSwitch.trigger(...)` (same call `KillSwitchOverlay` uses), `RoomAuditLog.recent(limit = 50)` from Task 9, the existing API-key state/save calls from `XenoViewModel` (currently feeding `ApiKeysSheet`), and the existing vision-toggle callbacks from `LiveScreen.kt:261-279`.
- Produces: `@Composable fun SettingsSheet(onDismiss: () -> Unit, viewModel: XenoViewModel)` — internal layout: (1) autonomy mode segmented row [Ask | Ask-less | Auto | Bypass], (2) kill-switch row (red, confirmation dialog), (3) "Recent activity" — `LazyColumn` of audit rows (icon by outcome, description, relative time), (4) API key field(s), (5) vision toggles.

- [ ] **Step 1: Build the sheet in XenoWarm tokens** (`XenoWarm` in `SRC/ui/theme/Color.kt:167`) — match `LiveScreen`'s existing chip/typography idiom. No new theme values.
- [ ] **Step 2: Robolectric smoke test** (new `SettingsSheetTest`): composing the sheet with a fake/empty audit list doesn't crash and shows the four section headers.
- [ ] **Step 3: Build + tests. Commit** `feat: settings sheet — mode, kill switch, activity history (audit recent() finally wired), API key, vision`

### Task 15: One design language (kill Obsidian)

**Files:**
- Modify: `SRC/ui/agent/ConfirmSheet.kt`, `SRC/ui/agent/KillSwitchOverlay.kt`, `SRC/ui/components/PersonaSwitcher.kt` — restyle from dark glass to XenoWarm (background, text, button tokens only; zero structural/behavioral change). In `KillSwitchOverlay` delete the unused compact-orb branch (`:154-228`; only `expanded=true` is ever used, `LiveScreen.kt:318`).
- Modify: `SRC/ui/theme/Color.kt` — delete `XenoColors` dark tokens (:30, legacy triad :79-85); retarget the one import in `SRC/character/NazimPersona.kt:5`. `SRC/ui/theme/Theme.kt` — delete `XenoDarkColorScheme` (:20-58); `MaterialTheme` uses a light scheme built from XenoWarm.
- Modify: `SRC/ui/theme/Motion.kt` / `Shape.kt` — delete members whose only consumers died (e.g. `Motion.Drift` after `AuroraBackground`).

- [ ] **Step 1: Restyle; grep `XenoColors` afterwards — zero hits must remain.**
- [ ] **Step 2: Build + tests + `GreetingScreenshotTest` refresh if it asserts colors.**
- [ ] **Step 3: Commit** `style: one warm-light design language; delete Obsidian tokens and dark scheme (-0.4k LOC)`

---

## Phase 6 — Verify

### Task 16: Full verification

- [ ] **Step 1:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` — all green, APK produced.
- [ ] **Step 2: LOC check:** `find app/src/main -name "*.kt" | xargs wc -l | tail -1` — expect ≈13–14k (from 24,311).
- [ ] **Step 3: Dead-reference sweep:** `grep -rn "FluidAvatarCanvas\|WakeWordGate\|RedactionPolicy\|ParamsHasher\|XenoColors\|AgentToolSchemas.byName" app/src --include="*.kt"` → zero hits.
- [ ] **Step 4: On-device acceptance** (install `app/build/outputs/apk/debug/app-debug.apk`; needs a device/emulator with the accessibility service enabled and a Gemini key in `.env`):
  1. Tap mic → converse (voice in/out, Nazim lip-sync).
  2. Minimize → keep talking → still responds; notification shows the session; STOP action ends it.
  3. "Set a 10-minute timer", "turn on the torch", "set an alarm for 7am", "volume down", "brightness to max", "turn on do not disturb" — all execute (newly wired).
  4. "Text mom I'll be late" → warm confirm sheet with literal details.
  5. Open a banking app / password field → spoken refusal. Lock the device mid-task → block (KEYGUARD).
  6. Gear → settings sheet: change mode, see recent activity entries, kill switch works.
  7. Status-bar icons dark on cream; every sheet warm-light.
- [ ] **Step 5: Commit any final fixes; tag** `git tag prune-and-wire-v1`.
