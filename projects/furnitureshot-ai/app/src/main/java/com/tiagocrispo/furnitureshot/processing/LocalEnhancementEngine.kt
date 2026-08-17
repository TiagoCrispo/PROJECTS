package com.tiagocrispo.furnitureshot.processing

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object LocalEnhancementEngine {
    suspend fun process(
        context: Context,
        originalPath: String,
        settings: ProcessSettings,
    ): ProcessResult = withContext(Dispatchers.Default) {
        val primaryMaxDimension = chooseProcessingDimension(context)
        try {
            processAttempt(
                originalPath = originalPath,
                settings = settings,
                maxDimension = primaryMaxDimension,
                memoryWarning = null,
            )
        } catch (_: OutOfMemoryError) {
            System.gc()
            processAttempt(
                originalPath = originalPath,
                settings = settings,
                maxDimension = 1280,
                memoryWarning = "La foto era muy grande; se procesó en modo seguro.",
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
        var enhanced: Bitmap? = null
        var finalBitmap: Bitmap? = null

        try {
            source = ImageStore.loadForProcessing(originalPath, maxDimension)
            coroutineContext.ensureActive()

            enhanced = applySafeToneEnhancement(source, settings)
            if (enhanced !== source) {
                source.recycle()
                source = null
            }
            coroutineContext.ensureActive()

            val matte = MlKitStudioComposer.compose(
                source = enhanced,
                shadowStrength = settings.shadowStrength,
            )
            finalBitmap = matte.bitmap
            coroutineContext.ensureActive()

            val resultFile = withContext(Dispatchers.IO) {
                ImageStore.saveResult(originalPath, finalBitmap)
            }

            val warning = listOfNotNull(memoryWarning, matte.warning)
                .joinToString(" ")
                .ifBlank { null }

            return ProcessResult(
                resultPath = resultFile.absolutePath,
                backgroundReplaced = matte.replaced,
                warning = warning,
            )
        } finally {
            finalBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            enhanced?.let { bitmap ->
                if (bitmap !== finalBitmap && !bitmap.isRecycled) bitmap.recycle()
            }
            source?.let { bitmap ->
                if (bitmap !== enhanced && bitmap !== finalBitmap && !bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun chooseProcessingDimension(context: Context): Int {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryClassMb = activityManager?.memoryClass ?: 256
        return when {
            memoryClassMb <= 192 -> 1280
            memoryClassMb <= 256 -> 1440
            memoryClassMb <= 384 -> 1600
            else -> 1920
        }
    }

    private fun applySafeToneEnhancement(source: Bitmap, settings: ProcessSettings): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val contrast = settings.contrast.coerceIn(0.98f, 1.07f)
        val brightnessOffset = settings.brightness.coerceIn(-0.03f, 0.05f) * 255f
        val translate = (1f - contrast) * 128f + brightnessOffset
        val warmthOffset = settings.warmth.coerceIn(-0.02f, 0.025f) * 255f

        val matrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate + warmthOffset,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate - warmthOffset,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }
}

private data class MatteResult(
    val bitmap: Bitmap,
    val replaced: Boolean,
    val warning: String?,
)

private object MlKitStudioComposer {
    private const val ML_MAX_DIMENSION = 1024
    private const val SEGMENTATION_TIMEOUT_MS = 35_000L
    private const val LOW_CONFIDENCE = 0.14f
    private const val HIGH_CONFIDENCE = 0.74f
    private const val COMPONENT_THRESHOLD = 0.22f
    private const val MIN_COVERAGE = 0.04f
    private const val MAX_COVERAGE = 0.76f

    suspend fun compose(source: Bitmap, shadowStrength: Float): MatteResult {
        val working = scaledForMl(source)
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        val alpha = try {
            val result = withTimeoutOrNull(SEGMENTATION_TIMEOUT_MS) {
                segmenter.process(InputImage.fromBitmap(working, 0)).await()
            }
            coroutineContext.ensureActive()
            val foreground = result?.foregroundConfidenceMask
            if (foreground == null) {
                null
            } else {
                val values = FloatArray(working.width * working.height)
                val copy = foreground.duplicate().apply { rewind() }
                var index = 0
                while (copy.hasRemaining() && index < values.size) {
                    values[index++] = softenConfidence(copy.get().coerceIn(0f, 1f))
                }
                if (index < values.size) null else AlphaMask(values, working.width, working.height)
            }
        } catch (_: Exception) {
            null
        } finally {
            segmenter.close()
        }

        if (working !== source && !working.isRecycled) working.recycle()

        if (alpha == null) {
            return MatteResult(
                bitmap = source,
                replaced = false,
                warning = "El recorte no estuvo disponible. Intenta de nuevo en unos segundos.",
            )
        }

        coroutineContext.ensureActive()
        if (!retainMainComponent(alpha.values, alpha.width, alpha.height)) {
            return MatteResult(
                bitmap = source,
                replaced = false,
                warning = "No se pudo separar el mueble del fondo con suficiente seguridad.",
            )
        }

        protectThinDetails(alpha.values, alpha.width, alpha.height)
        featherEdges(alpha.values, alpha.width, alpha.height)
        tightenFringe(alpha.values)

        val validation = validateAlpha(alpha.values, alpha.width, alpha.height)
        if (!validation.accepted) {
            return MatteResult(
                bitmap = source,
                replaced = false,
                warning = "No se pudo separar el mueble del fondo con suficiente seguridad.",
            )
        }

        val maskSmall = alphaToBitmap(alpha.values, alpha.width, alpha.height)
        val maskFull = if (alpha.width == source.width && alpha.height == source.height) {
            maskSmall
        } else {
            Bitmap.createScaledBitmap(maskSmall, source.width, source.height, true)
        }

        val sourceBounds = RectF(
            validation.bounds.left * source.width.toFloat() / alpha.width.toFloat(),
            validation.bounds.top * source.height.toFloat() / alpha.height.toFloat(),
            validation.bounds.right * source.width.toFloat() / alpha.width.toFloat(),
            validation.bounds.bottom * source.height.toFloat() / alpha.height.toFloat(),
        )
        val targetRect = buildCatalogTargetRect(
            subjectBounds = sourceBounds,
            outputWidth = source.width,
            outputHeight = source.height,
        )
        val subjectMatrix = Matrix().apply {
            setRectToRect(sourceBounds, targetRect, Matrix.ScaleToFit.FILL)
        }

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        drawStudioBackground(canvas, source.width, source.height)

        if (shadowStrength > 0f) {
            drawContactShadow(
                canvas = canvas,
                alpha = alpha.values,
                maskWidth = alpha.width,
                maskHeight = alpha.height,
                bounds = validation.bounds,
                targetRect = targetRect,
                outputWidth = source.width,
                outputHeight = source.height,
                strength = shadowStrength,
            )
        }

        val layer = canvas.saveLayer(
            0f,
            0f,
            source.width.toFloat(),
            source.height.toFloat(),
            null,
        )
        canvas.drawBitmap(
            source,
            subjectMatrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(maskFull, subjectMatrix, maskPaint)
        maskPaint.xfermode = null
        canvas.restoreToCount(layer)

        if (maskFull !== maskSmall && !maskFull.isRecycled) maskFull.recycle()
        if (!maskSmall.isRecycled) maskSmall.recycle()

        return MatteResult(bitmap = output, replaced = true, warning = null)
    }

    private data class AlphaMask(
        val values: FloatArray,
        val width: Int,
        val height: Int,
    )

    private data class MaskValidation(
        val accepted: Boolean,
        val bounds: RectF,
    )

    private fun scaledForMl(source: Bitmap): Bitmap {
        val maxDimension = max(source.width, source.height)
        if (maxDimension <= ML_MAX_DIMENSION) return source
        val scale = ML_MAX_DIMENSION.toFloat() / maxDimension.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun softenConfidence(confidence: Float): Float {
        if (confidence <= LOW_CONFIDENCE) return 0f
        if (confidence >= HIGH_CONFIDENCE) return 1f
        val t = ((confidence - LOW_CONFIDENCE) / (HIGH_CONFIDENCE - LOW_CONFIDENCE))
            .coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun retainMainComponent(alpha: FloatArray, width: Int, height: Int): Boolean {
        val size = alpha.size
        val labels = IntArray(size)
        val queue = IntArray(size)
        var nextLabel = 0
        var bestLabel = 0
        var bestScore = Float.NEGATIVE_INFINITY
        val centerX = width / 2f
        val centerY = height / 2f
        val imageArea = size.toFloat()

        for (start in 0 until size) {
            if (labels[start] != 0 || alpha[start] < COMPONENT_THRESHOLD) continue

            nextLabel++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = nextLabel
            var area = 0
            var sumX = 0L
            var sumY = 0L
            var borderPixels = 0

            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                area++
                sumX += x.toLong()
                sumY += y.toLong()
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) borderPixels++

                fun add(next: Int) {
                    if (labels[next] == 0 && alpha[next] >= COMPONENT_THRESHOLD) {
                        labels[next] = nextLabel
                        queue[tail++] = next
                    }
                }

                if (x > 0) add(index - 1)
                if (x + 1 < width) add(index + 1)
                if (y > 0) add(index - width)
                if (y + 1 < height) add(index + width)
            }

            if (area < 12) continue
            val componentCenterX = sumX.toFloat() / area.toFloat()
            val componentCenterY = sumY.toFloat() / area.toFloat()
            val dx = (componentCenterX - centerX) / width.toFloat()
            val dy = (componentCenterY - centerY) / height.toFloat()
            val distance = sqrt(dx * dx + dy * dy)
            val centerScore = (1f - distance / 0.72f).coerceIn(0f, 1f)
            val areaScore = (area.toFloat() / imageArea).coerceIn(0f, 1f)
            val edgePenalty = (borderPixels.toFloat() / area.toFloat()).coerceIn(0f, 1f)
            val score = areaScore * 0.82f + centerScore * 0.18f - edgePenalty * 0.22f

            if (score > bestScore) {
                bestScore = score
                bestLabel = nextLabel
            }
        }

        if (bestLabel == 0) return false

        for (index in alpha.indices) {
            if (labels[index] != bestLabel) alpha[index] = 0f
        }
        return true
    }

    private fun protectThinDetails(alpha: FloatArray, width: Int, height: Int) {
        val original = alpha.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var strongest = original[y * width + x]
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        strongest = max(
                            strongest,
                            original[(y + dy) * width + (x + dx)] * 0.91f,
                        )
                    }
                }
                alpha[y * width + x] = strongest.coerceIn(0f, 1f)
            }
        }
    }

    private fun featherEdges(alpha: FloatArray, width: Int, height: Int) {
        val original = alpha.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val center = original[index]
                if (center <= 0.01f || center >= 0.995f) continue
                var sum = 0f
                var count = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        sum += original[(y + dy) * width + (x + dx)]
                        count++
                    }
                }
                alpha[index] = (sum / count.toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    private fun tightenFringe(alpha: FloatArray) {
        for (index in alpha.indices) {
            val value = alpha[index]
            alpha[index] = when {
                value <= 0.06f -> 0f
                value >= 0.94f -> 1f
                else -> {
                    val t = ((value - 0.06f) / 0.88f).coerceIn(0f, 1f)
                    t * t * (3f - 2f * t)
                }
            }
        }
    }

    private fun validateAlpha(alpha: FloatArray, width: Int, height: Int): MaskValidation {
        var count = 0
        var left = width
        var right = -1
        var top = height
        var bottom = -1
        var edgeCount = 0
        val perimeter = (width * 2 + height * 2 - 4).coerceAtLeast(1)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = alpha[y * width + x]
                if (value >= 0.45f) {
                    count++
                    left = min(left, x)
                    right = max(right, x)
                    top = min(top, y)
                    bottom = max(bottom, y)
                    if (x == 0 || y == 0 || x == width - 1 || y == height - 1) edgeCount++
                }
            }
        }

        if (count == 0 || right < left || bottom < top) {
            return MaskValidation(false, RectF())
        }

        val coverage = count.toFloat() / alpha.size.toFloat()
        val bounds = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        val usefulSize = bounds.width() >= width * 0.10f && bounds.height() >= height * 0.15f
        val spansWholeWidth = left <= 1 && right >= width - 2
        val spansWholeHeight = top <= 1 && bottom >= height - 2
        val edgeRatio = edgeCount.toFloat() / perimeter.toFloat()
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val dx = (centerX - width / 2f) / width.toFloat()
        val dy = (centerY - height / 2f) / height.toFloat()
        val centerDistance = sqrt(dx * dx + dy * dy)

        return MaskValidation(
            accepted = coverage in MIN_COVERAGE..MAX_COVERAGE &&
                usefulSize &&
                !spansWholeWidth &&
                !spansWholeHeight &&
                edgeRatio < 0.35f &&
                centerDistance < 0.55f,
            bounds = bounds,
        )
    }

    private fun alphaToBitmap(alpha: FloatArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(alpha.size) { index ->
            val a = (alpha[index].coerceIn(0f, 1f) * 255f).roundToInt()
            Color.argb(a, 255, 255, 255)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun buildCatalogTargetRect(
        subjectBounds: RectF,
        outputWidth: Int,
        outputHeight: Int,
    ): RectF {
        val availableWidth = outputWidth * 0.82f
        val availableHeight = outputHeight * 0.78f
        val scale = min(
            availableWidth / subjectBounds.width().coerceAtLeast(1f),
            availableHeight / subjectBounds.height().coerceAtLeast(1f),
        )
        val targetWidth = subjectBounds.width() * scale
        val targetHeight = subjectBounds.height() * scale
        val centerX = outputWidth * 0.5f
        val bottom = outputHeight * 0.865f
        return RectF(
            centerX - targetWidth / 2f,
            bottom - targetHeight,
            centerX + targetWidth / 2f,
            bottom,
        )
    }

    private fun drawStudioBackground(canvas: Canvas, width: Int, height: Int) {
        canvas.drawColor(Color.WHITE)
        val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                height * 0.72f,
                0f,
                height.toFloat(),
                Color.WHITE,
                Color.rgb(250, 250, 248),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(
            0f,
            height * 0.72f,
            width.toFloat(),
            height.toFloat(),
            floorPaint,
        )
    }

    private fun drawContactShadow(
        canvas: Canvas,
        alpha: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        bounds: RectF,
        targetRect: RectF,
        outputWidth: Int,
        outputHeight: Int,
        strength: Float,
    ) {
        val left = bounds.left.roundToInt().coerceIn(0, maskWidth - 1)
        val right = bounds.right.roundToInt().coerceIn(left, maskWidth - 1)
        val top = bounds.top.roundToInt().coerceIn(0, maskHeight - 1)
        val bottom = bounds.bottom.roundToInt().coerceIn(top, maskHeight - 1)
        val bottomByColumn = IntArray(right - left + 1) { -1 }
        var globalBottom = -1

        for (x in left..right) {
            var found = -1
            for (y in bottom downTo top) {
                if (alpha[y * maskWidth + x] >= 0.45f) {
                    found = y
                    break
                }
            }
            bottomByColumn[x - left] = found
            globalBottom = max(globalBottom, found)
        }

        if (globalBottom < 0) return

        val tolerance = max(2, (bounds.height() * 0.035f).roundToInt())
        val scaleX = targetRect.width() / bounds.width().coerceAtLeast(1f)
        val scaleY = targetRect.height() / bounds.height().coerceAtLeast(1f)

        val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val alphaValue = (16f * strength.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 24)
            color = Color.argb(alphaValue, 20, 20, 20)
            maskFilter = BlurMaskFilter(
                max(outputWidth, outputHeight) * 0.010f,
                BlurMaskFilter.Blur.NORMAL,
            )
        }
        canvas.drawOval(
            RectF(
                targetRect.left + targetRect.width() * 0.12f,
                targetRect.bottom - outputHeight * 0.004f,
                targetRect.right - targetRect.width() * 0.12f,
                targetRect.bottom + outputHeight * 0.018f,
            ),
            ambientPaint,
        )

        val contactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val alphaValue = (58f * strength.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 70)
            color = Color.argb(alphaValue, 12, 12, 12)
            maskFilter = BlurMaskFilter(
                max(outputWidth, outputHeight) * 0.0038f,
                BlurMaskFilter.Blur.NORMAL,
            )
        }

        var runStart = -1
        var runYSum = 0
        var runCount = 0

        fun flushRun(endExclusive: Int) {
            if (runStart < 0 || runCount <= 0) return
            val startMaskX = left + runStart
            val endMaskX = left + endExclusive
            val averageMaskY = runYSum.toFloat() / runCount.toFloat()
            val x1 = targetRect.left + (startMaskX - bounds.left) * scaleX
            val x2 = targetRect.left + (endMaskX - bounds.left) * scaleX
            val y = targetRect.top + (averageMaskY - bounds.top) * scaleY
            val horizontalPadding = max(outputWidth * 0.003f, (x2 - x1) * 0.04f)
            canvas.drawOval(
                RectF(
                    (x1 - horizontalPadding).coerceAtLeast(0f),
                    y - outputHeight * 0.0025f,
                    (x2 + horizontalPadding).coerceAtMost(outputWidth.toFloat()),
                    y + outputHeight * 0.0065f,
                ),
                contactPaint,
            )
            runStart = -1
            runYSum = 0
            runCount = 0
        }

        for (index in bottomByColumn.indices) {
            val y = bottomByColumn[index]
            val isContact = y >= globalBottom - tolerance
            if (isContact) {
                if (runStart < 0) runStart = index
                runYSum += y
                runCount++
            } else if (runStart >= 0) {
                flushRun(index)
            }
        }
        if (runStart >= 0) flushRun(bottomByColumn.size)
    }
}
