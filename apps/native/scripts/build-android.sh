#!/usr/bin/env bash
# Build the Android client. Exports the SDK/NDK/JDK environment the Tauri
# Android pipeline needs, then delegates to the Tauri CLI.
#
# Usage: ./scripts/build-android.sh [--debug] [--apk] [--aab] [--target <abi>]
# Defaults (no args): a debug APK for aarch64 only — the fastest way to prove
# the shell builds and installs.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"

export ANDROID_HOME="${ANDROID_HOME:-/root/Android/Sdk}"
if [ -z "${NDK_HOME:-}" ]; then
  NDK_HOME="$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1)"
  export NDK_HOME
fi
# Tauri/gradle builds read either name depending on the code path.
export ANDROID_NDK_HOME="$NDK_HOME"
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  export JAVA_HOME
fi

if [ ! -d "$ANDROID_HOME/ndk" ]; then
  echo "Android SDK/NDK missing under $ANDROID_HOME — run ./scripts/bootstrap-android-sdk.sh first" >&2
  exit 66
fi

echo "ANDROID_HOME=$ANDROID_HOME"
echo "NDK_HOME=$NDK_HOME"
echo "JAVA_HOME=$JAVA_HOME"

cd "$root"
if [ "$#" -eq 0 ]; then
  exec npx tauri android build --debug --apk --target aarch64 "$@"
fi
exec npx tauri android build "$@"
