I now have full grounding in the actual code. Writing the deliverable.

# Xeno Live → Voice-Driven Phone-Control Agent: Architecture & Build Plan

> A blueprint for evolving the existing Gemini-powered "Xeno Live" voice companion into a permission-gated, voice-driven phone-control agent — restricted to non-sensitive actions, on the user's own device, with explicit consent and a Claude-Code-style autonomy model.

---

## 1. How Xeno Live works today

Xeno Live is a **single-Activity Jetpack Compose app** whose live voice loop is fully real and self-contained. There is **no function-calling / tool support anywhere** — the Live protocol is **AUDIO-only**. Adding a phone-control agent is *adding a new capability*, not extending one.

### Component map

| Layer | File | Role |
|---|---|---|
| Entry | `app/src/main/java/com/example/MainActivity.kt` | Thin shell. Owns `XenoViewModel` via `by viewModels()`, requests `RECORD_AUDIO` once (Accompanist), calls `LiveScreen(viewModel)`. **Imports `com.example.ui.screens.LiveScreen` which does not exist → app does not compile as committed.** |
| Brain / state | `app/src/main/java/com/example/viewmodels/XenoViewModel.kt` | Single source of truth (`AndroidViewModel`). StateFlows: `selectedPersona`, `companionState`, `messages`, `amplitude`, `isConnected`, `isMicActive`, `errorMessage`, `apiKeyMissing`. Public API: `selectPersona`, `toggleLive`, `startSession`, `stopSession`, `sendText`, `clearError`. |
| WebSocket | `app/src/main/java/com/example/live/GeminiLiveClient.kt` | OkHttp WebSocket to `wss://generativelanguage.googleapis.com/.../BidiGenerateContent?key=…`. Model `gemini-3.1-flash-live-preview`. Outbound `sendAudioChunk` / `sendTextTurn`; inbound dispatched in `handleServerMessage()` (lines ~130–170). `Listener` interface (lines 28–37) is the integration seam. |
| Protocol DTOs | `app/src/main/java/com/example/live/LiveProtocol.kt` | Moshi `@JsonClass` classes. `LiveSetup` carries **only** `model` + `generationConfig` + `systemInstruction` — **no `tools` field**. `LiveServerMessage` has only `setupComplete` + `serverContent`; `LiveServerContent` has only `modelTurn/turnComplete/interrupted/generationComplete`. **No `toolCall`/`functionCall`/`toolResponse` types exist.** |
| Mic | `app/src/main/java/com/example/audio/AudioCapture.kt` | PCM16 mono **16 kHz** on a daemon thread, prefers `VOICE_COMMUNICATION` source (HW echo cancellation). Emits per-chunk RMS amplitude. Exactly Gemini Live input format. |
| Playback | `app/src/main/java/com/example/audio/AudioStreamPlayer.kt` | PCM16 mono **24 kHz** `AudioTrack` MODE_STREAM. `clear()` implements barge-in (pause+flush). Exactly Gemini Live output format. |
| Personas | `app/src/main/java/com/example/models/Persona.kt` | 4 hardcoded personas (xeno/aria/maya/atlas), each `systemInstruction` + `voiceName` (Fenrir/Aoede/Kore/Charon). `systemInstruction` is passed verbatim into Live setup — the natural place to teach tools. |
| State enum | `app/src/main/java/com/example/core/CompanionState.kt` | `IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, ERROR`; also `Sender`, `ChatMessage`. |
| Manifest | `app/src/main/AndroidManifest.xml` | Declares **only `INTERNET` + `RECORD_AUDIO`**. No foreground service, no accessibility, no overlay, no telephony/package-query perms. |

### Live voice flow (all real, no stubs)

```
startSession()  →  GeminiLiveClient.connect(systemInstruction, voiceName, SessionListener)
                       └─ onOpen → sends LiveSetupRequest (AUDIO modality only)
SessionListener (inner class, XenoViewModel.kt lines 274–367; every callback re-dispatches onto viewModelScope):
  onSetupComplete → player.start() + beginCapture(client) + state=LISTENING
  AudioCapture.onPcm → client.sendAudioChunk(pcm16)          (16kHz mic → socket)
  onAudio(pcm)    → player.write(pcm) + state=SPEAKING        (24kHz socket → speaker)
  onText          → appendStreamedText
  onTurnComplete  → state=LISTENING
  onInterrupted   → player.clear()  (barge-in; server VAD on by default)
  onError/onClosed → teardown
```

