package com.tiagocrispo.furnitureshot.processing

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.tiagocrispo.furnitureshot.data.ImageStore
import com.tiagocrispo.furnitureshot.model.ProcessResult
import com.tiagocrispo.furnitureshot.model.ProcessSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * High-fidelity furniture pipeline.
 *
 * Design goals:
 *  - preserve the real product pixels and geometry;
 *  - use ML only to obtain a coarse subject matte;
 *  - refine that matte at the working source resolution;
 *  - never add a generic/oval/drop shadow;
 *  - avoid a second cutout/recomposition pass that would soften wood texture.
 */
object HighFidelityEnhancementEngine {
    suspend fun process(
        context: Context,
        originalPath: String,
        settings: ProcessSettings,
    ): ProcessResult = withContext(Dispatchers.Default) {
        val primaryDimension = chooseProcessingDimension(context)
        try {
            processAttempt(
                originalPath = originalPath,
                settings = settings,
                maxDimension = primaryDimension,
                memoryWarning = null,
            )
        } catch (_: OutOfMemoryError) {
            System.gc()
            processAttempt(
                originalPath = originalPath,
                settings = settings,
                maxDimension = 1600,
                memoryWarning = "La foto era muy grande; se procesó en modo de memoria segura.",
            )
        }
    }

    private suspend fun processAttempt(
        originalPath: String,
        settings: ProcessSettings,
        maxDimension: Int,
        memoryWarning: String?,
    ): ProcessResult {
        var source: Bitmap? = null
        var composed: Bitmap? = null
        try {
            source = ImageStore.loadForProcessing(originalPath, maxDimension)
            coroutineContext.ensureActive()

            val matte = FullResolutionMatteBuilder.build(source)
            coroutineContext.ensureActive()

            if (matte == null) {
                val resultFile = withContext(Dispatchers.IO) {
                    ImageStore.saveResult(originalPath, source)
                }
                return ProcessResult(
                    resultPath = resultFile.absolutePath,
                    backgroundReplaced = false,
                    warning = listOfNotNull(
                        memoryWarning,
                        "No se pudo separar el mueble con suficiente seguridad; se conservó la foto sin inventar recorte ni sombra.",
                    ).joinToString(" "),
                )
            }

            composed = composeCatalogImage(
                source = source,
                alpha = matte.alpha,
                bounds = matte.bounds,
                settings = settings,
            )
            coroutineContext.ensureActive()

            val resultFile = withContext(Dispatchers.IO) {
                ImageStore.saveResult(originalPath, composed)
            }
            return ProcessResult(
                resultPath = resultFile.absolutePath,
                backgroundReplaced = true,
                warning = memoryWarning,
            )
        } finally {
            composed?.let { if (!it.isRecycled) it.recycle() }
            source?.let { if (it !== composed && !it.isRecycled) it.recycle() }
        }
    }

    private fun chooseProcessingDimension(context: Context): Int {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryClassMb = activityManager?.memoryClass ?: 256
        return when {
            memoryClassMb <= 192 -> 1800
            memoryClassMb <= 256 -> 2400
            memoryClassMb <= 384 -> 2800
            else -> 3200
        }
    }

    private fun composeCatalogImage(
        source: Bitmap,
        alpha: FloatArray,
        bounds: RectF,
        settings: ProcessSettings,
    ): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        drawPremiumBackground(canvas, width, height)

        val availableWidth = width * 0.84f
        val availableHeight = height * 0.80f
        val uniformScale = min(
            availableWidth / bounds.width().coerceAtLeast(1f),
            availableHeight / bounds.height().coerceAtLeast(1f),
        )
        val placedWidth = bounds.width() * uniformScale
        val placedHeight = bounds.height() * uniformScale
        val centerX = width * 0.5f
        val bottom = height * 0.905f
        val target = RectF(
            centerX - placedWidth / 2f,
            bottom - placedHeight,
            centerX + placedWidth / 2f,
            bottom,
        )

