#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()

def replace(rel: str, old: str, new: str) -> None:
    path = root / rel
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Expected source fragment not found in {rel}: {old!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")

broker = "app/src/main/java/com/tiagocrispo/furnitureshot/processing/BackgroundGenerationBroker.kt"
replace(broker, 'val outputPath = json.optString("outputPath")', 'val outputPath = json?.optString("outputPath").orEmpty()')
replace(
    broker,
    'warning = json.optString("warning").ifBlank { "Se generó un background plate local mediante el broker interno." },',
    'warning = json?.optString("warning").orEmpty().ifBlank { "Se generó un background plate local mediante el broker interno." },',
)
replace(
    broker,
    'warning = json.optString("warning").ifBlank { "El backend local informó un error y se aplicó el fallback seguro." },',
    'warning = json?.optString("warning").orEmpty().ifBlank { "El backend local informó un error y se aplicó el fallback seguro." },',
)

engine = "app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt"
replace(
    engine,
    'processAttempt(originalPath, settings, preferredDimension, null, onProgress)',
    'processAttempt(context, originalPath, settings, preferredDimension, null, onProgress)',
)
replace(
    engine,
    'processAttempt(\n                originalPath,',
    'processAttempt(\n                context,\n                originalPath,',
)
replace(
    engine,
    '    private suspend fun processAttempt(\n        originalPath: String,',
    '    private suspend fun processAttempt(\n        context: Context,\n        originalPath: String,',
)

print("PRODUCTSHOT_BLOCK8_COMPILE_HOTFIX_APPLIED")
