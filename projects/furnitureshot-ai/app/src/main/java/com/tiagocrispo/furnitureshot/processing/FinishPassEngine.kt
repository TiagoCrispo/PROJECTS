package com.tiagocrispo.furnitureshot.processing

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.tiagocrispo.furnitureshot.data.ImageStore
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object FinishPassEngine {
    fun apply(resultPath: String): String {
        val source = ImageStore.loadForProcessing(resultPath, maxDimension = 3200)
        var output: Bitmap? = null
        try {
            val mask = buildForegroundMask(source)
            if (!retainMainComponent(mask, source.width, source.height)) return resultPath
            refineMask(mask, source.width, source.height)

            val bounds = findBounds(mask, source.width, source.height) ?: return resultPath
            output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            drawStudioBackground(canvas, source.width, source.height)
            drawSilhouetteShadow(canvas, mask, source.width, source.height, bounds)
            compositeForeground(source, output, mask)

            val result = File(resultPath)
            val temp = File(result.parentFile, "finish-pass.tmp.jpg")
            FileOutputStream(temp).use { stream ->
                check(output.compress(Bitmap.CompressFormat.JPEG, 99, stream)) {
                    "No se pudo guardar el acabado final."
                }
            }
            if (result.exists() && !result.delete()) {
                temp.delete()
                error("No se pudo reemplazar el resultado anterior.")
            }
            if (!temp.renameTo(result)) {
                temp.copyTo(result, overwrite = true)
                temp.delete()
            }
            return result.absolutePath
        } finally {
            if (!source.isRecycled) source.recycle()
            output?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun buildForegroundMask(source: Bitmap): BooleanArray {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val mask = BooleanArray(pixels.size)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val chroma = maxC - minC
            val luminance = (r * 30 + g * 59 + b * 11) / 100

            val nearStudioWhite = r >= 230 && g >= 230 && b >= 228 && chroma <= 22
            val likelyWoodOrMetal =
                luminance <= 178 ||
                    chroma >= 18 ||
                    (luminance <= 218 && chroma >= 11)

            mask[i] = !nearStudioWhite && likelyWoodOrMetal
        }
        return mask
    }

    private fun retainMainComponent(mask: BooleanArray, width: Int, height: Int): Boolean {
        val labels = IntArray(mask.size)
        val queue = IntArray(mask.size)
        var label = 0
        var bestLabel = 0
        var bestScore = Float.NEGATIVE_INFINITY
        val centerX = width * 0.5f
        val centerY = height * 0.48f

        for (start in mask.indices) {
            if (!mask[start] || labels[start] != 0) continue
            label++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var area = 0
            var sumX = 0L
            var sumY = 0L

            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                area++
                sumX += x.toLong()
                sumY += y.toLong()

                fun add(next: Int) {
                    if (mask[next] && labels[next] == 0) {
                        labels[next] = label
                        queue[tail++] = next
                    }
                }

                if (x > 0) add(index - 1)
                if (x + 1 < width) add(index + 1)
                if (y > 0) add(index - width)
                if (y + 1 < height) add(index + width)
            }

            if (area < 32) continue
            val cx = sumX.toFloat() / area
            val cy = sumY.toFloat() / area
            val dx = (cx - centerX) / width
            val dy = (cy - centerY) / height
            val centerPenalty = dx * dx + dy * dy
            val score = area.toFloat() - centerPenalty * mask.size * 0.18f
            if (score > bestScore) {
                bestScore = score
                bestLabel = label
            }
        }

        if (bestLabel == 0) return false
        for (i in mask.indices) mask[i] = labels[i] == bestLabel
        return true
    }

    private fun refineMask(mask: BooleanArray, width: Int, height: Int) {
        repeat(2) {
            val copy = mask.clone()
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val index = y * width + x
                    var neighbors = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            if (copy[(y + dy) * width + x + dx]) neighbors++
                        }
                    }
                    if (copy[index] && neighbors <= 1) mask[index] = false
                    if (!copy[index] && neighbors >= 7) mask[index] = true
                }
            }
        }

        val copy = mask.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (copy[index]) continue
                val horizontal = copy[index - 1] && copy[index + 1]
                val vertical = copy[index - width] && copy[index + width]
                if (horizontal || vertical) mask[index] = true
            }
        }
    }

    private fun findBounds(mask: BooleanArray, width: Int, height: Int): RectF? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!mask[y * width + x]) continue
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
        if (right < left || bottom < top) return null
        return RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun drawStudioBackground(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                height * 0.60f,
                0f,
                height.toFloat(),
                Color.rgb(255, 255, 255),
                Color.rgb(247, 247, 245),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun drawSilhouetteShadow(
        canvas: Canvas,
        mask: BooleanArray,
        width: Int,
        height: Int,
        bounds: RectF,
    ) {
        val objectWidth = bounds.width().coerceAtLeast(width * 0.15f)
        val globalBottom = bounds.bottom

        val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(64, 18, 18, 18)
            maskFilter = BlurMaskFilter(max(width, height) * 0.014f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(
            RectF(
                bounds.left + objectWidth * 0.03f,
                globalBottom - height * 0.003f,
                bounds.right - objectWidth * 0.03f,
                globalBottom + height * 0.062f,
            ),
            ambientPaint,
        )

        val bottomByColumn = IntArray(width) { -1 }
        for (x in max(0, bounds.left.toInt())..min(width - 1, bounds.right.toInt())) {
            for (y in min(height - 1, bounds.bottom.toInt()) downTo max(0, bounds.top.toInt())) {
                if (mask[y * width + x]) {
                    bottomByColumn[x] = y
                    break
                }
            }
        }

        val threshold = globalBottom - height * 0.035f
        val contactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(118, 12, 12, 12)
            maskFilter = BlurMaskFilter(max(width, height) * 0.0045f, BlurMaskFilter.Blur.NORMAL)
        }

        var runStart = -1
        var x = max(0, bounds.left.toInt())
        val endX = min(width - 1, bounds.right.toInt())
        while (x <= endX + 1) {
            val support = x <= endX && bottomByColumn[x] >= threshold
            if (support && runStart < 0) runStart = x
            if ((!support || x > endX) && runStart >= 0) {
                val runEnd = x - 1
                val runWidth = runEnd - runStart + 1
                if (runWidth >= max(3, (objectWidth * 0.015f).roundToInt())) {
                    val center = (runStart + runEnd) / 2f
                    val half = max(runWidth * 0.75f, objectWidth * 0.045f)
                    canvas.drawOval(
                        RectF(
                            center - half,
                            globalBottom - height * 0.004f,
                            center + half,
                            globalBottom + height * 0.018f,
                        ),
                        contactPaint,
                    )
                }
                runStart = -1
            }
            x++
        }
    }

    private fun compositeForeground(source: Bitmap, output: Bitmap, mask: BooleanArray) {
        val width = source.width
        val height = source.height
        val src = IntArray(width * height)
        val dst = IntArray(width * height)
        source.getPixels(src, 0, width, 0, 0, width, height)
        output.getPixels(dst, 0, width, 0, 0, width, height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (!mask[index]) continue
                var backgroundNeighbors = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (!mask[(y + dy) * width + x + dx]) backgroundNeighbors++
                    }
                }
                val alpha = when {
                    backgroundNeighbors >= 5 -> 0.82f
                    backgroundNeighbors >= 2 -> 0.93f
                    else -> 1f
                }
                if (alpha >= 0.999f) {
                    dst[index] = src[index]
                } else {
                    val s = src[index]
                    val d = dst[index]
                    dst[index] = Color.rgb(
                        (Color.red(s) * alpha + Color.red(d) * (1f - alpha)).roundToInt().coerceIn(0, 255),
                        (Color.green(s) * alpha + Color.green(d) * (1f - alpha)).roundToInt().coerceIn(0, 255),
                        (Color.blue(s) * alpha + Color.blue(d) * (1f - alpha)).roundToInt().coerceIn(0, 255),
                    )
                }
            }
        }
        output.setPixels(dst, 0, width, 0, 0, width, height)
    }
}
