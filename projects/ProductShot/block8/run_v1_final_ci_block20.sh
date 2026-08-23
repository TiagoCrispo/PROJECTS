#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:?}"
SRC="$RUNNER_TEMP/productshot-v1-src"
OUT="$RUNNER_TEMP/productshot-v1-final"

# First reconstruct and validate the exact Block 19 baseline.
test "$(git -C "$ROOT" hash-object "$ROOT/projects/ProductShot/block8/run_v1_final_ci_block19.sh")" = "5c5ea5a792ee1c1e56c48b429dc7adc757a9a57b"
bash "$ROOT/projects/ProductShot/block8/run_v1_final_ci_block19.sh"

# Apply Block 20 only after the accepted baseline exists.
APPLIER20="$ROOT/projects/ProductShot/block8/block20_apply.py"
test "$(git -C "$ROOT" hash-object "$APPLIER20")" = "c29cce541d9a1a8fa7a471e327acb50337a70e8c"
python3 "$APPLIER20" "$SRC"

cd "$SRC"
python3 scripts/static_validate.py . | tee "$OUT/diagnostics/block20-static-validation.txt"

# Build Block 20 from a clean Gradle output so no previous class/result can mask a regression.
gradle --no-daemon clean

gradle --no-daemon :app:dependencies --configuration debugRuntimeClasspath > "$OUT/diagnostics/block20-dependency-report.txt"
if grep -E 'play-services-mlkit-subject-segmentation|kotlinx-coroutines-play-services|com\.google\.mediapipe:tasks-vision:0\.10\.26\.1([[:space:]]|$)' "$OUT/diagnostics/block20-dependency-report.txt"; then
  echo 'Forbidden online/conflicting segmentation dependency returned in Block20' >&2
  exit 1
fi
grep -F 'com.google.ai.edge.litert:litert:1.4.1' "$OUT/diagnostics/block20-dependency-report.txt"
grep -F 'com.google.mediapipe:tasks-vision-image-generator:0.10.26.1' "$OUT/diagnostics/block20-dependency-report.txt"

set +e
gradle --no-daemon --stacktrace :app:testDebugUnitTest 2>&1 | tee "$OUT/diagnostics/block20-unit-test.log"
status=${PIPESTATUS[0]}
set -e
[ "$status" -eq 0 ]
python3 - <<'PYTEST'
from pathlib import Path
import xml.etree.ElementTree as ET
reports=list(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
if not reports:
    raise SystemExit('Block20 unit-test report missing / NO-SOURCE')
tests=failures=errors=0
for report in reports:
    node=ET.parse(report).getroot()
    tests += int(node.attrib.get('tests','0'))
    failures += int(node.attrib.get('failures','0'))
    errors += int(node.attrib.get('errors','0'))
if tests < 12 or failures or errors:
    raise SystemExit(f'Block20 tests invalid: tests={tests} failures={failures} errors={errors}')
print(f'PRODUCTSHOT_BLOCK20_UNIT_TESTS_OK tests={tests}')
PYTEST

set +e
gradle --no-daemon --stacktrace :app:lintDebug 2>&1 | tee "$OUT/diagnostics/block20-lint.log"
status=${PIPESTATUS[0]}
set -e
[ "$status" -eq 0 ]

set +e
gradle --no-daemon --stacktrace :app:assembleDebug 2>&1 | tee "$OUT/diagnostics/block20-build.log"
status=${PIPESTATUS[0]}
set -e
[ "$status" -eq 0 ]
if grep -nE '(^|[[:space:]])w: ' "$OUT/diagnostics/block20-build.log"; then
  echo 'Kotlin compiler warning detected in Block20 final build' >&2
  exit 1
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
test -s "$APK"
AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
ZIPALIGN="$ANDROID_HOME/build-tools/35.0.0/zipalign"
"$ZIPALIGN" -c -v 4 "$APK" | tee "$OUT/diagnostics/block20-zipalign.log"
"$APKSIGNER" verify --verbose --print-certs "$APK" | tee "$OUT/diagnostics/block20-apksigner.log"
"$AAPT" dump badging "$APK" | tee "$OUT/diagnostics/block20-badging.txt"
"$AAPT" dump permissions "$APK" | tee "$OUT/diagnostics/block20-permissions.txt"
grep -F "package: name='com.tiagocrispo.furnitureshot' versionCode='100' versionName='1.0.0'" "$OUT/diagnostics/block20-badging.txt"
grep -F 'android.permission.CAMERA' "$OUT/diagnostics/block20-permissions.txt"
if grep -E 'android.permission.(INTERNET|ACCESS_NETWORK_STATE)' "$OUT/diagnostics/block20-permissions.txt"; then
  echo 'Network permission unexpectedly present after Block20' >&2
  exit 1
fi

unzip -l "$APK" | grep -E 'lib/.*/(libtensorflowlite_jni|libmediapipe_tasks_vision|libimagegenerator_gpu).*\.so' | tee "$OUT/diagnostics/block20-native-libs.txt"
grep -F 'libtensorflowlite_jni.so' "$OUT/diagnostics/block20-native-libs.txt"
grep -F 'libmediapipe_tasks_vision_image_generator_jni.so' "$OUT/diagnostics/block20-native-libs.txt"
unzip -t "$APK" | tee "$OUT/diagnostics/block20-apk-integrity.txt"

# Replace Block19 deliverables with the Block20 build; workflow upload steps now expose only this tree.
rm -f "$OUT/apk/ProductShot-v1.0.0-FINAL.apk" "$OUT/apk/ProductShot-v1.0.0-FINAL.sha256" "$OUT/source/ProductShot-v1.0.0-FINAL-SOURCE.zip" "$OUT/source/ProductShot-v1.0.0-FINAL-SOURCE.sha256" "$OUT/source/source-integrity.txt"
cp "$APK" "$OUT/apk/ProductShot-v1.0.0-FINAL.apk"
sha256sum "$OUT/apk/ProductShot-v1.0.0-FINAL.apk" > "$OUT/apk/ProductShot-v1.0.0-FINAL.sha256"
zip -qr "$OUT/source/ProductShot-v1.0.0-FINAL-SOURCE.zip" . -x '.gradle/*' '.kotlin/*' 'app/build/*' 'build/*'
unzip -t "$OUT/source/ProductShot-v1.0.0-FINAL-SOURCE.zip" > "$OUT/source/source-integrity.txt"
sha256sum "$OUT/source/ProductShot-v1.0.0-FINAL-SOURCE.zip" > "$OUT/source/ProductShot-v1.0.0-FINAL-SOURCE.sha256"

cat >> "$OUT/apk/ProductShot-v1.0.0-FINAL-VALIDATION.txt" <<'EOF'

Block 20 integration gates:
- validated/atomic gallery and camera imports
- MediaPipe generation cooperatively checks cancellation between attempts
- background broker uses cancellable delay instead of Thread.sleep
- generated background artifacts are finally-owned and cleaned
- persisted results are validated before history commit
- cancellation/failure reconciles all uncommitted job result files
- previous/history-backed results are protected from rollback cleanup
- 12+ real JVM tests passed, including orphan/rollback safety policy
- clean Gradle rebuild, lint, APK signing/alignment/integrity and offline permission gates passed
EOF

echo 'PRODUCTSHOT_BLOCK20_PIPELINE_INTEGRATION_CI_OK'
