#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail(){ echo "WRAPPER_ERROR: $*" >&2; exit 1; }
command -v gradle >/dev/null 2>&1 || fail "Necesitas Gradle 8.13 instalado una sola vez para generar el wrapper."
GV="$(gradle --version | awk '/^Gradle /{print $2; exit}')"
[[ "$GV" == "8.13" ]] || fail "Gradle detectado=$GV; usa exactamente 8.13 para generar el wrapper."

gradle wrapper --gradle-version 8.13 --distribution-type bin
./gradlew --version

echo "GRADLE_WRAPPER_READY"
