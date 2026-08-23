#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:?}"
SRC="$RUNNER_TEMP/productshot-v1-src"
OUT="$RUNNER_TEMP/productshot-v1-final"
AUDIT="$OUT/diagnostics/block21"
mkdir -p "$AUDIT"

# Reconstruct and prove the exact accepted Block20 baseline first.
test "$(git -C "$ROOT" hash-object "$ROOT/projects/ProductShot/block8/run_v1_final_ci_block20.sh")" = "c77b2e861d6bbe1ffc12096625bc0ef7ff8f5317"
bash "$ROOT/projects/ProductShot/block8/run_v1_final_ci_block20.sh"

# Freeze/audit transformations only; no feature work is allowed here.
APPLIER21="$ROOT/projects/ProductShot/block8/block21_apply.py"
test "$(git -C "$ROOT" hash-object "$APPLIER21")" = "dbc50a52436ddfeefa2b00f1b474dca7fb96eb79"
python3 "$APPLIER21" "$SRC"

cd "$SRC"
python3 scripts/static_validate.py . | tee "$AUDIT/static-validation.txt"

# Source audit: no legacy reference/collage path, no dead pack pipeline, no network APIs.
if rg -n -i 'qualityReferencePath|referenceImagePath|CatalogSheetComposer|ReferenceStyleAnalyzer|BackgroundGenerationBroker|PhotoPackAnalyzer|PackConsistencyEngine|ON_DEVICE_PROVIDER_BRIDGED|Thread\.sleep\(|java\.net\.|okhttp|retrofit|Socket\(' app/src/main/java > "$AUDIT/forbidden-source.txt"; then
  cat "$AUDIT/forbidden-source.txt"
  echo 'Block21 forbidden legacy/network source returned' >&2
  exit 1
fi
: > "$AUDIT/forbidden-source.txt"

grep -F 'android:allowBackup="false"' app/src/main/AndroidManifest.xml
grep -F 'android:usesCleartextTraffic="false"' app/src/main/AndroidManifest.xml
grep -F 'tools:node="remove"' app/src/main/AndroidManifest.xml

# Build from a truly clean output. Release is now a mandatory audit target.
gradle --no-daemon clean

gradle --no-daemon :app:dependencies --configuration releaseRuntimeClasspath > "$AUDIT/release-dependencies.txt"
if grep -E 'play-services-mlkit-subject-segmentation|kotlinx-coroutines-play-services|com\.google\.mediapipe:tasks-vision:0\.10\.26\.1([[:space:]]|$)' "$AUDIT/release-dependencies.txt"; then
  echo 'Forbidden online/conflicting dependency returned in release runtime' >&2
  exit 1
fi
grep -F 'com.google.ai.edge.litert:litert:1.4.1' "$AUDIT/release-dependencies.txt"
grep -F 'com.google.mediapipe:tasks-vision-image-generator:0.10.26.1' "$AUDIT/release-dependencies.txt"
# MediaPipe tasks-core currently carries optional DataTransport classes. Keep them rather than
# risking a runtime NoClassDefFoundError, but prove that the final merged APK has no network permission.
grep -F 'com.google.android.datatransport:transport-runtime:3.1.0' "$AUDIT/release-dependencies.txt" > "$AUDIT/optional-transport-dependency.txt" || true

