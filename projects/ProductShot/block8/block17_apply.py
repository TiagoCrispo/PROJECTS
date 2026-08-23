#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()

def read(rel: str) -> str:
    p = root / rel
    if not p.exists():
        raise SystemExit(f'MISSING: {rel}')
    return p.read_text(encoding='utf-8')

def write(rel: str, text: str) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')

def replace_exact(rel: str, old: str, new: str, count: int = 1) -> None:
    text = read(rel)
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f'REPLACE COUNT MISMATCH {rel}: expected {count}, found {actual}: {old!r}')
    write(rel, text.replace(old, new))

# Keep all MediaPipe APIs on the same 0.10.26.1 family. Remove ML Kit's
# dynamically downloaded subject model and the Play Services coroutine bridge.
replace_exact(
    'app/build.gradle.kts',
    '    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")\n\n'
    '    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")\n'
    '    implementation("com.google.mediapipe:tasks-vision-image-generator:0.10.26.1")',
    '    implementation("com.google.mediapipe:tasks-vision:0.10.26.1")\n'
    '    implementation("com.google.mediapipe:tasks-vision-image-generator:0.10.26.1")',
)

# ProductShot remains deliberately offline. MediaPipe Tasks transitively carries
# optional DataTransport declarations, so remove network capabilities at manifest
# merge time instead of relying on the library manifests staying unchanged.
replace_exact(
    'app/src/main/AndroidManifest.xml',
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    xmlns:tools="http://schemas.android.com/tools">',
)
replace_exact(
    'app/src/main/AndroidManifest.xml',
    '    <uses-permission android:name="android.permission.CAMERA" />\n',
    '    <uses-permission android:name="android.permission.CAMERA" />\n\n'
    '    <!-- MediaPipe Tasks brings optional telemetry transports transitively.\n'
    '         ProductShot is intentionally offline: strip their network capabilities\n'
    '         from the final merged APK. -->\n'
    '    <uses-permission\n'
    '        android:name="android.permission.INTERNET"\n'
    '        tools:node="remove" />\n'
    '    <uses-permission\n'
    '        android:name="android.permission.ACCESS_NETWORK_STATE"\n'
    '        tools:node="remove" />\n',
)
replace_exact(
    'app/src/main/AndroidManifest.xml',
    '\n        <meta-data\n'
    '            android:name="com.google.mlkit.vision.DEPENDENCIES"\n'
    '            android:value="subject_segment" />\n',
    '\n',
)

