#!/bin/bash
set -e
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
SDK="$HOME/Library/Android/sdk"
mkdir -p "$SDK/cmdline-tools"
cd "$SDK/cmdline-tools"

echo ">> downloading cmdline-tools"
URLS=(
  "https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
  "https://dl.google.com/android/repository/commandlinetools-mac-10406996_latest.zip"
  "https://dl.google.com/android/repository/commandlinetools-mac-9477386_latest.zip"
)
ok=0
for u in "${URLS[@]}"; do
  if curl -fsSL -o cmdline.zip "$u"; then echo "got $u"; ok=1; break; fi
done
[ "$ok" = "1" ] || { echo "FAILED to download cmdline-tools"; exit 1; }

rm -rf latest tmpcmd
unzip -q cmdline.zip -d tmpcmd
mv tmpcmd/cmdline-tools latest
rm -rf tmpcmd cmdline.zip
echo ">> cmdline-tools installed at $SDK/cmdline-tools/latest"

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"

echo ">> accepting licenses"
yes | "$SDKM" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true

echo ">> installing platform-tools, platform 36, build-tools"
"$SDKM" --sdk_root="$SDK" "platform-tools" "platforms;android-36" "build-tools;36.0.0" 2>&1 | tail -5
# extra: also grab android-35 platform as a safety net
"$SDKM" --sdk_root="$SDK" "platforms;android-35" 2>&1 | tail -3 || true

echo ">> DONE. installed packages:"
"$SDKM" --sdk_root="$SDK" --list_installed 2>/dev/null | head -40
