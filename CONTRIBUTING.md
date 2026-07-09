# Contributing to Xeno Live

Sideload-only Android app. Google Play prohibits plan-and-execute accessibility agents, so it is never shipped through Play.

## Build & test

Requires JDK 11+ and the Android SDK (see `local.properties` / `install-android-sdk.sh`).

```bash
./gradlew :app:assembleDebug        # build the debug APK
./gradlew :app:testDebugUnitTest    # run the JVM unit tests (99 pass)
./gradlew :app:lintDebug            # optional: Android lint
```

Run `./gradlew :app:testDebugUnitTest` and make it green before opening a PR. Live voice cannot be exercised in CI or the emulator (emulator mic is silent) — verify voice/roam manually on a real device with a Gemini API key.

## Coding style

- Kotlin + Jetpack Compose (Material 3). Match the surrounding code; there is no autoformatter gate, so read the neighbours before you write.
- No code comments by default; a genuinely necessary one is 1-2 lines. Rationale belongs in the commit/PR body, not the source.
- UI uses the XenoWarm theme tokens (`com.example.ui.theme`) — no hardcoded colors, no dark-scheme tokens.
- Keep the layering intact: tools depend on contract interfaces (`AccessibilityController`, `PhoneControlExecutor`), and `ServiceLocator` is the only place allowed to name concrete implementations.
- No emojis anywhere (code, docs, commits, PRs). Use plain markers: `PASS`/`FAIL`, `-`, `[ ]`.

## Safety model — do not weaken

The permission/autonomy stack is the reason this app can be trusted with an AccessibilityService. Changes that reduce its strength are not accepted. Specifically, never:

- Route a tool call around `AgentCoordinator` (the single gate: permission engine, kill switch, loop guard, rate limiter, audit).
- Downgrade or bypass secure-context detection — banking/wallet apps, password/OTP/CVV fields, `FLAG_SECURE` screens, and a locked device must stay hard-blocked.
- Remove or narrow the on-device redaction (`ScreenRedactor` / `SensitivePatterns`) that scrubs a screen before it reaches Gemini.
- Make an irreversible or outbound action auto-run. Unknown/unmapped tools must degrade to `UNKNOWN` and go through Confirm; drafts (email/sms/dial/event) must prefill only and never send.
- Log raw params to the audit trail, or disable the panic STOP.

If a change touches this stack, cover it with a unit test and explain the safety reasoning in the PR body.

## Adding a tool

Three edits for a tool that reuses an existing `ActionType`:

1. **`app/src/main/java/com/example/agent/tools/YourTool.kt`** — one `AgentTool` implementation exposing a `ToolDeclaration` (name, description, JSON-schema params) and `execute()` returning `ToolResult.Success/Failure`. Use `TorchTool.kt` as the template and the `ToolArgs` helpers (`readBoolean`, etc.) for arg coercion.
2. **`ToolRegistry.buildTools`** — add one line constructing your tool (pass `context` if it needs one).
3. **`GeminiToolMapper.NAME_TO_TYPE`** — map the tool's wire name to an `ActionType` so the risk classifier can tier it. Omit this and the tool falls back to `UNKNOWN` and is forced through Confirm.

If the tool needs a genuinely new capability, also add the `ActionType` enum value in `PermissionModel`; it defaults to the `GUARDED` tier (never silent auto) and to irreversible unless you place it in `RiskClassifier` / `FORCED_ASK_TYPES` deliberately. Add a unit test for anything with non-trivial logic.

## Git & PRs

- Commit messages: one short line (~7-8 words).
- PR description: exactly two sections — `# Issues` (observed problems) and `# Fixes` (what changed, file in parens). Nothing else.
- Branch off `main`; do not commit secrets or `.env` files.