engine_rel = 'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt'
replace_exact(
    engine_rel,
    'import com.google.mlkit.vision.common.InputImage\n'
    'import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation\n'
    'import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions\n',
    'import com.google.mediapipe.framework.image.BitmapImageBuilder\n'
    'import com.google.mediapipe.framework.image.ByteBufferExtractor\n'
    'import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint\n'
    'import com.google.mediapipe.tasks.core.BaseOptions\n'
    'import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter\n',
)
replace_exact(engine_rel, 'import kotlinx.coroutines.tasks.await\n', '')
replace_exact(engine_rel, 'import kotlinx.coroutines.withTimeoutOrNull\n', '')
replace_exact(engine_rel, '    private const val SEGMENTATION_TIMEOUT_MS = 35_000L\n', '')
replace_exact(
    engine_rel,
    '    private const val MAX_COVERAGE = 0.86f\n',
    '    private const val MAX_COVERAGE = 0.86f\n'
    '    private const val MAGIC_TOUCH_MODEL_ASSET = "magic_touch.tflite"\n'
    '    private val SEGMENTATION_PROBES = arrayOf(\n'
    '        0.50f to 0.52f,\n'
    '        0.50f to 0.64f,\n'
    '        0.42f to 0.55f,\n'
    '        0.58f to 0.55f,\n'
    '        0.50f to 0.40f,\n'
    '    )\n',
)
replace_exact(
    engine_rel,
    'val coarse = buildCoarseAlpha(source) ?: return saveConservativeFallback(',
    'val coarse = buildCoarseAlpha(context, source) ?: return saveConservativeFallback(',
)
replace_exact(
    engine_rel,
    '''    private suspend fun buildCoarseAlpha(source: Bitmap): CoarseAlpha? {
        val working = scaleForSegmentation(source)
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
        )
        try {
            val result = withTimeoutOrNull(SEGMENTATION_TIMEOUT_MS) {
                segmenter.process(InputImage.fromBitmap(working, 0)).await()
            } ?: return null
            coroutineContext.ensureActive()
            val buffer = result.foregroundConfidenceMask ?: return null
            val values = FloatArray(working.width * working.height)
            val copy = buffer.duplicate().apply { rewind() }
            var i = 0
            while (copy.hasRemaining() && i < values.size) {
                val confidence = copy.get().coerceIn(0f, 1f)
                values[i++] = confidenceToAlpha(confidence)
            }
            if (i < values.size) return null
            if (!retainMainComponent(values, working.width, working.height)) return null
            protectThinStructures(values, working.width, working.height)
            return CoarseAlpha(values, working.width, working.height)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (_: Exception) {
            return null
        } finally {
            segmenter.close()
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }
''',
    '''    private suspend fun buildCoarseAlpha(context: Context, source: Bitmap): CoarseAlpha? {
        val working = scaleForSegmentation(source)
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MAGIC_TOUCH_MODEL_ASSET)
            .build()
        val options = InteractiveSegmenter.InteractiveSegmenterOptions.builder()
            .setBaseOptions(baseOptions)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build()
        val segmenter = InteractiveSegmenter.createFromOptions(context.applicationContext, options)
        val mpImage = BitmapImageBuilder(working).build()
        try {
            var best: CoarseAlpha? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for ((x, y) in SEGMENTATION_PROBES) {
                coroutineContext.ensureActive()
                val roi = InteractiveSegmenter.RegionOfInterest.create(
                    NormalizedKeypoint.create(x, y),
                )
                val result = segmenter.segment(mpImage, roi)
                val masks = result.confidenceMasks().orElse(emptyList())
                try {
                    // MagicTouch's embedded labels are [background, subject], so the last
                    // confidence mask is the requested product/subject mask.
                    val subjectMask = masks.lastOrNull() ?: continue
                    val width = subjectMask.width
                    val height = subjectMask.height
                    if (width < 2 || height < 2) continue
                    val floats = ByteBufferExtractor.extract(subjectMask).asFloatBuffer()
                    val values = FloatArray(width * height)
                    if (floats.remaining() < values.size) continue
                    floats.get(values)
                    for (i in values.indices) {
                        values[i] = confidenceToAlpha(values[i].coerceIn(0f, 1f))
                    }
                    if (!retainMainComponent(values, width, height)) continue
                    protectThinStructures(values, width, height)
                    val score = scoreCoarseCandidate(values, width, height)
                    if (score > bestScore) {
                        bestScore = score
                        best = CoarseAlpha(values, width, height)
                    }
                } finally {
                    masks.forEach { it.close() }
                }
            }
            return best
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (_: Exception) {
            return null
        } finally {
            mpImage.close()
            segmenter.close()
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    private fun scoreCoarseCandidate(alpha: FloatArray, width: Int, height: Int): Float {
        var foreground = 0
        var border = 0
        var centerEnergy = 0f
        val centerX0 = (width * 0.34f).roundToInt().coerceIn(0, width - 1)
        val centerX1 = (width * 0.66f).roundToInt().coerceIn(0, width - 1)
        val centerY0 = (height * 0.30f).roundToInt().coerceIn(0, height - 1)
        val centerY1 = (height * 0.72f).roundToInt().coerceIn(0, height - 1)
        var centerSamples = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val a = alpha[y * width + x]
                if (a >= COMPONENT_THRESHOLD) {
                    foreground++
                    if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border++
                }
                if (x in centerX0..centerX1 && y in centerY0..centerY1) {
                    centerEnergy += a
                    centerSamples++
                }
            }
        }
        val coverage = foreground.toFloat() / alpha.size.coerceAtLeast(1)
        if (coverage !in MIN_COVERAGE..MAX_COVERAGE) return Float.NEGATIVE_INFINITY
        val borderRatio = if (foreground == 0) 1f else border.toFloat() / foreground
        val centerMean = centerEnergy / centerSamples.coerceAtLeast(1)
        return coverage * 0.72f + centerMean * 0.28f - borderRatio * 0.35f
    }
''',
)

