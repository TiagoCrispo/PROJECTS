#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:?}"
BASE="$ROOT/projects/ProductShot/block8/run_v1_final_ci.sh"
TMP="$RUNNER_TEMP/run_v1_final_ci_block17b.sh"
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

replace_once(
    'python3 "$APPLIER17" "$SRC"\n\nMAGIC_DIR=',
    'python3 "$APPLIER17" "$SRC"\n\n'
    'APPLIER17B="$ROOT/projects/ProductShot/block8/block17b_apply.py"\n'
    'echo "d308be2e8f658f83934f589c8bf9ff4fc0daa04a067c3649ff4b0088be84ca38  $APPLIER17B" | sha256sum -c -\n'
    'python3 "$APPLIER17B" "$SRC"\n\n'
    'MAGIC_DIR=',
)

replace_once(
    "grep -F 'com.google.mediapipe:tasks-vision:0.10.26.1' app/build.gradle.kts",
    "if grep -F 'implementation(\"com.google.mediapipe:tasks-vision:0.10.26.1\")' app/build.gradle.kts; then\n"
    "  echo 'Standalone tasks-vision AAR unexpectedly present beside Image Generator' >&2\n"
    "  exit 1\n"
    "fi\n"
    "grep -F 'com.google.mediapipe:tasks-vision-image-generator:0.10.26.1' app/build.gradle.kts",
)

replace_once(
    "grep -F 'com.google.mediapipe:tasks-vision:0.10.26.1' \"$OUT/diagnostics/dependency-report.txt\"",
    "grep -F 'com.google.mediapipe:tasks-vision-image-generator:0.10.26.1' \"$OUT/diagnostics/dependency-report.txt\"\n"
    "if grep -E 'com\\.google\\.mediapipe:tasks-vision:0\\.10\\.26\\.1([[:space:]]|$)' \"$OUT/diagnostics/dependency-report.txt\"; then\n"
    "  echo 'Standalone tasks-vision dependency unexpectedly resolved' >&2\n"
    "  exit 1\n"
    "fi",
)

p.write_text(s, encoding='utf-8')
PY

bash "$TMP"
