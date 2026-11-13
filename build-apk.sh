#!/usr/bin/env bash
set -euo pipefail
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 8.9+ is required. Open the project in Android Studio or install Gradle." >&2
  exit 1
fi
exec gradle --no-daemon :app:assembleDebug