        val matrix = Matrix().apply {
            postTranslate(-bounds.left, -bounds.top)
            postScale(uniformScale, uniformScale)
            postTranslate(target.left, target.top)
        }

        val layer = canvas.saveLayer(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            null,
        )
        canvas.drawBitmap(
            source,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = buildConservativeColorFilter(settings)
            },
        )

        val mask = alphaBitmap(alpha, width, height)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(mask, matrix, maskPaint)
        maskPaint.xfermode = null
        canvas.restoreToCount(layer)
        if (!mask.isRecycled) mask.recycle()

        // Intentionally no generated shadow here. A future shadow pass is allowed only
        // when confidence proves that real contact points can be reconstructed.
        return output
    }

    private fun drawPremiumBackground(canvas: Canvas, width: Int, height: Int) {
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                Color.rgb(248, 246, 242),
                Color.rgb(241, 237, 231),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
    }

    private fun buildConservativeColorFilter(settings: ProcessSettings): ColorMatrixColorFilter {
        val contrast = settings.contrast.coerceIn(0.995f, 1.045f)
        val brightness = settings.brightness.coerceIn(-0.02f, 0.035f) * 255f
        val warmth = settings.warmth.coerceIn(-0.012f, 0.018f) * 255f
        val translate = (1f - contrast) * 128f + brightness

        val tone = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate + warmth,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate - warmth,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val saturation = ColorMatrix().apply {
            setSaturation(settings.saturation.coerceIn(0.99f, 1.025f))
        }
        tone.postConcat(saturation)
        return ColorMatrixColorFilter(tone)
    }

    private fun alphaBitmap(alpha: FloatArray, width: Int, height: Int): Bitmap {
        val bytes = ByteArray(alpha.size)
        for (i in alpha.indices) {
            bytes[i] = (alpha[i].coerceIn(0f, 1f) * 255f)
                .roundToInt()
                .coerceIn(0, 255)
                .toByte()
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also {
            it.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        }
    }
}

private data class HighResMatte(
    val alpha: FloatArray,
    val bounds: RectF,
)

private object FullResolutionMatteBuilder {
    private const val ML_MAX_DIMENSION = 1024
    private const val SEGMENTATION_TIMEOUT_MS = 35_000L
    private const val COMPONENT_THRESHOLD = 0.20f
    private const val MIN_COVERAGE = 0.025f
    private const val MAX_COVERAGE = 0.84f

    suspend fun build(source: Bitmap): HighResMatte? {
        val working = scaledForMl(source)
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        val smallAlpha = try {
            val result = withTimeoutOrNull(SEGMENTATION_TIMEOUT_MS) {
                segmenter.process(InputImage.fromBitmap(working, 0)).await()
            }
            coroutineContext.ensureActive()
            val foreground = result?.foregroundConfidenceMask ?: return null
            val values = FloatArray(working.width * working.height)
            val buffer = foreground.duplicate().apply { rewind() }
            var i = 0
            while (buffer.hasRemaining() && i < values.size) {
                values[i++] = confidenceToAlpha(buffer.get().coerceIn(0f, 1f))
            }
            if (i < values.size) null else values
        } catch (_: Throwable) {
            null
        } finally {
            segmenter.close()
        }

        if (smallAlpha == null) {
            if (working !== source && !working.isRecycled) working.recycle()
            return null
        }

        if (!retainMainComponent(smallAlpha, working.width, working.height)) {
            if (working !== source && !working.isRecycled) working.recycle()
            return null
        }
        stabilizeThinDetails(smallAlpha, working.width, working.height)

        val fullAlpha = upsampleBilinear(
            sourceWidth = source.width,
            sourceHeight = source.height,
            small = smallAlpha,
            smallWidth = working.width,
            smallHeight = working.height,
        )
        if (working !== source && !working.isRecycled) working.recycle()

        edgeGuidedRefinement(source, fullAlpha)
        removeTinyDetachedFragments(fullAlpha, source.width, source.height)
        val bounds = validateAndBounds(fullAlpha, source.width, source.height) ?: return null
        return HighResMatte(fullAlpha, bounds)
    }

