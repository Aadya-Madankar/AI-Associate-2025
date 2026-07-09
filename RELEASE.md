# Building & installing Xeno Live

## Install (sideload) — the normal path

Xeno Live is **sideload-only** (Google Play prohibits plan-and-execute accessibility agents), so you install the APK directly.

1. Build the debug APK:
   ```bash
   export JAVA_HOME=/opt/homebrew/opt/openjdk@17
   export ANDROID_HOME="$HOME/Library/Android/sdk"
   ./gradlew :app:assembleDebug
   # -> app/build/outputs/apk/debug/app-debug.apk
   ```
2. Get it onto the phone — either:
   - USB + `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or
   - serve it and download in the phone browser: `python3 -m http.server 8080` in the folder, then open `http://<your-mac-lan-ip>:8080/app-debug.apk`.
3. Allow "install unknown apps" for the browser/file manager, then tap the APK to install.

## First-run setup (required)

1. **Microphone** — grant when prompted.
2. **Gemini API key** — open Settings (gear) → API keys, paste your key. (Needed to connect.)
3. **Accessibility** — Settings → Accessibility → Xeno Live → enable (lets it tap/type/scroll & roam). The app deep-links you there; choosing Auto/Bypass also prompts.
4. **Display over other apps** — allow when it first roams.

## App bundle (AAB)

```bash
./gradlew :app:bundleDebug     # -> app/build/outputs/bundle/debug/app-debug.aab (debug-signed)
```

An AAB can't be sideloaded directly; use `bundletool` to turn it into installable APKs, or upload it to a store. Because this app class is banned from Google Play, the AAB + release signing exist for completeness (or a private/enterprise track), not the Play Store.

### Release (signed) bundle

The release signing config reads a keystore from env vars (`app/build.gradle.kts`). To build a signed release AAB:

```bash
# one-time: generate an upload key (keep the .jks and passwords secret; it is gitignored)
keytool -genkeypair -v -keystore my-upload-key.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias upload

export KEYSTORE_PATH="$PWD/my-upload-key.jks"
export STORE_PASSWORD='<your store password>'
export KEY_PASSWORD='<your key password>'
./gradlew :app:bundleRelease   # -> app/build/outputs/bundle/release/app-release.aab
```

Never commit `my-upload-key.jks` or the passwords.
