#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()

def replace_exact(rel: str, old: str, new: str, count: int = 1) -> None:
    path = root / rel
    if not path.exists():
        raise SystemExit(f'MISSING: {rel}')
    text = path.read_text(encoding='utf-8')
    actual = text.count(old)
    if actual != count:
        raise SystemExit(
            f'REPLACE COUNT MISMATCH {rel}: expected {count}, found {actual}: {old!r}'
        )
    path.write_text(text.replace(old, new), encoding='utf-8')

# tasks-vision-image-generator 0.10.26.1 already packages the common vision
# classes/JNI. Keeping the standalone tasks-vision AAR beside it duplicates
# those classes and libmediapipe_tasks_vision_jni.so at APK packaging time.
# Remove only the redundant AAR; the following CI compile proves whether the
# InteractiveSegmenter API is exported by the Image Generator package itself.
replace_exact(
    'app/build.gradle.kts',
    '    implementation("com.google.mediapipe:tasks-vision:0.10.26.1")\n',
    '',
)

replace_exact(
    'scripts/static_validate.py',
    "req('com.google.mediapipe:tasks-vision:0.10.26.1' in build, 'pinned MediaPipe Interactive Segmenter dependency missing')\n",
    "req('implementation(\\\"com.google.mediapipe:tasks-vision:0.10.26.1\\\")' not in build, 'standalone tasks-vision AAR must stay absent beside Image Generator')\n"
    "req('com.google.mediapipe:tasks-vision-image-generator:0.10.26.1' in build, 'pinned MediaPipe Image Generator dependency missing')\n",
)

print('PRODUCTSHOT_BLOCK17B_REDUNDANT_VISION_AAR_REMOVED')