`SessionListener.isCurrent()` (`liveClient === client`) drops stale callbacks after teardown/persona switch. **This is the exact seam where the agent loop belongs.**

### Dead / orphaned code (do **not** wire the agent into these)

- `network/GeminiService.kt` — Retrofit REST, unused (voice uses the WebSocket only).
- `utils/*Helper.kt` — legacy on-device STT/TTS, no callers (though `TextToSpeechHelper` may be reused for command-mode confirmations).
- `views/FluidAvatarCanvas.kt` — defines a **duplicate** `com.example.views.CompanionState` enum.
- `avatar/AvatarView.kt`, all `ui/components/*` — unreachable because `LiveScreen` is missing. `assets/models/` is empty (avatar.glb absent → AvatarView falls back to AuroraOrb).

---

## 2. What we're building

A **hands-free, voice-driven phone-control agent** built on top of Xeno Live. The user speaks a natural request ("set a 10-minute timer", "text Mom I'm running late", "turn on the flashlight"), and the companion **plans, acts, and observes** on the real device UI — narrating what it's doing in its persona voice.

**Core principles:**

1. **Voice-first.** The existing duplex Gemini Live loop is the conversation surface; phone actions are Gemini Live **tool calls**.
2. **Permission-gated (Claude-Code-style).** Four autonomy modes (Ask / Ask-less / Auto / Bypass) layered over a deny-list + forced-ask list + secure-context block that override **every** mode.
3. **Non-sensitive by design.** A risk-classified action taxonomy. Banking/payment/2FA/password/security screens are hard-blocked; irreversible actions (send money, place call, delete) always route to explicit confirm and never auto-run.
4. **Visible & interruptible.** A persistent foreground-service notification + overlay shows the current step with a one-tap STOP. An append-only audit log records every action.
5. **Honest about constraints.** Google Play's AccessibilityService policy (enforced **Jan 28, 2026**) prohibits autonomous "plan and execute" agents — so the autonomous build is **sideload / dev-distribution**, and any Play build is deterministic-rule-only.

---

## 3. How it works (technical mechanism)

### 3.1 The four data-flow stages

```
 VOICE                 PLAN                  ACT                   OBSERVE
 ─────                 ────                  ───                   ───────
 mic 16kHz ─► Gemini Live ─► toolCall ─► PhoneControlExecutor ─► AccessibilityService
   (AudioCapture)   (WebSocket)   {name,args,id}   (intents / toggles / a11y)   reads node tree
        ▲                                                              │
        │                                                              ▼
   24kHz speaker ◄── audio narration ◄── toolResponse {id, screen:[…]} ◄── compact UI snapshot
   (AudioStreamPlayer)
```

The model **never sees the screen directly**. Every observation is a tool result you compute and feed back. This is the DroidRun / RunAnywhere / AppAgent pattern.

### 3.2 Accessibility-Service control engine

A single `AgentAccessibilityService extends AccessibilityService` is the **read + act** engine.

**Manifest declaration:**
```xml
<service android:name=".accessibility.AgentAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true">
  <intent-filter><action android:name="android.accessibilityservice.AccessibilityService"/></intent-filter>
  <meta-data android:name="android.accessibilityservice" android:resource="@xml/agent_a11y_config"/>
</service>
```

**Config XML (`res/xml/agent_a11y_config.xml`):** `canRetrieveWindowContent="true"`, `canPerformGestures="true"`, `accessibilityEventTypes="typeAllMask"`, `accessibilityFeedbackType="feedbackGeneric"`, flags `flagRetrieveInteractiveWindows|flagReportViewIds`. Set `isAccessibilityTool` only if pursuing the (narrow, disability-only) Play exemption — **do not** falsely claim it.

**Read screen** (`getRootInActiveWindow()` → DFS):
- Keep only nodes where `isVisibleToUser()` **and** (`isClickable || isEditable || isScrollable || isLongClickable || text != null`).
- Capture `getText()`, `getContentDescription()`, simplified `className`, `getViewIdResourceName()`, `getBoundsInScreen(Rect)`, capability flags.
- Cap at ~25–30 elements/frame to fit the prompt.
- **API < 33: `recycle()` every node** (no-op on 33+ — guard by version).

