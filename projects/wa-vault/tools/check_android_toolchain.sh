#!/usr/bin/env bash
set -euo pipefail

fail(){ echo "TOOLCHAIN_ERROR: $*" >&2; exit 1; }
need(){ command -v "$1" >/dev/null 2>&1 || fail "Falta '$1' en PATH."; }

need java
need javac
need unzip
need python3

JAVA_LINE="$(java -version 2>&1 | head -1)"
JAVA_MAJOR="$(printf '%s\n' "$JAVA_LINE" | sed -E 's/.*version "([0-9]+).*/\1/')"
(( JAVA_MAJOR >= 17 && JAVA_MAJOR <= 23 )) || fail "JDK detectado=$JAVA_MAJOR. AGP 8.13.2 requiere JDK >=17 y Gradle 8.13 puede ejecutarse oficialmente hasta Java 23. Detalle: $JAVA_LINE"

if [[ -x ./gradlew ]]; then
  GRADLE=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  fail "Falta Gradle. Instala Gradle 8.13 o genera el wrapper con tools/bootstrap_gradle_wrapper.sh."
fi

GV="$("${GRADLE[@]}" --version | awk '/^Gradle /{print $2; exit}')"
[[ "$GV" == "8.13" ]] || fail "Gradle detectado=$GV; AGP 8.13.2 requiere Gradle 8.13."

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK" ]] || fail "Define ANDROID_SDK_ROOT (preferido) o ANDROID_HOME."
[[ -d "$SDK" ]] || fail "Android SDK no existe: $SDK"
[[ -f "$SDK/platforms/android-36/android.jar" ]] || fail "Falta Android SDK Platform 36: $SDK/platforms/android-36/android.jar"

APKSIGNER="$(find "$SDK/build-tools" -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -V | tail -1 || true)"
AAPT="$(find "$SDK/build-tools" -maxdepth 2 -type f -name aapt 2>/dev/null | sort -V | tail -1 || true)"
[[ -n "$APKSIGNER" ]] || fail "Falta apksigner en Android SDK Build Tools."
[[ -n "$AAPT" ]] || fail "Falta aapt en Android SDK Build Tools."

printf 'TOOLCHAIN_PASS\n'
printf 'JAVA_MAJOR=%s\n' "$JAVA_MAJOR"
printf 'GRADLE_VERSION=%s\n' "$GV"
printf 'ANDROID_SDK_ROOT=%s\n' "$SDK"
printf 'ANDROID_PLATFORM=36\n'
printf 'APKSIGNER=%s\n' "$APKSIGNER"
printf 'AAPT=%s\n' "$AAPT"
