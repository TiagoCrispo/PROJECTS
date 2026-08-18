#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail(){ echo "ERROR: $*" >&2; exit 1; }

tools/check_android_toolchain.sh
if [[ -x ./gradlew ]]; then GRADLE=(./gradlew); else GRADLE=(gradle); fi
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME}}"

tools/run_static_validation.sh

"${GRADLE[@]}" --no-daemon --stacktrace clean \
  testDebugUnitTest testReleaseUnitTest \
  lintDebug lintRelease \
  assembleDebug assembleDebugAndroidTest assembleRelease bundleRelease

DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
RELEASE_APK="$(find app/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk' | sort | head -1)"
AAB="app/build/outputs/bundle/release/app-release.aab"
MAPPING="app/build/outputs/mapping/release/mapping.txt"
LINT_DEBUG="app/build/reports/lint-results-debug.xml"
LINT_RELEASE="app/build/reports/lint-results-release.xml"
for f in "$DEBUG_APK" "$TEST_APK" "$RELEASE_APK" "$AAB" "$MAPPING" "$LINT_DEBUG" "$LINT_RELEASE"; do [[ -f "$f" ]] || fail "Missing build artifact: $f"; done

for f in "$DEBUG_APK" "$TEST_APK" "$RELEASE_APK" "$AAB"; do unzip -t "$f" >/dev/null || fail "ZIP integrity failed: $f"; done
echo "ZIP_INTEGRITY_PASS"

APKSIGNER="$(find "$SDK/build-tools" -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -V | tail -1)"
AAPT="$(find "$SDK/build-tools" -maxdepth 2 -type f -name aapt 2>/dev/null | sort -V | tail -1)"
APK_ANALYZER="$(command -v apkanalyzer || true)"
"$APKSIGNER" verify --verbose --print-certs "$DEBUG_APK"

OUT="app/build/outputs/wavault-release-evidence"
mkdir -p "$OUT"
for pair in "debug:$DEBUG_APK" "release:$RELEASE_APK"; do
  label="${pair%%:*}"; apk="${pair#*:}"
  "$AAPT" dump badging "$apk" > "$OUT/$label-badging.txt"
  if [[ -n "$APK_ANALYZER" ]]; then "$APK_ANALYZER" manifest print "$apk" > "$OUT/$label-manifest.txt"; else "$AAPT" dump xmltree "$apk" AndroidManifest.xml > "$OUT/$label-manifest.txt"; fi
done

python3 - "$OUT/debug-badging.txt" "$OUT/debug-manifest.txt" "$OUT/release-badging.txt" "$OUT/release-manifest.txt" <<'PYAPK'
import sys
pairs=[('debug',sys.argv[1],sys.argv[2]),('release',sys.argv[3],sys.argv[4])]
for label,bp,mp in pairs:
    badging=open(bp,encoding='utf-8',errors='replace').read(); manifest=open(mp,encoding='utf-8',errors='replace').read()
    def need(c,m):
        if not c: raise SystemExit(f'APK_CONTRACT_FAIL[{label}]: {m}')
    need("targetSdkVersion:'36'" in badging,'targetSdkVersion != 36')
    for p in ('android.permission.USE_BIOMETRIC','android.permission.MANAGE_EXTERNAL_STORAGE','android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS'):
        need(p in badging,'missing permission '+p)
    for p in ('android.permission.INTERNET','android.permission.POST_NOTIFICATIONS'):
        need(p not in badging,'unexpected permission '+p)
    need('android.permission.BIND_NOTIFICATION_LISTENER_SERVICE' in manifest,'listener bind permission missing')
    need('WhatsAppNotificationListener' in manifest,'listener service missing')
    need('com.fer.wavault.permission.INTERNAL_EVENTS' in manifest,'internal signature permission missing')
    if label=='release': need('application-debuggable' not in badging,'release is debuggable')
print('APK_PACKAGED_SECURITY_CONTRACT_PASS')
PYAPK

if [[ -n "${WA_VAULT_KEYSTORE_FILE:-}" || -n "${WA_VAULT_KEY_ALIAS:-}" || -n "${WA_VAULT_KEYSTORE_PASSWORD:-}" || -n "${WA_VAULT_KEY_PASSWORD:-}" ]]; then
  [[ -n "${WA_VAULT_KEYSTORE_FILE:-}" && -n "${WA_VAULT_KEY_ALIAS:-}" && -n "${WA_VAULT_KEYSTORE_PASSWORD:-}" && -n "${WA_VAULT_KEY_PASSWORD:-}" ]] || fail "Partial release signing environment"
  "$APKSIGNER" verify --verbose --print-certs "$RELEASE_APK"
  echo "RELEASE_SIGNING_VERIFY_PASS"
else
  if "$APKSIGNER" verify "$RELEASE_APK" >/dev/null 2>&1; then echo "RELEASE_SIGNED_BY_BUILD_ENVIRONMENT"; else echo "RELEASE_UNSIGNED_EXPECTED"; fi
fi

python3 - "$LINT_DEBUG" "$LINT_RELEASE" <<'PYLINT'
import sys,xml.etree.ElementTree as ET
for path in sys.argv[1:]:
    root=ET.parse(path).getroot(); issues=list(root.findall('issue'))
    errors=[i for i in issues if i.attrib.get('severity') in {'Error','Fatal'}]
    warnings=[i for i in issues if i.attrib.get('severity')=='Warning']
    print(f'LINT_SUMMARY file={path} total={len(issues)} errors={len(errors)} warnings={len(warnings)}')
    for i in errors[:50]: print('LINT_BLOCKER',i.attrib.get('id'),i.attrib.get('message'))
    if errors: raise SystemExit(1)
PYLINT

[[ -s "$MAPPING" ]] || fail "R8 mapping is empty"
grep -q -- 'com.fer.wavault' "$MAPPING" || fail "R8 mapping does not contain WA Vault classes"
echo "R8_MAPPING_PASS"

"${GRADLE[@]}" --no-daemon :app:dependencies --configuration releaseRuntimeClasspath > "$OUT/release-runtime-dependencies.txt"
sha256sum "$DEBUG_APK" "$TEST_APK" "$RELEASE_APK" "$AAB" "$MAPPING" > "$OUT/SHA256SUMS.txt"

printf 'BUILD_PASS\nDEBUG_APK=%s\nTEST_APK=%s\nRELEASE_APK=%s\nAAB=%s\nMAPPING=%s\n' "$DEBUG_APK" "$TEST_APK" "$RELEASE_APK" "$AAB" "$MAPPING"
