package com.tiagocrispo.furnitureshot.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.tiagocrispo.furnitureshot.data.ImageStore
import com.tiagocrispo.furnitureshot.model.ProcessResult
import com.tiagocrispo.furnitureshot.model.ProcessSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.math.sqrt

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

        val matte = if (settings.whiteBackground) {
            ConservativeBackgroundIsolator.applyWhiteStudioBackground(enhanced)
        } else {
            MatteResult(enhanced, replaced = false, warning = null)
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
            warning = listOfNotNull(settings.fidelityWarning, matte.warning).joinToString(" ").ifBlank { null },
        )
    }

    private fun applySafeToneEnhancement(source: Bitmap, settings: ProcessSettings): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val contrast = settings.contrast.coerceIn(0.9f, 1.12f)
        val brightnessOffset = settings.brightness.coerceIn(-0.08f, 0.08f) * 255f
        val translate = (1f - contrast) * 128f + brightnessOffset
        val warmthOffset = settings.warmth.coerceIn(-0.04f, 0.04f) * 255f

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

private object ConservativeBackgroundIsolator {
    private const val MASK_MAX_DIMENSION = 900
    private const val MIN_BACKGROUND_COVERAGE = 0.08
    private const val MAX_BACKGROUND_COVERAGE = 0.92

    suspend fun applyWhiteStudioBackground(source: Bitmap): MatteResult {
        val scale = (MASK_MAX_DIMENSION.toFloat() / maxOf(source.width, source.height)).coerceAtMost(1f)
        val maskWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val maskHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val small = if (maskWidth == source.width && maskHeight == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, maskWidth, maskHeight, true)
        }

        val pixels = IntArray(maskWidth * maskHeight)
        small.getPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
        val backgroundColor = estimateBackgroundColor(pixels, maskWidth, maskHeight)
        val distanceThreshold = chooseThreshold(pixels, maskWidth, maskHeight, backgroundColor)

        val background = ByteArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0
        var count = 0

        fun tryAdd(index: Int) {
            if (background[index].toInt() != 0) return
            if (colorDistance(pixels[index], backgroundColor) > distanceThreshold) return
            background[index] = 1
            queue[tail++] = index
            count++
        }

        for (x in 0 until maskWidth) {
            tryAdd(x)
            tryAdd((maskHeight - 1) * maskWidth + x)
        }
        for (y in 0 until maskHeight) {
            tryAdd(y * maskWidth)
            tryAdd(y * maskWidth + maskWidth - 1)
        }

        while (head < tail) {
            if ((head and 0x1FFF) == 0) coroutineContext.ensureActive()
            val index = queue[head++]
            val x = index % maskWidth
            val y = index / maskWidth
            if (x > 0) tryAdd(index - 1)
            if (x + 1 < maskWidth) tryAdd(index + 1)
            if (y > 0) tryAdd(index - maskWidth)
            if (y + 1 < maskHeight) tryAdd(index + maskWidth)
        }

        if (small !== source) small.recycle()

        val coverage = count.toDouble() / pixels.size.toDouble()
        if (coverage < MIN_BACKGROUND_COVERAGE || coverage > MAX_BACKGROUND_COVERAGE) {
            return MatteResult(
                bitmap = source,
                replaced = false,
                warning = "El aislamiento de fondo no fue suficientemente confiable; Fidelity Lock conservó el fondo original.",
            )
        }

        val maskPixels = IntArray(background.size) { index ->
            if (background[index].toInt() == 1) Color.TRANSPARENT else Color.WHITE
        }
        val maskSmall = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888).also {
            it.setPixels(maskPixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
        }
        val maskFull = if (maskWidth == source.width && maskHeight == source.height) {
            maskSmall
        } else {
            Bitmap.createScaledBitmap(maskSmall, source.width, source.height, true)
        }

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
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

        return MatteResult(
            bitmap = output,
            replaced = true,
            warning = null,
        )
    }

    private fun estimateBackgroundColor(pixels: IntArray, width: Int, height: Int): Int {
        val patchW = (width * 0.07f).toInt().coerceIn(4, 48)
        val patchH = (height * 0.07f).toInt().coerceIn(4, 48)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L

        fun sample(xStart: Int, yStart: Int) {
            for (y in yStart until (yStart + patchH).coerceAtMost(height)) {
                for (x in xStart until (xStart + patchW).coerceAtMost(width)) {
                    val color = pixels[y * width + x]
                    red += Color.red(color)
                    green += Color.green(color)
                    blue += Color.blue(color)
                    count++
                }
            }
        }

        sample(0, 0)
        sample((width - patchW).coerceAtLeast(0), 0)
        sample(0, (height - patchH).coerceAtLeast(0))
        sample((width - patchW).coerceAtLeast(0), (height - patchH).coerceAtLeast(0))

        if (count == 0L) return Color.WHITE
        return Color.rgb(
            (red / count).toInt(),
            (green / count).toInt(),
            (blue / count).toInt(),
        )
    }

    private fun chooseThreshold(pixels: IntArray, width: Int, height: Int, backgroundColor: Int): Double {
        val samples = ArrayList<Double>(128)
        val stepX = (width / 24).coerceAtLeast(1)
        val stepY = (height / 24).coerceAtLeast(1)
        for (x in 0 until width step stepX) {
            samples += colorDistance(pixels[x], backgroundColor)
            samples += colorDistance(pixels[(height - 1) * width + x], backgroundColor)
        }
        for (y in 0 until height step stepY) {
            samples += colorDistance(pixels[y * width], backgroundColor)
            samples += colorDistance(pixels[y * width + width - 1], backgroundColor)
        }
        samples.sort()
        val median = if (samples.isEmpty()) 0.0 else samples[samples.size / 2]
        return (median * 2.2 + 22.0).coerceIn(32.0, 72.0)
    }

    private fun colorDistance(color: Int, reference: Int): Double {
        val dr = (Color.red(color) - Color.red(reference)).toDouble()
        val dg = (Color.green(color) - Color.green(reference)).toDouble()
        val db = (Color.blue(color) - Color.blue(reference)).toDouble()
        return sqrt(dr.pow(2) + dg.pow(2) + db.pow(2))
    }
}
