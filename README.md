# Xeno Live — voice-first phone agent, embodied by Nazim

Xeno Live is an Android AI companion — embodied by a photoreal MetaHuman named **Nazim** —
that talks with you in real time (Gemini Live, speech-to-speech) **and**, with your explicit
permission, **operates your phone for you**: opens apps, sets alarms, toggles settings, types,
taps, and runs multi-step tasks — all under a **Claude-style permission system** that keeps
secure/private things strictly off-limits.

> **Scope & design:** see [`SCOPE.md`](SCOPE.md). **Full technical blueprint:**
> [`ARCHITECTURE.md`](ARCHITECTURE.md). **How it was built:** [`BUILD_PLAN.md`](BUILD_PLAN.md).

---

## What it does

- **Talk** — real-time voice conversation with Nazim over the Gemini Live API (the original
  Xeno Live companion, preserved).
- **Act** — when you ask Nazim to *do* something, the model issues **tool calls** that are
  executed on-device through Android's **AccessibilityService** (read the screen → act →
  re-observe), each one gated by the permission engine.
- **Stay safe** — four autonomy modes, an action risk classifier, runtime secure-context
  detection, an audit log, on-device redaction, and an always-reachable kill switch.

## Permission & autonomy (the heart of it)

| Mode | Behavior |
| --- | --- |
| **Ask** (default) | Confirm every action |
| **Ask-less** | Auto-run a SAFE whitelist; confirm the rest |
| **Auto** | Run SAFE actions; risky ones go to the classifier → confirm/block |
| **Bypass** | Run everything except hard-blocked secure contexts (opt-in, kill switch armed) |
| **Plan** | Preview the action sequence; execute nothing |

In **every** mode, a denylist + forced-ask list + secure-context block override the baseline.
**Blocked, always:** banking/payment/wallet/authenticator apps, `FLAG_SECURE` screens,
password/OTP/CVV fields, lock screen, factory reset. Irreversible actions (send/call/purchase)
never auto-run and always show the literal details before you confirm.

---

## Build & run

This is an Android app (Kotlin, Jetpack Compose, Material 3). It builds with the standard
Gradle wrapper. The repo was bootstrapped to build from the command line:

**Prerequisites**
- JDK 17 (e.g. `brew install openjdk@17`)
- Android SDK with platform `android-36` + build-tools (`sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"`)
- A Gemini API key

**Steps**
```bash
# 1. Point the build at your SDK + JDK
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME="$HOME/Library/Android/sdk"
# (local.properties already sets sdk.dir)

# 2. Add your Gemini key (server-side Live API). The sentinel MY_GEMINI_API_KEY = "missing".
echo "GEMINI_API_KEY=your_real_key" > .env

# 3. Build the debug APK
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in **Android Studio** and Run.

**On-device setup (one time)**
1. Install the APK (sideload — see *Distribution* below).
2. Grant the microphone permission when prompted.
3. Enable the agent: **Settings → Accessibility → Xeno Live** (the app deep-links you here).
   This is what lets Nazim read the screen and act. You can revoke it any time.
4. Optional: grant brightness (`WRITE_SETTINGS`) / Do-Not-Disturb (notification policy) when a
   tool first needs them.

---

## Distribution

Google Play **prohibits** autonomous "plan-and-execute" AccessibilityService agents
(policy enforced Jan 28 2026), so Xeno Live ships **sideload / direct-APK**, for the owner of
the device, with explicit in-app consent. See [`ARCHITECTURE.md`](ARCHITECTURE.md) §8.

## Privacy

Screen contents are **redacted on-device** (passwords/OTP/card numbers/IBANs stripped) before
any data reaches Gemini; secure screens are never captured; the audit log stores only **hashed**
parameters, never raw field contents. Use the paid Gemini/Vertex tier (no training retention).

## Project layout

```
app/src/main/java/com/example/
├── accessibility/   read-the-screen + dispatch-gestures engine
├── agent/           agent loop, executor, tools/, AgentCoordinator, Gemini tool mapper
├── permission/      autonomy modes, risk classifier, secure-context detector, engine, audit hooks
├── security/        on-device redaction
├── audit/           Room-backed append-only audit log
├── voice/           mic foreground service, wake-word gate, command mode
├── character/       Nazim MetaHuman render (SceneView) + visemes + persona
├── live/            Gemini Live WebSocket client + tool protocol
├── ui/              LiveScreen + agent surfaces (confirm sheet, mode pill, console, audit…)
└── viewmodels/      XenoViewModel — wires it all together
```

To use a real photoreal Nazim, drop a rigged `nazim.glb` (ARKit blendshapes) into
`app/src/main/assets/`; otherwise the app renders a tasteful portrait fallback.