**Act:**
- `node.performAction(ACTION_CLICK / ACTION_SET_TEXT / ACTION_SCROLL_FORWARD …)`; **fall back to `dispatchGesture()` coordinate tap at bounds center** when `performAction` returns false (common on Compose/WebView/canvas).
- `dispatchGesture()` for taps/swipes (requires `canPerformGestures`, API 24+): tap = `Path.moveTo` + ~50 ms stroke; swipe = `moveTo`+`lineTo` + ~200–400 ms.
- `performGlobalAction(GLOBAL_ACTION_BACK / HOME / RECENTS / NOTIFICATIONS)` for navigation.
- `takeScreenshot()` (API 30+) only when text state is ambiguous — **rate-limited ~1 s** (handle `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`), copy the HardwareBuffer to a software Bitmap before recycling.

### 3.3 Screen representation for the model (set-of-marks by index)

Each observation is a flat JSON list — **index-based grounding, never raw coordinates** (LLMs are poor at pixel regression; a small integer index is reliable):

```json
[
  {"i":0,"role":"Button","text":"Start","bounds":[40,1200,360,1320],"clickable":true},
  {"i":1,"role":"EditText","text":"","hint":"Message","bounds":[40,400,1040,520],"editable":true},
  {"i":2,"role":"Button","text":"Send","bounds":[1060,400,1180,520],"clickable":true}
]
```

The model returns `tap(index=2)`; your code resolves `index → cached Rect → center → dispatchGesture`. **Indices are regenerated every `get_screen`** (never stable across renders) — require the model to re-observe after any screen-changing action. After each action, wait for the UI to settle (`TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED` quiesce or a short debounce) before re-reading.

### 3.4 The Gemini plan-act-observe loop

```
voice → Gemini Live → toolCall(name,args,id)
   → PermissionEngine.check(action)  →  [BLOCKED → toolResponse error]  /  [ASK → confirm sheet]  /  [allow]
   → PhoneControlExecutor.execute(name,args)  (on viewModelScope, OFF the WebSocket reader thread)
   → AgentAccessibilityService.readScreen()  → compact snapshot
   → client.sendToolResponse([{id, response:{result, screen:[…]}}])
   → model plans next toolCall OR speaks/finishes (task_complete)
```

