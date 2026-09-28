#!/usr/bin/env bash
# Build the native Compose Android client.
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./gradlew :androidApp:assembleDebug "$@"
