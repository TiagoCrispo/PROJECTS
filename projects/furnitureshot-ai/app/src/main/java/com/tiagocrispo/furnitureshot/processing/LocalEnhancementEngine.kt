package com.tiagocrispo.furnitureshot.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.tiagocrispo.furnitureshot.data.ImageStore
import com.tiagocrispo.furnitureshot.model.BackgroundMode
import com.tiagocrispo.furnitureshot.model.ProcessResult
import com.tiagocrispo.furnitureshot.model.ProcessSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object LocalEnhancementEngine {
    suspend fun process(
        context: Context,
        originalPath: String,
        settings: ProcessSettings,
    ): ProcessResult = withContext(Dispatchers.Default) {
        val source = ImageStore.loadForProcessing(originalPath)
        coroutineContext.ensureActive()

        val enhanced = applySafeToneEnhancement(source, settings)
        if (enhanced !== source) source.recycle()
        coroutineContext.ensureActive()

        val matte = when (settings.backgroundMode) {
            BackgroundMode.KEEP_ORIGINAL -> MatteResult(
                bitmap = enhanced,
                replaced = false,
                warning = null,
            )
            BackgroundMode.STUDIO_WHITE -> MlKitStudioComposer.compose(
                source = enhanced,
                shadowStrength = settings.shadowStrength,
            )
        }
        coroutineContext.ensureActive()

        val resultFile = withContext(Dispatchers.IO) {
            ImageStore.saveResult(originalPath, matte.bitmap)
        }

        if (matte.bitmap !== enhanced) enhanced.recycle()
        matte.bitmap.recycle()

        ProcessResult(
            resultPath = resultFile.absolutePath,
            backgroundReplaced = matte.replaced,
            warning = listOfNotNull(settings.fidelityWarning, matte.warning)
                .joinToString(" ")
                .ifBlank { null },
        )
    }

    private fun applySafeToneEnhancement(source: Bitmap, settings: ProcessSettings): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val contrast = settings.contrast.coerceIn(0.97f, 1.08f)
        val brightnessOffset = settings.brightness.coerceIn(-0.04f, 0.065f) * 255f
        val translate = (1f - contrast) * 128f + brightnessOffset
        val warmthOffset = settings.warmth.coerceIn(-0.025f, 0.035f) * 255f

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
    private const val ML_MAX_DIMENSION = 1536
    private const val MIN_COVERAGE = 0.07f
    private const val MAX_COVERAGE = 0.82f
    private const val LOW_CONFIDENCE = 0.18f
    private const val HIGH_CONFIDENCE = 0.78f

    suspend fun compose(source: Bitmap, shadowStrength: Float): MatteResult {
        val working = scaledForMl(source)
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .enableMultipleSubjects(
                SubjectSegmenterOptions.SubjectResultOptions.Builder()
                    .enableConfidenceMask()
                    .build(),
            )
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        val alpha = try {
            val input = InputImage.fromBitmap(working, 0)
            val result = segmenter.process(input).await()
            coroutineContext.ensureActive()
            buildBestSubjectAlpha(result, working.width, working.height)
        } catch (t: Throwable) {
            null
        } finally {
            segmenter.close()
        }

        if (working !== source) working.recycle()

        if (alpha == null) {
            return MatteResult(
                bitmap = source,
                replaced = false,
                warning = "El módulo IA de recorte todavía no estuvo disponible o no pudo aislar el mueble con seguridad. Se conservó la foto sin destruir el fondo; con conexión a internet, vuelve a procesarla cuando Google Play Services termine de preparar el modelo.",
            )
        }

        val validation = validateAlpha(alpha.values, alpha.width, alpha.height)
        if (!validation.accepted) {
            return MatteResult(
                bitmap = source,
                replaced = false,
                warning = "Fidelity Lock rechazó un recorte poco confiable y conservó el fondo original.",
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
        canvas.drawColor(Color.rgb(250, 250, 248))

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

        if (maskFull !== maskSmall) maskFull.recycle()
        maskSmall.recycle()

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

    private fun buildBestSubjectAlpha(
        result: com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult,
        width: Int,
        height: Int,
    ): AlphaMask? {
        val subjects = result.subjects
        if (subjects.isNotEmpty()) {
            val centerX = width / 2f
            val centerY = height / 2f
            val imageArea = width.toFloat() * height.toFloat()

            val best = subjects.maxByOrNull { subject ->
                val area = subject.width.toFloat() * subject.height.toFloat()
                val cx = subject.startX + subject.width / 2f
                val cy = subject.startY + subject.height / 2f
                val dx = abs(cx - centerX) / width.toFloat()
                val dy = abs(cy - centerY) / height.toFloat()
                val centerScore = 1f - (dx + dy).coerceIn(0f, 1f)
                val areaScore = (area / imageArea).coerceIn(0f, 1f)
                areaScore * 0.78f + centerScore * 0.22f
            }

            if (best != null && best.width > 0 && best.height > 0) {
                val subjectBuffer = best.confidenceMask
                if (subjectBuffer != null) {
                    val full = FloatArray(width * height)
                    val copy = subjectBuffer.duplicate().apply { rewind() }
                    for (sy in 0 until best.height) {
                        for (sx in 0 until best.width) {
                            if (!copy.hasRemaining()) break
                            val confidence = copy.get().coerceIn(0f, 1f)
                            val x = best.startX + sx
                            val y = best.startY + sy
                            if (x in 0 until width && y in 0 until height) {
                                full[y * width + x] = softenConfidence(confidence)
                            }
                        }
                    }
                    feather(full, width, height)
                    return AlphaMask(full, width, height)
                }
            }
        }

        val foreground = result.foregroundConfidenceMask ?: return null
        val duplicate = foreground.duplicate().apply { rewind() }
        val full = FloatArray(width * height)
        var i = 0
        while (duplicate.hasRemaining() && i < full.size) {
            full[i++] = softenConfidence(duplicate.get().coerceIn(0f, 1f))
        }
        feather(full, width, height)
        return AlphaMask(full, width, height)
    }

    private fun softenConfidence(confidence: Float): Float {
        if (confidence <= LOW_CONFIDENCE) return 0f
        if (confidence >= HIGH_CONFIDENCE) return 1f
        val t = ((confidence - LOW_CONFIDENCE) / (HIGH_CONFIDENCE - LOW_CONFIDENCE)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun feather(alpha: FloatArray, width: Int, height: Int) {
        val original = alpha.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val center = original[index]
                if (center <= 0.02f || center >= 0.98f) continue
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

    private fun validateAlpha(alpha: FloatArray, width: Int, height: Int): MaskValidation {
        var count = 0
        var left = width
        var right = -1
        var top = height
        var bottom = -1

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (alpha[y * width + x] >= 0.48f) {
                    count++
                    left = min(left, x)
                    right = max(right, x)
                    top = min(top, y)
                    bottom = max(bottom, y)
                }
            }
        }

        if (count == 0 || right < left || bottom < top) {
            return MaskValidation(false, RectF())
        }

        val coverage = count.toFloat() / alpha.size.toFloat()
        val bounds = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        val touchesTooMuch = (left <= 1 && right >= width - 2) || (top <= 1 && bottom >= height - 2)
        val usefulSize = bounds.width() >= width * 0.12f && bounds.height() >= height * 0.18f
        return MaskValidation(
            accepted = coverage in MIN_COVERAGE..MAX_COVERAGE && !touchesTooMuch && usefulSize,
            bounds = bounds,
        )
    }

    private fun alphaToBitmap(alpha: FloatArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(alpha.size) { i ->
            val a = (alpha[i].coerceIn(0f, 1f) * 255f).roundToInt()
            Color.argb(a, 255, 255, 255)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
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
            (left + objectWidth * 0.05f).coerceAtLeast(0f),
            (bottom - outputHeight * 0.012f).coerceAtLeast(0f),
            (right - objectWidth * 0.05f).coerceAtMost(outputWidth.toFloat()),
            (bottom + outputHeight * 0.035f).coerceAtMost(outputHeight.toFloat()),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val alpha = (42f * strength.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 55)
            color = Color.argb(alpha, 20, 20, 20)
            maskFilter = BlurMaskFilter(
                max(outputWidth, outputHeight) * 0.009f,
                BlurMaskFilter.Blur.NORMAL,
            )
        }
        canvas.drawOval(shadowRect, paint)
    }
}
