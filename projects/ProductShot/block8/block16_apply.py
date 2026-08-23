#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()


def read(rel: str) -> str:
    p = root / rel
    if not p.exists():
        raise SystemExit(f'MISSING: {rel}')
    return p.read_text(encoding='utf-8')


def write(rel: str, text: str) -> None:
    (root / rel).write_text(text, encoding='utf-8')


def replace_exact(rel: str, old: str, new: str, count: int = 1) -> None:
    text = read(rel)
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f'REPLACE COUNT MISMATCH {rel}: expected {count}, found {actual}: {old!r}')
    write(rel, text.replace(old, new))


def delete_exact(rel: str) -> None:
    p = root / rel
    if not p.exists():
        raise SystemExit(f'DELETE TARGET MISSING: {rel}')
    p.unlink()

# Release identity and local-data hardening.
replace_exact('app/build.gradle.kts', '        versionCode = 61\n        versionName = "0.9.15-final-robustness"', '        versionCode = 100\n        versionName = "1.0.0"')
replace_exact('app/src/main/AndroidManifest.xml', '        android:allowBackup="true"', '        android:allowBackup="false"')

# Remove the obsolete model/reference-photo setting from the public process contract.
replace_exact('app/src/main/java/com/tiagocrispo/furnitureshot/model/Models.kt', '    val qualityReferencePath: String? = null,\n', '')

# Rename the remaining neutral studio style so the shipping source no longer carries
# reference-photo semantics. This is intentionally scoped to Kotlin shipping source.
java_root = root / 'app/src/main/java'
for p in java_root.rglob('*.kt'):
    s = p.read_text(encoding='utf-8')
    if 'CatalogReferenceStyle' in s:
        p.write_text(s.replace('CatalogReferenceStyle', 'CatalogStudioStyle'), encoding='utf-8')

# Background coordinator no longer accepts or reports a reference image.
replace_exact(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/BackgroundEngine.kt',
    '    PROCEDURAL_REFERENCE_GUIDED,\n',
    '',
)
replace_exact(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/BackgroundEngine.kt',
    '    val referenceImagePath: String?,\n',
    '',
)
replace_exact(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/BackgroundGenerationBroker.kt',
    '            put("referenceImagePath", input.referenceImagePath ?: JSONObject.NULL)\n',
    '',
)

# The main enhancement engine is now strictly one-photo input.
replace_exact(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt',
    '                    referenceImagePath = settings.qualityReferencePath,\n',
    '',
)
replace_exact(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt',
    'referenceStyle',
    'studioStyle',
    count=9,
)
replace_exact(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt',
    '// Reference-match diagnosis: beta22 was too high-key and its detail gain was',
    '// Legacy tuning diagnosis: beta22 was too high-key and its detail gain was',
)
replace_exact(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt',
    '// REFERENCE MATCH 2: outdoor highlights on varnished/finished wood',
    '// HIGHLIGHT RECOVERY 2: outdoor highlights on varnished/finished wood',
)

# Local background engines derive their look from the built-in studio style only.
replace_exact(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/MediaPipeReflectiveBackgroundProvider.kt',
    '        val base = ReferenceStyleAnalyzer.analyze(input.referenceImagePath) ?: CatalogStudioStyle.default()',
    '        val base = CatalogStudioStyle.default()',
)
replace_exact(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/ProceduralStudioBackgroundEngine.kt',
    '        val base = ReferenceStyleAnalyzer.analyze(input.referenceImagePath) ?: CatalogStudioStyle.default()',
    '        val base = CatalogStudioStyle.default()',
)
replace_exact(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/ProceduralStudioBackgroundEngine.kt',
    '            mode = if (input.referenceImagePath.isNullOrBlank()) BackgroundEngineMode.DEFAULT else BackgroundEngineMode.PROCEDURAL_REFERENCE_GUIDED,\n            provider = if (input.referenceImagePath.isNullOrBlank()) "procedural-default" else "procedural-reference-guided",',
    '            mode = BackgroundEngineMode.DEFAULT,\n            provider = "procedural-default",',
)

# Automatic settings no longer accept a second image.
replace_exact(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/PromptPolicy.kt',
    '    fun automaticSettings(qualityReferencePath: String? = null): ProcessSettings = ProcessSettings(',
    '    fun automaticSettings(): ProcessSettings = ProcessSettings(',
)
replace_exact(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/PromptPolicy.kt',
    '        qualityReferencePath = qualityReferencePath,\n',
    '',
)

# Delete dead legacy architecture so it cannot accidentally return later.
delete_exact('app/src/main/java/com/tiagocrispo/furnitureshot/processing/ReferenceStyleAnalyzer.kt')
delete_exact('app/src/main/java/com/tiagocrispo/furnitureshot/processing/CatalogSheetComposer.kt')

# Freeze validator identity and add release-only invariants.
replace_exact('scripts/static_validate.py', "req('versionCode = 61' in build, 'versionCode 61 missing')", "req('versionCode = 100' in build, 'versionCode 100 missing')")
replace_exact('scripts/static_validate.py', "req('versionName = \"0.9.15-final-robustness\"' in build, 'versionName mismatch')", "req('versionName = \"1.0.0\"' in build, 'versionName mismatch')")
replace_exact(
    'scripts/static_validate.py',
    "print('PRODUCTSHOT_BLOCK15_FINAL_ROBUSTNESS_STATIC_VALIDATION_OK')",
    """\n# v1.0 release freeze gates\nall_kotlin = '\\n'.join(q.read_text(encoding='utf-8') for q in (root / 'app/src/main/java').rglob('*.kt'))\nfor legacy in ('qualityReferencePath', 'referenceImagePath', 'ReferenceStyleAnalyzer', 'PROCEDURAL_REFERENCE_GUIDED', 'CatalogReferenceStyle', 'CatalogSheetComposer'):\n    req(legacy not in all_kotlin, f'legacy reference/collage architecture remains: {legacy}')\nreq(not (root / 'app/src/main/java/com/tiagocrispo/furnitureshot/processing/ReferenceStyleAnalyzer.kt').exists(), 'legacy reference analyzer file still ships')\nreq(not (root / 'app/src/main/java/com/tiagocrispo/furnitureshot/processing/CatalogSheetComposer.kt').exists(), 'legacy collage composer file still ships')\nreq('android:allowBackup=\"false\"' in manifest, 'backup must be disabled for local photo/model data')\nreq('android:usesCleartextTraffic=\"false\"' in manifest, 'cleartext traffic must remain disabled')\nreq('android.permission.INTERNET' not in manifest and 'android.permission.ACCESS_NETWORK_STATE' not in manifest, 'v1.0 must remain local-only')\nreq('CatalogStudioStyle' in all_kotlin, 'studio style rename missing')\nprint('PRODUCTSHOT_V1_0_0_FINAL_STATIC_VALIDATION_OK')""",
)

# Final fail-closed audit of shipping Kotlin sources.
all_kotlin = '\n'.join(p.read_text(encoding='utf-8') for p in java_root.rglob('*.kt'))
legacy = [
    'qualityReferencePath', 'referenceImagePath', 'ReferenceStyleAnalyzer',
    'PROCEDURAL_REFERENCE_GUIDED', 'CatalogReferenceStyle', 'CatalogSheetComposer',
]
left = [x for x in legacy if x in all_kotlin]
if left:
    raise SystemExit('LEGACY TOKENS REMAIN: ' + ', '.join(left))

print('PRODUCTSHOT_BLOCK16_V1_FINAL_APPLIED')
