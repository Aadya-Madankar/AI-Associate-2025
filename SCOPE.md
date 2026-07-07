# Xeno Live — Project Scope

> **One line:** Xeno Live is a voice-first Android AI companion — embodied by a photoreal
> MetaHuman named **Nazim** — that talks with you in real time *and*, with your explicit
> permission, operates your phone for you (opens apps, types, toggles settings, runs
> multi-step tasks) under a Claude-style permission system that keeps secure/private
> things strictly off-limits.

Status of repo today: a Google AI Studio scaffold (Kotlin + Jetpack Compose + Material 3)
wired to the **Gemini Live API** for realtime speech-to-speech. It can *talk*. It cannot
*do* anything to the phone yet. This project adds the "do" half + the Nazim embodiment.

---

## 1. The two halves of the app

| Half | State | What it is |
| --- | --- | --- |
| **Companion** | Exists (upgrade) | Gemini Live realtime voice S2S, transcript, avatar. Upgrade: replace the abstract Aurora orb with the **Nazim** MetaHuman; collapse "random personas" into the single Nazim identity. |
| **Agent** | To build | Accessibility-driven phone control + a Claude-style permission/autonomy system + a voice "command mode" that runs multi-step tasks on the device. |

---

## 2. Nazim — the MetaHuman character (replaces the random/abstract avatar)

- **Identity:** A single, persistent, named persona — **Nazim** — is the face and voice of
  the app. No more "switch between random personas." Nazim is the companion.
- **Embodiment:** A photoreal 3D human head/upper-body rendered with **SceneView/Filament**
  (`io.github.sceneview:sceneview` is already a dependency). MetaHuman-grade look.
- **Liveness:** idle breathing + blinks + micro-gaze; emotion/state set
  (`IDLE`, `LISTENING`, `THINKING`, `SPEAKING`, `ACTING`); **viseme-based lip-sync** driven
  by the Gemini Live audio stream (amplitude → jaw/mouth blendshapes, upgradeable to
  phoneme→viseme mapping).
- **Asset pipeline:** a rigged **GLB humanoid with ARKit-style blendshapes** (the 52
  morph targets MetaHuman/ARKit expose) is the target asset. A true Unreal MetaHuman is
  *exported* to glTF/GLB; we render the exported mesh — we do **not** run Unreal at runtime.
  - **Fallback (if no GLB asset is supplied):** a stylized realistic portrait of Nazim with
    animated state overlays + the orb as an ambient halo, so the app is never blank.
- **The Aurora orb** is demoted from "the character" to an ambient backdrop/aura around Nazim.

> Open asset question (non-blocking): do you have a Nazim GLB/MetaHuman export, or should
> the build source/generate a placeholder rigged humanoid? The build plan covers both paths.

---

## 3. What the agent can do — and can't (the heart of the product)

The agent only ever touches **your own device, with your explicit consent.** Actions are
classified into three tiers; the tier + the active autonomy mode decide whether it runs.

| Tier | Examples | Default handling |
| --- | --- | --- |
| **Safe** | open an app, set alarm/timer, toggle Wi-Fi/Bluetooth/torch/DND, volume/brightness, media play-pause, start navigation, web search, take a screenshot, open a Settings page, read notifications, scroll/read the screen | Auto-eligible (runs without asking in Auto/Bypass) |
| **Needs-confirm** | send a message/email, place a call, post content, add a calendar event, share a file, anything that transmits data outward | Always asks (except Bypass), shows a preview |
| **Blocked** | banking/payment apps, password & OTP fields, any `FLAG_SECURE` screen, security/lock settings, factory reset, uninstalling apps, transferring money, deleting messages/photos | Hard-refused in **every** mode |

**How "secure/private" is detected at runtime:** package allow/deny list (banking/wallet
packages denied), `FLAG_SECURE` window flag, password `inputType`, content descriptions /
field hints (OTP, CVV, PIN), and Settings security sub-pages. When any signal fires, the
action is blocked and the agent says so out loud.