# Strengthen static release validation. The model bytes are injected by the
# deterministic CI runner before this validator executes.
replace_exact('scripts/static_validate.py', 'import xml.etree.ElementTree as ET\n', 'import xml.etree.ElementTree as ET\nimport hashlib\n')
replace_exact(
    'scripts/static_validate.py',
    "req('android.permission.INTERNET' not in manifest, 'INTERNET permission must remain absent')\n"
    "req('android.permission.ACCESS_NETWORK_STATE' not in manifest, 'ACCESS_NETWORK_STATE must remain absent')",
    "req('android.permission.INTERNET' in manifest and 'android.permission.ACCESS_NETWORK_STATE' in manifest, 'explicit network-permission removals missing')\n"
    "req(manifest.count('tools:node=\"remove\"') >= 2, 'network permissions must be removed by manifest merger')",
)
replace_exact(
    'scripts/static_validate.py',
    "req('android.permission.INTERNET' not in manifest and 'android.permission.ACCESS_NETWORK_STATE' not in manifest, 'v1.0 must remain local-only')",
    "req(manifest.count('tools:node=\"remove\"') >= 2, 'v1.0 merged network permissions must be stripped')",
)
replace_exact(
    'scripts/static_validate.py',
    "req('CatalogStudioStyle' in all_kotlin, 'studio style rename missing')\n",
    "req('CatalogStudioStyle' in all_kotlin, 'studio style rename missing')\n"
    "req('play-services-mlkit-subject-segmentation' not in build, 'ML Kit subject segmentation dependency still ships')\n"
    "req('kotlinx-coroutines-play-services' not in build, 'obsolete Play Services coroutine bridge still ships')\n"
    "req('com.google.mediapipe:tasks-vision:0.10.26.1' in build, 'pinned MediaPipe Interactive Segmenter dependency missing')\n"
    "req('com.google.mlkit' not in all_kotlin and 'SubjectSegmentation' not in all_kotlin, 'ML Kit segmentation code still ships')\n"
    "req('subject_segment' not in manifest and 'com.google.mlkit.vision.DEPENDENCIES' not in manifest, 'ML Kit runtime model metadata still ships')\n"
    "req('InteractiveSegmenter.createFromOptions' in engine and 'MAGIC_TOUCH_MODEL_ASSET' in engine, 'bundled MediaPipe segmentation path missing')\n"
    "model = root / 'app/src/main/assets/magic_touch.tflite'\n"
    "req(model.exists() and model.stat().st_size == 6227884, 'pinned MagicTouch asset missing or wrong size')\n"
    "req(hashlib.sha256(model.read_bytes()).hexdigest() == 'e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25', 'MagicTouch asset checksum mismatch')\n",
)

# Fail closed against accidental reintroduction of the dynamic ML Kit path.
all_kotlin = '\n'.join(p.read_text(encoding='utf-8') for p in (root / 'app/src/main/java').rglob('*.kt'))
for token in ('com.google.mlkit', 'SubjectSegmentation', 'SubjectSegmenterOptions', 'kotlinx.coroutines.tasks.await'):
    if token in all_kotlin:
        raise SystemExit('BLOCK17 LEGACY SEGMENTATION TOKEN REMAINS: ' + token)

print('PRODUCTSHOT_BLOCK17_OFFLINE_SEGMENTATION_APPLIED')
