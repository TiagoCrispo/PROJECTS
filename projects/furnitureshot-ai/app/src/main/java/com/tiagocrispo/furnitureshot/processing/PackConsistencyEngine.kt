package com.tiagocrispo.furnitureshot.processing

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PackConsistencyEngine {
    private data class Analysis(
        val path: String,
        val width: Int,
        val height: Int,
        val bounds: RectF,
        val bgR: Float,
        val bgG: Float,
        val bgB: Float,
        val objectLuma: Float,
    )

    private data class TargetStyle(
        val bgTop: Int,
        val bgBottom: Int,
        val widthCoverage: Float,
        val bottomNorm: Float,
        val centerX: Float,
        val objectLuma: Float,
    )

    fun normalize(resultPaths: List<String>): List<String> {
        if (resultPaths.size < 2) return resultPaths
        val analyses = resultPaths.mapNotNull { analyze(it) }
        if (analyses.size < 2) return resultPaths
        val style = buildTargetStyle(analyses)
        analyses.forEach { rewrite(it, style) }
        return resultPaths
    }

    private fun analyze(path: String): Analysis? {
        val bitmap = android.graphics.BitmapFactory.decodeFile(path) ?: return null
        return try {
            val mask = detectObjectMask(bitmap)
            val bounds = computeBounds(mask, bitmap.width, bitmap.height) ?: return null
            val bg = estimateBackground(bitmap)
            val objectLuma = estimateObjectLuma(bitmap, mask)
            Analysis(
                path = path,
                width = bitmap.width,
                height = bitmap.height,
                bounds = bounds,
                bgR = bg[0],
                bgG = bg[1],
                bgB = bg[2],
                objectLuma = objectLuma,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun buildTargetStyle(analyses: List<Analysis>): TargetStyle {
        fun median(values: List<Float>): Float {
            val sorted = values.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
        }
        val avgBgR = analyses.map { it.bgR }.average().toInt().coerceIn(0, 255)
        val avgBgG = analyses.map { it.bgG }.average().toInt().coerceIn(0, 255)
        val avgBgB = analyses.map { it.bgB }.average().toInt().coerceIn(0, 255)
        val widthCov = median(analyses.map { it.bounds.width() / it.width.toFloat() }).coerceIn(0.64f, 0.80f)
        val bottomNorm = median(analyses.map { it.bounds.bottom / it.height.toFloat() }).coerceIn(0.88f, 0.92f)
        val centerX = median(analyses.map { (it.bounds.left + it.bounds.right) / 2f / it.width.toFloat() }).coerceIn(0.47f, 0.53f)
        val objectLuma = median(analyses.map { it.objectLuma }).coerceIn(0.34f, 0.72f)
        val top = Color.rgb((avgBgR + 6).coerceIn(0,255), (avgBgG + 6).coerceIn(0,255), (avgBgB + 6).coerceIn(0,255))
        val bottom = Color.rgb((avgBgR - 6).coerceIn(0,255), (avgBgG - 6).coerceIn(0,255), (avgBgB - 8).coerceIn(0,255))
        return TargetStyle(top, bottom, widthCov, bottomNorm, centerX, objectLuma)
    }

    private fun rewrite(analysis: Analysis, style: TargetStyle) {
        val file = File(analysis.path)
        val bitmap = android.graphics.BitmapFactory.decodeFile(analysis.path) ?: return
        val output = try {
            val mask = detectObjectMask(bitmap)
            val bounds = computeBounds(mask, bitmap.width, bitmap.height) ?: return
            val objectBitmap = applyMaskAndTone(bitmap, mask, style.objectLuma)
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            drawUnifiedBackground(canvas, bitmap.width, bitmap.height, style)

            val scale = min(
                style.widthCoverage * bitmap.width.toFloat() / bounds.width().coerceAtLeast(1f),
                bitmap.height * 0.80f / bounds.height().coerceAtLeast(1f),
            )
            val placedWidth = bounds.width() * scale
            val placedHeight = bounds.height() * scale
            val left = bitmap.width * style.centerX - placedWidth / 2f
            val bottom = bitmap.height * style.bottomNorm
            val top = bottom - placedHeight
            val placedBounds = RectF(left, top, left + placedWidth, top + placedHeight)

            drawPackShadow(canvas, placedBounds, bitmap.width, bitmap.height)

            val matrix = Matrix().apply {
                postTranslate(-bounds.left, -bounds.top)
                postScale(scale, scale)
                postTranslate(placedBounds.left, placedBounds.top)
            }
            canvas.drawBitmap(objectBitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            if (!objectBitmap.isRecycled) objectBitmap.recycle()
            result
        } finally {
            bitmap.recycle()
        }

        val temp = File(file.parentFile, "result.pack.tmp.jpg")
        try {
            FileOutputStream(temp).use { out -> output.compress(Bitmap.CompressFormat.JPEG, 99, out) }
            temp.copyTo(file, overwrite = true)
        } finally {
            if (temp.exists()) temp.delete()
            if (!output.isRecycled) output.recycle()
        }
    }

    private fun applyMaskAndTone(source: Bitmap, mask: FloatArray, targetLuma: Float): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val currentLuma = estimateObjectLuma(source, mask).coerceAtLeast(0.001f)
        val gain = (targetLuma / currentLuma).coerceIn(0.94f, 1.10f)
        for (i in pixels.indices) {
            val a = (mask[i].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            val r = (Color.red(pixels[i]) * gain).roundToInt().coerceIn(0, 255)
            val g = (Color.green(pixels[i]) * gain).roundToInt().coerceIn(0, 255)
            val b = (Color.blue(pixels[i]) * gain).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(a, r, g, b)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun drawUnifiedBackground(canvas: Canvas, width: Int, height: Int, style: TargetStyle) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(), style.bgTop, style.bgBottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(36, 255, 255, 255)
        }
        canvas.drawRect(0f, height * 0.62f, width.toFloat(), height.toFloat(), floorPaint)
    }

    private fun drawPackShadow(canvas: Canvas, bounds: RectF, width: Int, height: Int) {
        val objectWidth = bounds.width().coerceAtLeast(width * 0.20f)
        val objectHeight = bounds.height().coerceAtLeast(height * 0.18f)
        val baseY = bounds.bottom
        val tall = objectHeight / objectWidth > 1.18f
        val broadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(if (tall) 60 else 50, 46, 39, 33)
            maskFilter = BlurMaskFilter(max(width, height) * (if (tall) 0.019f else 0.017f), BlurMaskFilter.Blur.NORMAL)
        }
        val mediumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(if (tall) 90 else 78, 34, 28, 24)
            maskFilter = BlurMaskFilter(max(width, height) * (if (tall) 0.009f else 0.008f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(
            RectF(bounds.left + objectWidth * (if (tall) 0.02f else 0.05f), baseY - height * 0.002f, bounds.right - objectWidth * 0.02f, baseY + height * (if (tall) 0.060f else 0.046f)),
            broadPaint,
        )
        canvas.drawOval(
            RectF(bounds.left + objectWidth * (if (tall) 0.10f else 0.16f), baseY, bounds.right - objectWidth * (if (tall) 0.06f else 0.10f), baseY + height * (if (tall) 0.028f else 0.022f)),
            mediumPaint,
        )
    }

    private fun estimateBackground(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val samples = ArrayList<Int>(32)
        val sx = max(1, width / 8)
        val sy = max(1, height / 8)
        fun sampleBlock(startX: Int, startY: Int, endX: Int, endY: Int) {
            val stepX = max(1, (endX - startX) / 5)
            val stepY = max(1, (endY - startY) / 5)
            for (y in startY until endY step stepY) {
                for (x in startX until endX step stepX) {
                    samples += pixels[y * width + x]
                }
            }
        }
        sampleBlock(0, 0, sx, sy)
        sampleBlock(width - sx, 0, width, sy)
        sampleBlock(0, height - sy, sx, height)
        sampleBlock(width - sx, height - sy, width, height)
        return floatArrayOf(
            samples.map { Color.red(it) }.average().toFloat(),
            samples.map { Color.green(it) }.average().toFloat(),
            samples.map { Color.blue(it) }.average().toFloat(),
        )
    }

    private fun detectObjectMask(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val bg = estimateBackground(bitmap)
        val alpha = FloatArray(width * height)
        val candidateBackground = BooleanArray(width * height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val sat = maxC - minC
            val lum = (r * 30 + g * 59 + b * 11) / 100
            val dist = abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2])
            val nearBg = dist <= 26f && sat <= 28
            val likelyBg = dist <= 40f && sat <= 34 && lum >= 180
            candidateBackground[i] = nearBg || likelyBg
            alpha[i] = when {
                nearBg -> 0f
                sat >= 22 -> 1f
                lum <= 165 -> 1f
                else -> 0.65f
            }
        }
        val trueBackground = floodBorderBackground(candidateBackground, width, height)
        for (i in alpha.indices) {
            if (trueBackground[i]) alpha[i] = 0f else if (alpha[i] < 0.78f) alpha[i] = 0.96f
        }
        return alpha
    }

    private fun floodBorderBackground(candidate: BooleanArray, width: Int, height: Int): BooleanArray {
        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var head = 0
        var tail = 0
        fun push(index: Int) {
            if (!visited[index] && candidate[index]) {
                visited[index] = true
                queue[tail++] = index
            }
        }
        for (x in 0 until width) {
            push(x)
            push((height - 1) * width + x)
        }
        for (y in 0 until height) {
            push(y * width)
            push(y * width + (width - 1))
        }
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % width
            val y = idx / width
            if (x > 0) push(idx - 1)
            if (x + 1 < width) push(idx + 1)
            if (y > 0) push(idx - width)
            if (y + 1 < height) push(idx + width)
        }
        return visited
    }

    private fun computeBounds(mask: FloatArray, width: Int, height: Int): RectF? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y * width + x] >= 0.5f) {
                    left = min(left, x)
                    right = max(right, x)
                    top = min(top, y)
                    bottom = max(bottom, y)
                }
            }
        }
        return if (right <= left || bottom <= top) null else RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun estimateObjectLuma(bitmap: Bitmap, mask: FloatArray): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var total = 0f
        var count = 0
        for (i in pixels.indices) {
            if (mask[i] >= 0.5f) {
                val c = pixels[i]
                total += (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
                count++
            }
        }
        return if (count == 0) 0.5f else total / count.toFloat()
    }
}
