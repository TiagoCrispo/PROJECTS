#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
if [[ -x ./gradlew ]]; then G=(./gradlew); else G=(gradle); fi
unset WA_VAULT_KEYSTORE_FILE WA_VAULT_KEYSTORE_PASSWORD WA_VAULT_KEY_ALIAS WA_VAULT_KEY_PASSWORD || true
TMP="${TMPDIR:-/tmp}/wavault-repro"; rm -rf "$TMP"; mkdir -p "$TMP"
build_one(){
  local n="$1"
  "${G[@]}" --no-daemon clean assembleRelease >/dev/null
  local apk; apk="$(find app/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk' | sort | head -1)"
  [[ -n "$apk" && -f "$apk" ]] || { echo 'REPRO_FAIL: release APK missing' >&2; exit 1; }
  cp "$apk" "$TMP/release-$n.apk"
  cp app/build/outputs/mapping/release/mapping.txt "$TMP/mapping-$n.txt"
}
build_one 1
build_one 2
H1="$(sha256sum "$TMP/release-1.apk" | awk '{print $1}')"; H2="$(sha256sum "$TMP/release-2.apk" | awk '{print $1}')"
M1="$(sha256sum "$TMP/mapping-1.txt" | awk '{print $1}')"; M2="$(sha256sum "$TMP/mapping-2.txt" | awk '{print $1}')"
echo "APK1=$H1"; echo "APK2=$H2"; echo "MAP1=$M1"; echo "MAP2=$M2"
[[ "$H1" == "$H2" ]] || { echo 'REPRODUCIBILITY_FAIL: release APK hashes differ' >&2; exit 1; }
[[ "$M1" == "$M2" ]] || { echo 'REPRODUCIBILITY_FAIL: mapping hashes differ' >&2; exit 1; }
echo "REPRODUCIBLE_RELEASE_PASS"
