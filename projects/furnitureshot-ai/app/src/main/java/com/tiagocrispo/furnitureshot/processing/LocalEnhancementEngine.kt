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
                memoryWarning = "La foto era muy grande; se procesó en modo seguro para evitar un cierre de la app.",
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
                warning = "El recorte no estuvo disponible. Intenta procesar de nuevo en unos segundos.",
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

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        drawStudioBackground(canvas, source.width, source.height)

        if (shadowStrength > 0f) {
            drawContactShadow(
                canvas = canvas,
                bounds = validation.bounds,
                maskWidth = alpha.width,
                maskHeight = alpha.height,
                outputWidth = source.width,
                outputHeight = source.height,
                strength = shadowStrength,
            )
        }

        val layer = canvas.saveLayer(0f, 0f, source.width.toFloat(), source.height.toFloat(), null)
        canvas.drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(maskFull, 0f, 0f, maskPaint)
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

        for (i in alpha.indices) {
            if (labels[i] != bestLabel) alpha[i] = 0f
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
                        strongest = max(strongest, original[(y + dy) * width + (x + dx)] * 0.92f)
                    }
                }
                alpha[y * width + x] = strongest.coerceIn(0f, 1f)
            }
        }
    }

    private fun featherEdges(alpha: FloatArray, width: Int, height: Int) {
        repeat(2) {
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

    private fun drawStudioBackground(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                Color.rgb(253, 253, 252),
                Color.rgb(246, 246, 244),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun drawContactShadow(
        canvas: Canvas,
        bounds: RectF,
        maskWidth: Int,
        maskHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        strength: Float,
    ) {
        val scaleX = outputWidth.toFloat() / maskWidth.toFloat()
        val scaleY = outputHeight.toFloat() / maskHeight.toFloat()
        val left = bounds.left * scaleX
        val right = bounds.right * scaleX
        val bottom = bounds.bottom * scaleY
        val objectWidth = (right - left).coerceAtLeast(outputWidth * 0.12f)

        val shadowRect = RectF(
            (left + objectWidth * 0.07f).coerceAtLeast(0f),
            (bottom - outputHeight * 0.010f).coerceAtLeast(0f),
            (right - objectWidth * 0.07f).coerceAtMost(outputWidth.toFloat()),
            (bottom + outputHeight * 0.032f).coerceAtMost(outputHeight.toFloat()),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val alpha = (38f * strength.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 48)
            color = Color.argb(alpha, 20, 20, 20)
            maskFilter = BlurMaskFilter(
                max(outputWidth, outputHeight) * 0.008f,
                BlurMaskFilter.Blur.NORMAL,
            )
        }
        canvas.drawOval(shadowRect, paint)
    }
}