---

## 4. Permission & autonomy modes (Claude-style)

| Mode | Behavior | Analogy |
| --- | --- | --- |
| **Ask** (default) | Confirm every action with a preview sheet | Claude "ask each time" |
| **Ask-less / Auto** | Auto-run *Safe* actions; confirm *Needs-confirm*; block *Blocked* | Claude "auto-accept edits" |
| **Bypass** | Run everything except *Blocked*; persistent kill-switch on screen | Claude "bypass permissions / YOLO" |
| **Plan / Preview** | Generate and show the full action sequence; execute nothing | Claude "plan mode" |

Cross-cutting safety: per-app + per-action **allow/deny lists**, **session scoping**
("allow for this task only"), a full **audit log** of every observed screen + action taken,
**undo** where reversible, a hard **panic kill-switch** (notification action + shake gesture),
and **rate limiting** to stop runaway loops.

---

## 5. How it works (technical mechanism, end to end)

```
 Voice ("Nazim, silence my phone and text mom I'll be late")
   │
   ▼  on-device wake word + STT  (command mode)   ── or ──  Gemini Live audio (chat mode)
   │
   ▼  Agent Orchestrator  ── builds a compact JSON of the current screen (a11y node tree,
   │                          set-of-marks numbered elements) + the goal
   ▼  Gemini (function-calling)  ── proposes ONE tool call: e.g. setDnd(true)
   │
   ▼  Permission gate  ── classify tier → check mode → (auto | confirm sheet | block)
   │
   ▼  Action executor  ── AccessibilityService.dispatchGesture / performAction / Intent
   │
   ▼  Observe again  ── re-serialize screen → feed back to Gemini → next tool call …
   │                    (loop until goal done, "stuck" detected, or user aborts)
   ▼  Nazim speaks the result (TTS / Gemini audio) + audit log entry
```

- **Control engine:** Android **AccessibilityService** — node-tree reading,
  `performAction` (click/set-text/scroll), `dispatchGesture` (taps/swipes by coordinate),
  `performGlobalAction` (BACK/HOME/RECENTS/notifications/screenshot).
- **Brain:** Gemini **function calling**. The existing `GeminiLiveClient`/`LiveProtocol`
  handle audio/text/turn only — they will be **extended** to handle `toolCall` /
  `toolResponse`, or a parallel non-live Gemini call is used for the agent loop.
- **Screen → model:** accessibility tree compacted to numbered, bounded elements
  (set-of-marks) so the model references elements by id, not raw pixels; sensitive fields
  redacted before any data leaves the device.

---

## 6. Platform, stack, constraints

- **Platform:** Android only (v1). `minSdk 24`, `targetSdk 36`. Kotlin + Compose + Material 3.
- **Distribution:** **Sideload-first.** Google Play's Accessibility API policy is restrictive
  for general automation; v1 ships outside Play with prominent disclosure + explicit consent.
- **Privacy:** screen contents are redacted/minimized before being sent to Gemini; secure
  screens are never captured; everything is logged locally for the user to review.

---

## 7. Out of scope for v1

iOS, rooted/ADB-tethered control, a true Unreal runtime, multi-device control, and any
action in the **Blocked** tier (by design, not by omission).

---

## 8. Definition of "good state" (acceptance)

1. App builds, installs, and launches.
2. **Nazim** renders as a 3D/realistic character and lip-syncs to speech.
3. Realtime voice **conversation** works (Gemini Live) — the existing companion still works.
4. A spoken **command** runs a real multi-step task on the device via accessibility
   (e.g. "set a 10-minute timer", "turn on the torch", "open Settings → Display").
5. **Permission modes** are enforced; **Blocked** tier is genuinely unreachable.
6. **Audit log** + **kill-switch** + **onboarding/consent flow** all work.
7. Unit/Robolectric/screenshot tests pass; the project is documented.
