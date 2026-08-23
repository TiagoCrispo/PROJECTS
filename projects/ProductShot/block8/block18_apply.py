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

# Block 18: run MagicTouch directly through standalone LiteRT Interpreter.
# This avoids adding the MediaPipe tasks-vision AAR, whose Java/JNI payload
# collides with tasks-vision-image-generator, while keeping segmentation fully
# bundled and offline.
replace_exact(
    'app/build.gradle.kts',
    '    implementation("com.google.mediapipe:tasks-vision-image-generator:0.10.26.1")\n',
    '    implementation("com.google.ai.edge.litert:litert:1.4.1")\n'
    '    implementation("com.google.mediapipe:tasks-vision-image-generator:0.10.26.1")\n',
)

engine_rel = 'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt'
replace_exact(
    engine_rel,
    'import com.google.mediapipe.framework.image.BitmapImageBuilder\n'
    'import com.google.mediapipe.framework.image.ByteBufferExtractor\n'
    'import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint\n'
    'import com.google.mediapipe.tasks.core.BaseOptions\n'
    'import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter\n',
    'import org.tensorflow.lite.DataType\n'
    'import org.tensorflow.lite.Interpreter\n',
)
replace_exact(
    engine_rel,
    'import java.io.File\n',
    'import java.io.File\n'
    'import java.nio.ByteBuffer\n'
    'import java.nio.ByteOrder\n',
)
replace_exact(
    engine_rel,
    'import kotlin.math.abs\n',
    'import kotlin.math.abs\n'
    'import kotlin.math.exp\n',
)
replace_exact(
    engine_rel,
    '    private const val MAGIC_TOUCH_MODEL_ASSET = "magic_touch.tflite"\n',
    '    private const val MAGIC_TOUCH_MODEL_ASSET = "magic_touch.tflite"\n'
    '    private const val MAGIC_TOUCH_SIZE = 512\n'
    '    private const val MAGIC_TOUCH_CHANNELS = 4\n'
    '    private const val MAGIC_TOUCH_OUTPUT_CHANNELS = 2\n',
)

old_method = '''    private suspend fun buildCoarseAlpha(context: Context, source: Bitmap): CoarseAlpha? {
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
'''