set +e
gradle --no-daemon --stacktrace :app:testDebugUnitTest 2>&1 | tee "$AUDIT/unit-test.log"
status=${PIPESTATUS[0]}
set -e
[ "$status" -eq 0 ]
python3 - <<'PYTEST'
from pathlib import Path
import xml.etree.ElementTree as ET
reports=list(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
if not reports:
    raise SystemExit('Block21 unit-test report missing / NO-SOURCE')
tests=failures=errors=0
for report in reports:
    node=ET.parse(report).getroot()
    tests += int(node.attrib.get('tests','0'))
    failures += int(node.attrib.get('failures','0'))
    errors += int(node.attrib.get('errors','0'))
if tests < 12 or failures or errors:
    raise SystemExit(f'Block21 tests invalid: tests={tests} failures={failures} errors={errors}')
print(f'PRODUCTSHOT_BLOCK21_UNIT_TESTS_OK tests={tests}')
PYTEST

set +e
gradle --no-daemon --stacktrace :app:lintRelease 2>&1 | tee "$AUDIT/lint-release.log"
status=${PIPESTATUS[0]}
set -e
[ "$status" -eq 0 ]

set +e
gradle --no-daemon --stacktrace :app:assembleRelease 2>&1 | tee "$AUDIT/build-release.log"
status=${PIPESTATUS[0]}
set -e
[ "$status" -eq 0 ]
if grep -nE '(^|[[:space:]])w: ' "$AUDIT/build-release.log"; then
  echo 'Kotlin compiler warning detected in Block21 release build' >&2
  exit 1
fi

APK="app/build/outputs/apk/release/app-release-unsigned.apk"
test -s "$APK"
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt"
ZIPALIGN="$ANDROID_HOME/build-tools/35.0.0/zipalign"
"$ZIPALIGN" -c -v 4 "$APK" | tee "$AUDIT/release-zipalign.log"
"$AAPT" dump badging "$APK" | tee "$AUDIT/release-badging.txt"
"$AAPT" dump permissions "$APK" | tee "$AUDIT/release-permissions.txt"
"$AAPT" dump xmltree "$APK" AndroidManifest.xml > "$AUDIT/release-manifest-tree.txt"

grep -F "package: name='com.tiagocrispo.furnitureshot' versionCode='100' versionName='1.0.0'" "$AUDIT/release-badging.txt"
grep -F 'android.permission.CAMERA' "$AUDIT/release-permissions.txt"
if grep -E 'android.permission.(INTERNET|ACCESS_NETWORK_STATE)' "$AUDIT/release-permissions.txt"; then
  echo 'Network permission unexpectedly present in release APK' >&2
  exit 1
fi
if grep -F 'application-debuggable' "$AUDIT/release-badging.txt"; then
  echo 'Release APK is debuggable' >&2
  exit 1
fi

unzip -l "$APK" | tee "$AUDIT/release-apk-list.txt" >/dev/null
grep -F 'assets/magic_touch.tflite' "$AUDIT/release-apk-list.txt"
grep -F 'lib/arm64-v8a/libtensorflowlite_jni.so' "$AUDIT/release-apk-list.txt"
grep -F 'lib/arm64-v8a/libmediapipe_tasks_vision_image_generator_jni.so' "$AUDIT/release-apk-list.txt"
unzip -t "$APK" | tee "$AUDIT/release-apk-integrity.txt"

# Inspect our own compiled symbols/strings for architecture that should no longer ship.
DEXDIR="$RUNNER_TEMP/productshot-block21-dex"
rm -rf "$DEXDIR" && mkdir -p "$DEXDIR"
unzip -q "$APK" 'classes*.dex' -d "$DEXDIR"
strings "$DEXDIR"/classes*.dex > "$AUDIT/release-dex-strings.txt"
if grep -E 'PhotoPackAnalyzer|PackConsistencyEngine|BackgroundGenerationBroker|CatalogSheetComposer|qualityReferencePath|referenceImagePath|ON_DEVICE_PROVIDER_BRIDGED' "$AUDIT/release-dex-strings.txt"; then
  echo 'Legacy ProductShot symbol still present in release DEX' >&2
  exit 1
fi

# Frozen source only. Block21 deliberately does NOT publish an APK artifact to the user-facing workflow.
rm -rf "$OUT/source"
mkdir -p "$OUT/source"
zip -qr "$OUT/source/ProductShot-v1.0.0-BLOCK21-FROZEN-SOURCE.zip" . -x '.gradle/*' '.kotlin/*' 'app/build/*' 'build/*'
unzip -t "$OUT/source/ProductShot-v1.0.0-BLOCK21-FROZEN-SOURCE.zip" > "$OUT/source/source-integrity.txt"
sha256sum "$OUT/source/ProductShot-v1.0.0-BLOCK21-FROZEN-SOURCE.zip" > "$OUT/source/ProductShot-v1.0.0-BLOCK21-FROZEN-SOURCE.sha256"
sha256sum "$APK" > "$AUDIT/release-unsigned-apk.sha256"

cat > "$AUDIT/BLOCK21-AUDIT-SUMMARY.txt" <<'EOF'
ProductShot v1.0 Block21 freeze audit
- direct MediaPipe background generation only; obsolete file broker removed
- orphan multi-photo pack analyzers removed
- coroutine cancellation is rethrown in async gallery/camera/save/image-loading paths
- generated result export/share/history ownership hardened to app-private jobs/<id>/
- no ML Kit / standalone tasks-vision conflict
- optional MediaPipe DataTransport classes retained for runtime compatibility, but merged release APK has no INTERNET or ACCESS_NETWORK_STATE permission
- 12+ JVM tests passed
- lintRelease passed
- assembleRelease passed from clean output
- release APK is not debuggable
- release manifest/package/version/native assets/integrity audited
- no legacy ProductShot architecture symbols in release DEX
EOF

echo 'PRODUCTSHOT_BLOCK21_FINAL_FREEZE_AUDIT_CI_OK'