    private fun scaledForMl(source: Bitmap): Bitmap {
        val longEdge = max(source.width, source.height)
        if (longEdge <= ML_MAX_DIMENSION) return source
        val scale = ML_MAX_DIMENSION.toFloat() / longEdge.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun confidenceToAlpha(confidence: Float): Float {
        val low = 0.10f
        val high = 0.82f
        if (confidence <= low) return 0f
        if (confidence >= high) return 1f
        val t = ((confidence - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun retainMainComponent(alpha: FloatArray, width: Int, height: Int): Boolean {
        val labels = IntArray(alpha.size)
        val queue = IntArray(alpha.size)
        var nextLabel = 0
        var bestLabel = 0
        var bestScore = Float.NEGATIVE_INFINITY
        val imageArea = alpha.size.toFloat()
        val cx = width / 2f
        val cy = height / 2f

        for (start in alpha.indices) {
            if (labels[start] != 0 || alpha[start] < COMPONENT_THRESHOLD) continue
            nextLabel++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = nextLabel
            var area = 0
            var sumX = 0L
            var sumY = 0L
            var border = 0

            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                area++
                sumX += x.toLong()
                sumY += y.toLong()
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border++

                if (x > 0) enqueue(index - 1, nextLabel, labels, queue, alpha, tail).also { tail = it }
                if (x + 1 < width) enqueue(index + 1, nextLabel, labels, queue, alpha, tail).also { tail = it }
                if (y > 0) enqueue(index - width, nextLabel, labels, queue, alpha, tail).also { tail = it }
                if (y + 1 < height) enqueue(index + width, nextLabel, labels, queue, alpha, tail).also { tail = it }
            }

            if (area < 12) continue
            val componentX = sumX.toFloat() / area
            val componentY = sumY.toFloat() / area
            val dx = (componentX - cx) / width
            val dy = (componentY - cy) / height
            val distance = sqrt(dx * dx + dy * dy)
            val centerScore = (1f - distance / 0.72f).coerceIn(0f, 1f)
            val areaScore = (area / imageArea).coerceIn(0f, 1f)
            val borderPenalty = (border.toFloat() / area).coerceIn(0f, 1f)
            val score = areaScore * 0.84f + centerScore * 0.16f - borderPenalty * 0.24f
            if (score > bestScore) {
                bestScore = score
                bestLabel = nextLabel
            }
        }

        if (bestLabel == 0) return false
        for (i in alpha.indices) if (labels[i] != bestLabel) alpha[i] = 0f
        return true
    }

    private fun enqueue(
        index: Int,
        label: Int,
        labels: IntArray,
        queue: IntArray,
        alpha: FloatArray,
        tail: Int,
    ): Int {
        if (labels[index] != 0 || alpha[index] < COMPONENT_THRESHOLD) return tail
        labels[index] = label
        queue[tail] = index
        return tail + 1
    }

    private fun stabilizeThinDetails(alpha: FloatArray, width: Int, height: Int) {
        val copy = alpha.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val center = copy[i]
                if (center <= 0.10f || center >= 0.98f) continue
                var strongest = center
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        strongest = max(strongest, copy[(y + dy) * width + x + dx] * 0.88f)
                    }
                }
                alpha[i] = max(center, strongest).coerceIn(0f, 1f)
            }
        }
    }

    private fun upsampleBilinear(
        sourceWidth: Int,
        sourceHeight: Int,
        small: FloatArray,
        smallWidth: Int,
        smallHeight: Int,
    ): FloatArray {
        if (sourceWidth == smallWidth && sourceHeight == smallHeight) return small
        val result = FloatArray(sourceWidth * sourceHeight)
        val xScale = if (sourceWidth > 1) (smallWidth - 1).toFloat() / (sourceWidth - 1) else 0f
        val yScale = if (sourceHeight > 1) (smallHeight - 1).toFloat() / (sourceHeight - 1) else 0f

        for (y in 0 until sourceHeight) {
            val fy = y * yScale
            val y0 = fy.toInt().coerceIn(0, smallHeight - 1)
            val y1 = min(y0 + 1, smallHeight - 1)
            val wy = fy - y0
            val row0 = y0 * smallWidth
            val row1 = y1 * smallWidth
            val outRow = y * sourceWidth
            for (x in 0 until sourceWidth) {
                val fx = x * xScale
                val x0 = fx.toInt().coerceIn(0, smallWidth - 1)
                val x1 = min(x0 + 1, smallWidth - 1)
                val wx = fx - x0
                val top = small[row0 + x0] * (1f - wx) + small[row0 + x1] * wx
                val bottom = small[row1 + x0] * (1f - wx) + small[row1 + x1] * wx
                result[outRow + x] = top * (1f - wy) + bottom * wy
            }
        }
        return result
    }

    private fun edgeGuidedRefinement(source: Bitmap, alpha: FloatArray) {
        val width = source.width
        val height = source.height
        if (width < 3 || height < 3) return
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val a = alpha[i]
                if (a <= 0.015f || a >= 0.985f) continue

                val l = luminance(pixels[i - 1])
                val r = luminance(pixels[i + 1])
                val t = luminance(pixels[i - width])
                val b = luminance(pixels[i + width])
                val gradient = ((abs(r - l) + abs(b - t)) / 510f).coerceIn(0f, 1f)

                if (gradient > 0.035f) {
                    val exponent = 1f + min(1.8f, gradient * 3.4f)
                    alpha[i] = sharpenTransition(a, exponent)
                } else if (a < 0.08f) {
                    alpha[i] = 0f
                } else if (a > 0.96f) {
                    alpha[i] = 1f
                }
            }
        }
    }

    private fun sharpenTransition(value: Float, exponent: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return if (v < 0.5f) {
            0.5f * (2f * v).pow(exponent)
        } else {
            1f - 0.5f * (2f * (1f - v)).pow(exponent)
        }.coerceIn(0f, 1f)
    }

    private fun luminance(color: Int): Float =
        (Color.red(color) * 0.2126f + Color.green(color) * 0.7152f + Color.blue(color) * 0.0722f)

    private fun removeTinyDetachedFragments(alpha: FloatArray, width: Int, height: Int) {
        val minRun = max(2, (min(width, height) * 0.0015f).roundToInt())
        for (y in 0 until height) {
            var runStart = -1
            for (x in 0..width) {
                val solid = x < width && alpha[y * width + x] >= 0.55f
                if (solid && runStart < 0) runStart = x
                if ((!solid || x == width) && runStart >= 0) {
                    val runLength = x - runStart
                    if (runLength < minRun) {
                        for (xx in runStart until x) {
                            val i = y * width + xx
                            if (alpha[i] < 0.88f) alpha[i] *= 0.35f
                        }
                    }
                    runStart = -1
                }
            }
        }
    }

    private fun validateAndBounds(alpha: FloatArray, width: Int, height: Int): RectF? {
        var count = 0
        var left = width
        var right = -1
        var top = height
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (alpha[row + x] >= 0.48f) {
                    count++
                    left = min(left, x)
                    right = max(right, x)
                    top = min(top, y)
                    bottom = max(bottom, y)
                }
            }
        }
        if (count == 0 || right <= left || bottom <= top) return null
        val coverage = count.toFloat() / alpha.size.toFloat()
        val bounds = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        if (coverage !in MIN_COVERAGE..MAX_COVERAGE) return null
        if (bounds.width() < width * 0.08f || bounds.height() < height * 0.12f) return null
        return bounds
    }
}