**Critical protocol facts (gemini-3.1-flash-live-preview):**
- Declare tools in `setup.tools = [{functionDeclarations:[…]}]`. The Live API does **not** auto-execute tools.
- Server sends `{"toolCall":{"functionCalls":[{id,name,args}]}}`. You reply `{"toolResponse":{"functionResponses":[{id,name,response}]}}` — **`id` MUST echo exactly**, or the turn hangs.
- **Never** answer a function call via `clientContent` / `sendTextTurn` — only via `toolResponse`.
- Handle `{"toolCallCancellation":{"ids":[…]}}` (barge-in) → abort in-flight gestures, route through existing `onInterrupted`.
- **Sequential/blocking only** — do not design around NON_BLOCKING/async scheduling (that's 2.5-Flash-Live only).
- Audio-only sessions cap ~15 min; context 32k tokens → only send the **latest** snapshot, drop stale dumps; use session resumption for long runs.

**Loop control (build from day one):** `stepCount` hard cap (~25); ring buffer of recent `(action,args,screenHash)` to detect repeats (same action on unchanged screen N times → inject an error hint or escalate); each `toolResponse` carries success/failure so the model self-corrects; explicit `task_complete(success, summary)` terminates.

### 3.5 Voice pipeline

The hard parts already exist (16 kHz capture, 24 kHz playback w/ barge-in, server VAD). The agent adds always-on capability via a **mic foreground service**:

| Concern | Approach |
|---|---|
| Mic ownership | Single `VoiceService` (FGS `type=microphone`) owns the one `AudioCapture`. Started from the **visible Activity** (you cannot start a mic FGS from background — `RECORD_AUDIO` is while-in-use). |
| Wake word | On-device **Porcupine** (`process(short[])` sharing the existing echo-cancelled AudioCapture frames) or open-source **Vosk**. Only the tiny model runs in steady state; **don't keep the WebSocket open** while merely waiting for wake. |
| Turn-taking | Gemini Live **server VAD** handles barge-in; `onInterrupted → player.clear()` already wired. Do **not** also run `SpeechRecognizer` concurrently (mic conflict). |
| Confirmations | In agent/command mode reuse Android `TextToSpeechHelper`; in conversation mode use Gemini native audio out. |
| Manifest adds | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` (+ `POST_NOTIFICATIONS`), `<service foregroundServiceType="microphone">`. |

---

## 4. The permission & autonomy system (the heart of the product)

Maps Claude Code's permission model onto phone actions. **Layering rule (copy verbatim): modes set the baseline; deny rules, forced-ask rules, and the secure-context block apply in EVERY mode — including Bypass.**

### 4.1 Autonomy modes

```kotlin
enum class AutonomyMode { ASK, ASK_LESS, AUTO, BYPASS, PLAN }
```

| Mode | Behavior | Claude Code analog |
|---|---|---|
| **ASK** (default) | Reads freely; **confirm every action**. | `default` |
| **ASK-LESS** | Auto-run a fixed SAFE whitelist; confirm everything else. | `acceptEdits` |
| **AUTO** | Run SAFE actions without prompts; GUARDED actions go to the risk classifier → confirm or block. Kill-switch counters armed. | `auto` (+ classifier) |
| **BYPASS** | Run everything **except** hard-blocked secure contexts + forced-ask list. Gated behind a deliberate re-confirmed opt-in; never a one-tap toggle. Sideload-only. | `bypassPermissions` |
| **PLAN** | Emit the full ordered action list to a preview; **execute nothing**. | `plan` |

Persist mode in DataStore; surface as a Compose mode pill; cycle via a gesture.

### 4.2 Action risk model & classifier

```kotlin
data class AgentAction(val type: ActionType, val targetApp: String?, val params: Map<String,Any>, val reversible: Boolean)
```

**Classifier order (first-match-wins, mirrors Claude Code):**
1. Global **denylist** (apps + action types) → hard deny.
2. **Forced-ask** list (send-money, delete-all, place-call) → always prompt, even in BYPASS.
3. **Secure-context detector** → block / escalate.
4. Per-action/per-app **allowlist** + current mode → auto-run or prompt.

**Classification = MAX(static action risk, runtime context risk).** Three questions per action: *Is it irreversible/data-leaking/trust-crossing? Did the user authorize THIS exact action? Does authorization cover the blast radius (open app vs. send message in it)?* **Reversibility dominates** — `SEND_MESSAGE / PLACE_CALL / MAKE_PURCHASE` are marked irreversible and **never qualify for AUTO**.

| Tier | Actions |
|---|---|
| **SAFE** | OPEN_APP, SET_ALARM/TIMER, TORCH, MEDIA_PLAY_PAUSE, MEDIA_VOLUME, WEB_SEARCH, OPEN_URL, MAPS, OPEN_SETTINGS_PAGE, SMS/EMAIL **draft** (user presses send), DIAL (pre-fill), ADD_CALENDAR_EVENT, SHARE |
| **GUARDED (needs-confirm)** | SEND_MESSAGE (committed), SEND_EMAIL (committed), PLACE_CALL, MAKE_PURCHASE, DELETE_DATA, CHANGE_SECURITY_SETTING, INSTALL/UNINSTALL, brightness/DND (one-time special-access grant) |
| **BLOCKED** | Anything in banking/payment/wallet/authenticator apps, FLAG_SECURE windows, password/OTP/CVV fields, keyguard/lockscreen, factory reset |

### 4.3 Detecting & blocking sensitive / secure contexts

`SecureContextDetector` runs **before** every action; returns `BLOCKED` if any of:

| Signal | Detection |
|---|---|
| Foreground app on deny-list | `event.packageName` on `TYPE_WINDOW_STATE_CHANGED`; curated banking/wallet/2FA package set (user-editable). |
| Secure window | `WindowManager.LayoutParams.FLAG_SECURE` present. ⚠️ FLAG_SECURE blocks **screenshots only** — it does **not** hide node text from a11y, so this is a **self-enforced** ethical boundary. |
| Password/OTP field | `node.isPassword()` OR inputType `TYPE_TEXT_VARIATION_PASSWORD/VISIBLE_PASSWORD/NUMBER_VARIATION_PASSWORD`; backstop: hint/content-desc/resource-id regex for `OTP|CVV|PIN|password|card number`. |
| Lockscreen | keyguard showing. |
| Android 14+ hidden node | `isAccessibilityDataSensitive` returns null/blank → treat "sensitive-looking + unreadable" as GUARDED, never SAFE-because-empty. |

BLOCKED is a hard stop in ASK/ASK-LESS/AUTO/PLAN; proceeds in BYPASS only after an **extra explicit confirm**.

### 4.4 Consent / audit / kill-switch UX

- **Confirm sheet:** Compose `ModalBottomSheet` over a `TYPE_ACCESSIBILITY_OVERLAY` window (so it floats above the target app and the dispatch path can't defeat it). Shows action verb, target app icon/name, **literal params** (actual message text / number / amount), risk badge, and `Allow once` / `Always allow (this action + this app)` / `Deny`. Irreversible actions require the literal value displayed.
- **Rule scopes (DataStore):** this-action-once · always-this-action+this-app · for-this-session · for-this-app. New "always" grants default to **session-only**; never generalize "send message" across apps/recipients.
- **Sessions:** bounded by explicit start ("go hands-free for 10 minutes"), auto-expire on timer / screen-off / app-background. Per-session reset of rate-limit + block counters.
- **Foreground service** owns: append-only **audit log** (Room: timestamp, mode, action, target app, **params hash** — never raw field contents, classification, decision, outcome, undo-token), **token-bucket rate limiter** (low hard cap on consecutive irreversible/GUARDED actions), and the **kill switch**.
- **Kill switch (3 ways):** persistent STOP in the notification; a hardware trigger (volume-down long-press / power triple-press via the a11y service) → `disableSelf()` + cancel in-flight gestures + revert to ASK; screen-off / app-leave auto-disarm.
- **Auto-fallback counters (copy thresholds):** 3 consecutive blocks **or** 20 total/session → AUTO/BYPASS force-reverts to ASK.
- **Undo:** snapshot prior value for reversible toggles; store created entity id for alarms/timers/events; mark sends/calls/purchases irreversible.

---

## 5. Action catalog

Tier legend: **✅ SAFE** (may auto-run in ASK-LESS/AUTO) · **⚠️ NEEDS-CONFIRM** · **⛔ BLOCKED**.

### Intents / deep links (target app renders its own UI)

| Action | Mechanism | Permission | Tier |
|---|---|---|---|
| Dial number (pre-fill) | `ACTION_DIAL` `tel:` | none | ✅ |
| Place call directly | `ACTION_CALL` | `CALL_PHONE` | ⚠️ |
| SMS draft | `ACTION_SENDTO` `smsto:` + `sms_body` | none | ✅ |
| Send SMS silently | `SmsManager` | `SEND_SMS` | ⛔ |
| Email draft | `ACTION_SENDTO` `mailto:` + extras | none | ✅ |
| Set alarm | `AlarmClock.ACTION_SET_ALARM` | `SET_ALARM` (normal) | ✅ |
| Set timer | `AlarmClock.ACTION_SET_TIMER` | `SET_ALARM` | ✅ |
| Add calendar event | `ACTION_INSERT` Events.CONTENT_URI | none | ✅ |
| Web search / open URL | `ACTION_WEB_SEARCH` / `ACTION_VIEW` | none | ✅ |
| Maps / navigation | `geo:` / `google.navigation:q=` | none | ✅ |
| Share text/file | `ACTION_SEND` + `createChooser` | none | ✅ |
| Open another app | `getLaunchIntentForPackage` | none | ✅ |
| Open settings page | `Settings.ACTION_*_SETTINGS` | none | ✅ |
| Open security/privacy settings | `ACTION_SECURITY_SETTINGS` | none | ⚠️ |
| Uninstall package | `ACTION_DELETE` / `ACTION_UNINSTALL_PACKAGE` | — | ⚠️/⛔ |

> Always `intent.resolveActivity(packageManager) != null` before `startActivity` (Android 11+ package visibility may need `<queries>`). Prefer `ACTION_DIAL`/`ACTION_SENDTO` over direct call/send. Leave `EXTRA_SKIP_UI=false` so actions stay visible.

### System toggles / special-permission APIs

| Action | API | Permission / caveat | Tier |
|---|---|---|---|
| Flashlight/torch | `CameraManager.setTorchMode` | none | ✅ |
| Media volume | `AudioManager.setStreamVolume(STREAM_MUSIC, …, FLAG_SHOW_UI)` | none | ✅ |
| Ring/notif volume | `setStreamVolume(STREAM_RING…)` | throws `SecurityException` if DND active w/o policy access | ⚠️ |
| Brightness | `Settings.System.putInt(SCREEN_BRIGHTNESS)` | `WRITE_SETTINGS` (one-time grant via `ACTION_MANAGE_WRITE_SETTINGS`) | ⚠️ |
| Do-Not-Disturb | `NotificationManager.setInterruptionFilter` | `ACCESS_NOTIFICATION_POLICY` (one-time grant) | ⚠️ |
| **Wi-Fi toggle** | ❌ `setWifiEnabled()` no-ops (targetSdk≥29) → `Settings.Panel.ACTION_WIFI` | none (deep-link only) | ✅ (handoff) |
| **Bluetooth toggle** | ❌ `enable()/disable()` deprecated (targetSdk≥33) → `ACTION_BLUETOOTH_SETTINGS` | none (deep-link) | ✅ (handoff) |
| Media transport control | `MediaController.transportControls` | own session; others need NotificationListener access | ✅ / ⚠️ |
| Read notifications | `NotificationListenerService` | **separate grant** | ⚠️ |

### Accessibility UI actions

| Action | API | Tier |
|---|---|---|
| Tap element | `performAction(ACTION_CLICK)` → fallback `dispatchGesture` | ✅ (subject to context) |
| Type into field | `ACTION_SET_TEXT` (never if `isPassword()`) | ✅ / ⛔ on secure field |
| Scroll / swipe | `ACTION_SCROLL_*` / `dispatchGesture` | ✅ |
| Back / Home / Recents / Notifications | `performGlobalAction(GLOBAL_ACTION_*)` | ✅ |
| Tap "Pay"/"Send"/"Transfer"/"Delete"/"Confirm" | text/content-desc keyword match | ⚠️/⛔ |

---

## 6. Architecture & new components

New code lives in fresh packages; **existing voice path is untouched** except the documented seams in `GeminiLiveClient` / `XenoViewModel` / `LiveProtocol`.

```
com.example
├── accessibility/
│   ├── AgentAccessibilityService.kt   # AccessibilityService: read tree + dispatch gestures/global actions
│   ├── ScreenReader.kt                # DFS → List<UiElement>, index→Rect cache, version-guarded recycle()
│   ├── UiElement.kt                   # data class {index,label,role,resourceId,bounds,clickable,editable,scrollable}
│   ├── GestureDispatcher.kt           # tap/swipe/long-press Path→StrokeDescription→GestureDescription
│   └── SecureContextDetector.kt       # FLAG_SECURE / isPassword / deny-list / keyguard checks
├── agent/
│   ├── PhoneControlExecutor.kt        # interface: suspend fun execute(name, args): ToolResult
│   ├── IntentActions.kt               # sealed DialAction/SmsDraftAction/SetAlarmAction/… (resolveActivity-guarded)
│   ├── SystemToggleActions.kt         # torch/volume/brightness/DND/Settings.Panel handoffs
│   ├── AgentTools.kt                  # tool declarations (get_screen, tap, input_text, swipe, press_back/home, open_app, task_complete)
│   ├── AgentLoopController.kt         # stepCount cap, repeat/stuck detection, cancellation
│   └── ToolResult.kt
├── permission/
│   ├── AutonomyMode.kt                # ASK/ASK_LESS/AUTO/BYPASS/PLAN (enum, DataStore-persisted)
│   ├── RiskClassifier.kt             # static SAFE/GUARDED table; classify = MAX(static, context)
│   ├── PermissionEngine.kt            # first-match: deny → forced-ask → secure-context → allow/mode
│   ├── RuleStore.kt                   # DataStore: per-action/app/session allow rules
│   ├── AuditLog.kt                    # Room: timestamp/mode/action/app/paramsHash/decision/outcome/undoToken
│   ├── RateLimiter.kt                 # token bucket + consecutive-irreversible cap + fallback counters
│   └── KillSwitch.kt
├── voice/
│   └── VoiceService.kt                # FGS type=microphone; owns AudioCapture; wake-word gate; mode state machine
└── ui/
    ├── screens/LiveScreen.kt          # **MUST CREATE** (MainActivity imports it; app won't compile without it)
    ├── agent/ConfirmSheet.kt          # ModalBottomSheet over TYPE_ACCESSIBILITY_OVERLAY
    ├── agent/PlanPreviewScreen.kt     # PLAN-mode action list
    ├── agent/AuditLogScreen.kt
    └── agent/ModePill.kt
```

### Wiring into existing code

| Existing file | Change |
|---|---|
| `live/LiveProtocol.kt` | **Add** `tools: List<LiveTool>?` + optional `toolConfig` to `LiveSetup`; `LiveTool(functionDeclarations)`, `LiveFunctionDeclaration(name,description,parameters)`. **Add** `toolCall: LiveToolCall?` + `toolCallCancellation: LiveToolCallCancellation?` to `LiveServerMessage`; `LiveToolCall(functionCalls: List<LiveFunctionCall(id,name,args:Map)>)`. **Add** `LiveToolResponseRequest(toolResponse:{functionResponses:[{id,name,response}]})`. |
| `live/GeminiLiveClient.kt` | `connect()` (lines 71–86): include `tools` in `LiveSetupRequest`. `handleServerMessage()` (130–170): dispatch `toolCall` → new `Listener.onToolCall(calls)`; route `toolCallCancellation` → `onInterrupted`. Add `sendToolResponse(responses)` mirroring `sendTextTurn` (196–214). |
| `viewmodels/XenoViewModel.kt` | `SessionListener` (274–367): implement `onToolCall` next to `onAudio`/`onTurnComplete` → set state=THINKING, run `PermissionEngine.check` + `PhoneControlExecutor.execute` on `viewModelScope` (**off the reader thread**), then `client.sendToolResponse(...)` (guard with `isCurrent()`). `startSession()` (158–166): pass the persona's tool set alongside `systemInstruction`/`voiceName`. Add StateFlows: `autonomyMode`, `pendingConfirm`, `agentStatus`. Keep executor/permission state in the ViewModel/singleton — **not** the client (rebuilt on persona switch). |
| `models/Persona.kt` | Add capability metadata; describe available phone tools inside each `systemInstruction` so the model knows when to call them. |
| `MainActivity.kt` | Unblocked once `ui/screens/LiveScreen.kt` exists. Add deep-link to `Settings.ACTION_ACCESSIBILITY_SETTINGS` for one-time enablement; detect via `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. |
| `AndroidManifest.xml` | Add the a11y `<service>` + config, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, the mic `<service foregroundServiceType="microphone">`, `POST_NOTIFICATIONS`, `<queries>`, and per-action perms (`CALL_PHONE`, `WRITE_SETTINGS`, `ACCESS_NOTIFICATION_POLICY`) only as needed. |

---

## 7. Phased implementation plan

### P0 — Foundation (make it compile & restore the UI)
- [ ] Create `ui/screens/LiveScreen.kt` collecting the existing StateFlows and composing the orphaned `ui/components/*` + `avatar/AvatarView` (AuroraOrb fallback).
- [ ] Verify clean build + working voice loop unchanged.
- [ ] Add `BuildConfig.GEMINI_API_KEY` real-key path documented (`.env`; `MY_GEMINI_API_KEY` sentinel = missing).
- **DoD:** app builds, live voice conversation works end-to-end, no regressions.

### P1 — Core control engine (no LLM yet)
- [ ] `AgentAccessibilityService` + `res/xml/agent_a11y_config.xml` (canRetrieveWindowContent + canPerformGestures).
- [ ] `ScreenReader` (DFS → `UiElement` list, index→Rect cache, version-guarded `recycle()`), `GestureDispatcher`, `SecureContextDetector`.
- [ ] `IntentActions` + `SystemToggleActions` (resolveActivity-guarded; Wi-Fi/BT → Settings.Panel handoffs).
- [ ] Manifest: a11y service + config; deep-link to Accessibility settings + enabled-state detection.
- [ ] Debug harness: a button that reads the current screen and dispatches a tap by index.
- **DoD:** can read any foreground screen as JSON and tap/swipe/navigate by index from a test trigger; secure contexts detected.

### P2 — Permission & autonomy system
- [ ] `AutonomyMode`, `RiskClassifier`, `PermissionEngine` (4-layer first-match order), `RuleStore`, `AuditLog` (Room), `RateLimiter`, `KillSwitch`.
- [ ] `ConfirmSheet` over `TYPE_ACCESSIBILITY_OVERLAY`; `PlanPreviewScreen`; `AuditLogScreen`; `ModePill`.
- [ ] Foreground service hosting audit log + rate limiter + kill switch + STOP notification + hardware panic trigger.
- [ ] Auto-fallback counters (3 consecutive / 20 total) + session scoping + undo for reversible actions.
- **DoD:** every action routes through `PermissionEngine`; secure contexts hard-blocked; confirm sheet shows literal params; kill switch + audit log functional; AUTO reverts to ASK on threshold.

### P3 — Voice agent (Gemini tool-calling)
- [ ] Extend `LiveProtocol.kt` (tools/toolConfig + toolCall/toolCallCancellation/toolResponse DTOs).
- [ ] `GeminiLiveClient`: tools in setup, `onToolCall` callback, `sendToolResponse`, cancellation → `onInterrupted`.
- [ ] `AgentTools` declarations; `PhoneControlExecutor`; `AgentLoopController` (step cap, repeat/stuck detection).
- [ ] `XenoViewModel.SessionListener.onToolCall` → permission check → execute → snapshot → `sendToolResponse` (guarded by `isCurrent()`); new agent StateFlows.
- [ ] Persona `systemInstruction` teaches the tool set.
- [ ] `VoiceService` (FGS type=microphone) + wake-word gate; manifest FGS perms.
- **DoD:** user speaks a task → model plans/acts/observes via tools → persona narrates result; barge-in cancels in-flight actions; loop terminates via `task_complete`.

### P4 — Polish & safety
- [ ] On-device redaction before any cloud call (drop password/OTP/CVV/IBAN-shaped strings + sensitive-app content).
- [ ] Ephemeral processing (no persisted raw screen dumps; hashed audit params only).
- [ ] Set-of-marks overlay for ambiguous screens; conservative throttled screenshots.
- [ ] Session resumption / latest-snapshot-only context trimming for >15-min tasks.
- [ ] First-run prominent in-app disclosure + affirmative consent; capability dashboard; per-app allow/block list.
- [ ] OEM survival hardening (battery/auto-start whitelisting guidance); paid Gemini / Vertex tier enforced.
- **DoD:** sensitive data never leaves the device; consent flow complete; long tasks survive session limits; signed sideload build.

---

## 8. Risks, policy & limitations

### Google Play policy (category-defining constraint)
- The AccessibilityService policy (updated Oct 30, 2025; **enforced Jan 28, 2026**) states verbatim: *"Any use of this API that enables an app to autonomously initiate, plan, and execute actions is prohibited."* An LLM that reads the screen and taps buttons is the **explicit textbook target**.
- **Allowed:** deterministic, user-authored `If X then Y` scripts. **Banned:** "do it for me" / "plan and execute" LLM agents.
- `isAccessibilityTool=true` is reserved for **genuine disability tools**; a general voice assistant does **not** qualify. Falsely claiming it risks app + account termination.
- **Distribution reality:** ship the autonomous build **off-Play** (sideload/APK/MDM). If any Play presence is needed, split into a separate strictly-deterministic rule-based build. Even sideload faces Play Protect auto-block of ACCESSIBILITY-declaring installs in 10+ regions, and Android Advanced Protection Mode can auto-revoke the grant.

### Security
- The required capabilities (read all screens, synthetic taps, silent screenshots, drive other apps, incidental 2FA capture) are **identical to banking-trojan TTPs** — expect heavy scrutiny / false-positive PHA flags. Request the minimum a11y event types; keep the human-confirm path on a **separate trust boundary** from the dispatch path (`dispatchGesture` bypasses `filterTouchesWhenObscured`); never expose BYPASS as a one-tap toggle.

### Privacy / redaction
- Sending UI trees to Gemini = data collection. Use the **paid Gemini API or Vertex AI** (no training, ~55-day abuse-only retention) — **never the free tier** (may be human-reviewed / used for training).
- **Redact on-device before any network call:** drop password/OTP/CVV/IBAN-shaped strings and sensitive-app content. Process **ephemerally** — never persist raw screen captures; audit log stores **hashed** params only.
- Prominent disclosure must be **in-app**, shown in normal usage, with affirmative consent — not buried in a privacy policy.

### What to exclude (hard rules)
- ⛔ Banking / payment / wallet / authenticator apps; FLAG_SECURE windows; password/OTP/CVV fields; keyguard/lockscreen; factory reset; direct `ACTION_CALL`/`SmsManager` send; money transfer; destructive deletes.
- Irreversible actions (send/call/purchase) **always** confirm, **never** AUTO; low hard cap on consecutive irreversible actions.

### Technical limitations
- **No accessibility tree** on many games, Flutter/Compose-canvas, WebViews, DRM/secure surfaces → graceful "I can't see this screen" fallback (optional vision path).
- Compose/WebView nodes often need `dispatchGesture` coordinate taps (performAction returns false).
- Indices unstable across renders → re-observe after every screen change.
- `takeScreenshot` rate-limited ~1 s; FLAG_SECURE → blank capture.
- Live audio sessions cap ~15 min / 32k context → session resumption + latest-snapshot-only trimming.
- gemini-3.1-flash-live is sequential/blocking tool-calling only.
- Mic FGS cannot start from background; force-stop disables the a11y service until re-enabled; OEMs (Samsung/Xiaomi/Huawei) aggressively kill a11y services.

---

**Relevant existing files:** `MainActivity.kt`, `viewmodels/XenoViewModel.kt`, `live/GeminiLiveClient.kt`, `live/LiveProtocol.kt`, `core/CompanionState.kt`, `models/Persona.kt`, `audio/AudioCapture.kt`, `audio/AudioStreamPlayer.kt`, `AndroidManifest.xml` (all under `/Users/aadyamadankar/working/xeno-live/app/src/main/`).