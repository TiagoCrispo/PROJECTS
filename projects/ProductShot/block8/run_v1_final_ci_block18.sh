#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:?}"
BASE="$ROOT/projects/ProductShot/block8/run_v1_final_ci.sh"
TMP="$RUNNER_TEMP/run_v1_final_ci_block18.sh"
cp "$BASE" "$TMP"

python3 - "$TMP" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')

def replace_once(old: str, new: str) -> None:
    global s
    if s.count(old) != 1:
        raise SystemExit(f'wrapper replacement mismatch: {old!r}')
    s = s.replace(old, new)

# Replay Block 17B (remove the conflicting tasks-vision AAR), then Block 18
# (run MagicTouch directly with standalone LiteRT Interpreter).
replace_once(
    'python3 "$APPLIER17" "$SRC"\n\nMAGIC_DIR=',
    'python3 "$APPLIER17" "$SRC"\n\n'
    'APPLIER17B="$ROOT/projects/ProductShot/block8/block17b_apply.py"\n'
    'echo "d308be2e8f658f83934f589c8bf9ff4fc0daa04a067c3649ff4b0088be84ca38  $APPLIER17B" | sha256sum -c -\n'
    'python3 "$APPLIER17B" "$SRC"\n\n'
    'APPLIER18="$ROOT/projects/ProductShot/block8/block18_apply.py"\n'
    'test "$(git -C "$ROOT" hash-object "$APPLIER18")" = "e2991884fe34d709e30342fdbd7ca1f56d612390"\n'
    'python3 "$APPLIER18" "$SRC"\n\n'
    'MAGIC_DIR=',
)

replace_once(
    "grep -F 'com.google.mediapipe:tasks-vision:0.10.26.1' app/build.gradle.kts",
    "if grep -F 'implementation(\"com.google.mediapipe:tasks-vision:0.10.26.1\")' app/build.gradle.kts; then\n"
    "  echo 'Standalone tasks-vision AAR unexpectedly present beside Image Generator' >&2\n"
    "  exit 1\n"
    "fi\n"
    "grep -F 'com.google.ai.edge.litert:litert:1.4.1' app/build.gradle.kts\n"
    "grep -F 'com.google.mediapipe:tasks-vision-image-generator:0.10.26.1' app/build.gradle.kts",
)

replace_once(
    "grep -F 'InteractiveSegmenter.createFromOptions' app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt",
    "grep -F 'Interpreter(loadMagicTouchModel(context)' app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt\n"
    "if grep -RniE 'InteractiveSegmenter|tasks\\.vision\\.interactivesegmenter' app/src/main/java; then\n"
    "  echo 'Conflicting MediaPipe InteractiveSegmenter API unexpectedly remains' >&2\n"
    "  exit 1\n"
    "fi",
)

replace_once(
    "grep -F 'com.google.mediapipe:tasks-vision:0.10.26.1' \"$OUT/diagnostics/dependency-report.txt\"",
    "grep -F 'com.google.ai.edge.litert:litert:1.4.1' \"$OUT/diagnostics/dependency-report.txt\"\n"
    "grep -F 'com.google.mediapipe:tasks-vision-image-generator:0.10.26.1' \"$OUT/diagnostics/dependency-report.txt\"\n"
    "if grep -E 'com\\.google\\.mediapipe:tasks-vision:0\\.10\\.26\\.1([[:space:]]|$)' \"$OUT/diagnostics/dependency-report.txt\"; then\n"
    "  echo 'Standalone tasks-vision dependency unexpectedly resolved' >&2\n"
    "  exit 1\n"
    "fi",
)

replace_once(
    "- bundled pinned MagicTouch model: SHA-256 e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25\n",
    "- bundled pinned MagicTouch model: SHA-256 e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25\n"
    "- MagicTouch executes directly through pinned standalone LiteRT 1.4.1\n"
    "- conflicting MediaPipe tasks-vision AAR absent\n",
)

# Record the exact native payload in the final binary. Package-time duplicate JNI
# failures are fatal; this report also proves the isolated LiteRT JNI is present.
replace_once(
    'unzip -t "$APK" | tee "$OUT/diagnostics/apk-integrity.txt"\n',
    'unzip -l "$APK" | grep -E \'lib/.*/(libtensorflowlite_jni|libmediapipe_tasks_vision|libimagegenerator_gpu).*\\.so\' | tee "$OUT/diagnostics/native-libs.txt"\n'
    'grep -F \'libtensorflowlite_jni.so\' "$OUT/diagnostics/native-libs.txt"\n'
    'grep -F \'libmediapipe_tasks_vision_image_generator_jni.so\' "$OUT/diagnostics/native-libs.txt"\n'
    'unzip -t "$APK" | tee "$OUT/diagnostics/apk-integrity.txt"\n',
)

p.write_text(s, encoding='utf-8')
PY

bash "$TMP"