new_method = '''    private suspend fun buildCoarseAlpha(context: Context, source: Bitmap): CoarseAlpha? {
        val working = scaleForSegmentation(source)
        var modelInput: Bitmap? = null
        var interpreter: Interpreter? = null
        try {
            val scale = min(
                MAGIC_TOUCH_SIZE.toFloat() / working.width.coerceAtLeast(1),
                MAGIC_TOUCH_SIZE.toFloat() / working.height.coerceAtLeast(1),
            )
            val contentWidth = (working.width * scale).roundToInt().coerceIn(1, MAGIC_TOUCH_SIZE)
            val contentHeight = (working.height * scale).roundToInt().coerceIn(1, MAGIC_TOUCH_SIZE)
            val offsetX = (MAGIC_TOUCH_SIZE - contentWidth) / 2
            val offsetY = (MAGIC_TOUCH_SIZE - contentHeight) / 2
            modelInput = Bitmap.createScaledBitmap(working, contentWidth, contentHeight, true)

            val options = Interpreter.Options().setNumThreads(
                (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
            )
            interpreter = Interpreter(loadMagicTouchModel(context), options)
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)
            val expectedInput = intArrayOf(1, MAGIC_TOUCH_SIZE, MAGIC_TOUCH_SIZE, MAGIC_TOUCH_CHANNELS)
            val expectedOutput = intArrayOf(1, MAGIC_TOUCH_SIZE, MAGIC_TOUCH_SIZE, MAGIC_TOUCH_OUTPUT_CHANNELS)
            if (!inputTensor.shape().contentEquals(expectedInput) || inputTensor.dataType() != DataType.FLOAT32) return null
            if (!outputTensor.shape().contentEquals(expectedOutput) || outputTensor.dataType() != DataType.FLOAT32) return null

            val contentPixels = IntArray(contentWidth * contentHeight)
            modelInput.getPixels(contentPixels, 0, contentWidth, 0, 0, contentWidth, contentHeight)
            val modelPixels = IntArray(MAGIC_TOUCH_SIZE * MAGIC_TOUCH_SIZE)
            for (y in 0 until contentHeight) {
                System.arraycopy(
                    contentPixels,
                    y * contentWidth,
                    modelPixels,
                    (y + offsetY) * MAGIC_TOUCH_SIZE + offsetX,
                    contentWidth,
                )
            }

            val input = ByteBuffer.allocateDirect(
                MAGIC_TOUCH_SIZE * MAGIC_TOUCH_SIZE * MAGIC_TOUCH_CHANNELS * Float.SIZE_BYTES,
            ).order(ByteOrder.nativeOrder())
            for (pixel in modelPixels) {
                input.putFloat(Color.red(pixel) / 255f)
                input.putFloat(Color.green(pixel) / 255f)
                input.putFloat(Color.blue(pixel) / 255f)
                input.putFloat(0f)
            }
            val output = ByteBuffer.allocateDirect(
                MAGIC_TOUCH_SIZE * MAGIC_TOUCH_SIZE * MAGIC_TOUCH_OUTPUT_CHANNELS * Float.SIZE_BYTES,
            ).order(ByteOrder.nativeOrder())

            var previousPriorOffset = -1
            var best: CoarseAlpha? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for ((probeX, probeY) in SEGMENTATION_PROBES) {
                coroutineContext.ensureActive()
                if (previousPriorOffset >= 0) input.putFloat(previousPriorOffset, 0f)
                val px = (offsetX + probeX * (contentWidth - 1).coerceAtLeast(0)).roundToInt()
                    .coerceIn(offsetX, offsetX + contentWidth - 1)
                val py = (offsetY + probeY * (contentHeight - 1).coerceAtLeast(0)).roundToInt()
                    .coerceIn(offsetY, offsetY + contentHeight - 1)
                val priorFloatIndex = ((py * MAGIC_TOUCH_SIZE + px) * MAGIC_TOUCH_CHANNELS) + 3
                val priorByteOffset = priorFloatIndex * Float.SIZE_BYTES
                input.putFloat(priorByteOffset, 1f)
                previousPriorOffset = priorByteOffset
                input.rewind()
                output.rewind()
                interpreter.run(input, output)

                val logits = output.asFloatBuffer()
                val values = FloatArray(contentWidth * contentHeight)
                var dst = 0
                for (y in 0 until contentHeight) {
                    val modelY = y + offsetY
                    for (x in 0 until contentWidth) {
                        val modelX = x + offsetX
                        val base = (modelY * MAGIC_TOUCH_SIZE + modelX) * MAGIC_TOUCH_OUTPUT_CHANNELS
                        val backgroundLogit = logits.get(base)
                        val subjectLogit = logits.get(base + 1)
                        val delta = (backgroundLogit - subjectLogit).coerceIn(-30f, 30f)
                        val probability = (1.0 / (1.0 + exp(delta.toDouble()))).toFloat()
                        values[dst++] = confidenceToAlpha(probability)
                    }
                }
                if (!retainMainComponent(values, contentWidth, contentHeight)) continue
                protectThinStructures(values, contentWidth, contentHeight)
                val score = scoreCoarseCandidate(values, contentWidth, contentHeight)
                if (score > bestScore) {
                    bestScore = score
                    best = CoarseAlpha(values, contentWidth, contentHeight)
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
            interpreter?.close()
            modelInput?.let { if (!it.isRecycled) it.recycle() }
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    private fun loadMagicTouchModel(context: Context): ByteBuffer {
        val bytes = context.applicationContext.assets.open(MAGIC_TOUCH_MODEL_ASSET).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }
'''
replace_exact(engine_rel, old_method, new_method)

replace_exact(
    'scripts/static_validate.py',
    "req('InteractiveSegmenter.createFromOptions' in engine and 'MAGIC_TOUCH_MODEL_ASSET' in engine, 'bundled MediaPipe segmentation path missing')\n",
    "req('com.google.ai.edge.litert:litert:1.4.1' in build, 'pinned standalone LiteRT runtime missing')\n"
    "req('InteractiveSegmenter' not in all_kotlin and 'tasks.vision.interactivesegmenter' not in all_kotlin, 'conflicting MediaPipe InteractiveSegmenter API still ships')\n"
    "req('Interpreter(loadMagicTouchModel(context)' in engine and 'MAGIC_TOUCH_MODEL_ASSET' in engine, 'direct bundled MagicTouch LiteRT path missing')\n"
    "req('MAGIC_TOUCH_SIZE = 512' in engine and 'MAGIC_TOUCH_CHANNELS = 4' in engine and 'MAGIC_TOUCH_OUTPUT_CHANNELS = 2' in engine, 'MagicTouch tensor contract missing')\n"
    "req('backgroundLogit - subjectLogit' in engine and 'exp(delta.toDouble())' in engine, 'MagicTouch foreground softmax missing')\n",
)

all_kotlin = '\n'.join(p.read_text(encoding='utf-8') for p in (root / 'app/src/main/java').rglob('*.kt'))
for token in ('InteractiveSegmenter', 'tasks.vision.interactivesegmenter'):
    if token in all_kotlin:
        raise SystemExit('BLOCK18 CONFLICTING SEGMENTER TOKEN REMAINS: ' + token)

print('PRODUCTSHOT_BLOCK18_DIRECT_LITERT_SEGMENTATION_APPLIED')
