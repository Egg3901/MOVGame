#!/usr/bin/env bash
# Bootstrap the Android SDK + NDK needed to build the Margin of Victory native
# client on a Linux host without root. Idempotent: everything already present is
# left alone.
#
# Usage: ./scripts/bootstrap-android-sdk.sh
# Env overrides: ANDROID_HOME, ANDROID_API, BUILD_TOOLS_VERSION, NDK_VERSION,
#                CMDLINE_URL
set -euo pipefail

SDK="${ANDROID_HOME:-/root/Android/Sdk}"
API="${ANDROID_API:-35}"
BUILD_TOOLS="${BUILD_TOOLS_VERSION:-35.0.0}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
CMDLINE_URL="${CMDLINE_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"

mkdir -p "$SDK/cmdline-tools"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "== downloading Android command-line tools"
  tmp="$(mktemp -d)"
  curl -fsSL "$CMDLINE_URL" -o "$tmp/cmdline-tools.zip"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

export ANDROID_HOME="$SDK"
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  export JAVA_HOME
fi

SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"
echo "== accepting licenses"
yes | "$SDKM" --licenses >/dev/null 2>&1 || true

echo "== installing platform-tools, platforms;android-$API, build-tools;$BUILD_TOOLS, ndk;$NDK_VERSION"
"$SDKM" --install \
  "platform-tools" \
  "platforms;android-$API" \
  "build-tools;$BUILD_TOOLS" \
  "ndk;$NDK_VERSION"

echo
echo "ANDROID_HOME=$SDK"
echo "NDK_HOME=$SDK/ndk/$NDK_VERSION"
echo "JAVA_HOME=$JAVA_HOME"
echo "== installed"
"$SDKM" --list_installed
