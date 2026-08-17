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

object FinishPassEngine {
    fun apply(resultPath: String): String {
        val sourceFile = File(resultPath)
        if (!sourceFile.exists()) return resultPath

        val bitmap = android.graphics.BitmapFactory.decodeFile(resultPath) ?: return resultPath
        val finished = try {
            val mask = detectObjectMask(bitmap)
            val refinedMask = refineMask(mask, bitmap.width, bitmap.height)
            val bounds = computeBounds(refinedMask, bitmap.width, bitmap.height) ?: return resultPath
            composeCatalogShot(bitmap, refinedMask, bounds)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        val temp = File(sourceFile.parentFile, "result.finish.tmp.jpg")
        try {
            FileOutputStream(temp).use { out ->
                check(finished.compress(Bitmap.CompressFormat.JPEG, 99, out))
            }
            temp.copyTo(sourceFile, overwrite = true)
            temp.delete()
        } finally {
            if (!finished.isRecycled) finished.recycle()
            if (temp.exists()) temp.delete()
        }
        return sourceFile.absolutePath
    }

    private fun detectObjectMask(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val cornerSamples = ArrayList<Int>(32)
        fun sampleBlock(startX: Int, startY: Int, endX: Int, endY: Int) {
            val stepX = max(1, (endX - startX) / 6)
            val stepY = max(1, (endY - startY) / 6)
            for (y in startY until endY step stepY) {
                for (x in startX until endX step stepX) {
                    cornerSamples += pixels[y * width + x]
                }
            }
        }
        val sx = max(1, width / 8)
        val sy = max(1, height / 8)
        sampleBlock(0, 0, sx, sy)
        sampleBlock(width - sx, 0, width, sy)
        sampleBlock(0, height - sy, sx, height)
        sampleBlock(width - sx, height - sy, width, height)

        val bgR = cornerSamples.map { Color.red(it) }.average().toFloat()
        val bgG = cornerSamples.map { Color.green(it) }.average().toFloat()
        val bgB = cornerSamples.map { Color.blue(it) }.average().toFloat()

        val candidateBackground = BooleanArray(width * height)
        val alpha = FloatArray(width * height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val sat = maxC - minC
            val lum = (r * 30 + g * 59 + b * 11) / 100
            val dist = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)

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
            if (trueBackground[i]) {
                alpha[i] = 0f
            } else if (alpha[i] < 0.78f) {
                alpha[i] = 0.96f
            }
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

    private fun refineMask(mask: FloatArray, width: Int, height: Int): FloatArray {
        val out = mask.clone()
        repeat(2) {
            val copy = out.clone()
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val i = y * width + x
                    val v = copy[i]
                    var strong = 0
                    var weak = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val n = copy[(y + dy) * width + (x + dx)]
                            if (n >= 0.90f) strong++
                            if (n <= 0.10f) weak++
                        }
                    }
                    out[i] = when {
                        v < 0.25f && strong >= 7 -> 0.98f
                        v > 0.75f && weak >= 7 -> 0.02f
                        v in 0.25f..0.75f && strong >= 5 -> 0.95f
                        v in 0.25f..0.75f && weak >= 5 -> 0.05f
                        else -> v
                    }
                }
            }
        }
        reinforceObjectBridges(out, width, height)
        suppressBackgroundLeaks(out, width, height)
        return out
    }

    private fun reinforceObjectBridges(mask: FloatArray, width: Int, height: Int) {
        val copy = mask.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                if (copy[i] >= 0.85f) continue
                val l = copy[y * width + (x - 1)]
                val r = copy[y * width + (x + 1)]
                val t = copy[(y - 1) * width + x]
                val b = copy[(y + 1) * width + x]
                if ((l >= 0.92f && r >= 0.92f) || (t >= 0.92f && b >= 0.92f)) {
                    mask[i] = max(mask[i], 0.92f)
                }
            }
        }
    }

    private fun suppressBackgroundLeaks(mask: FloatArray, width: Int, height: Int) {
        val copy = mask.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                if (copy[i] <= 0.20f) continue
                var bgCount = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (copy[(y + dy) * width + (x + dx)] <= 0.12f) bgCount++
                    }
                }
                if (bgCount >= 6) mask[i] = min(mask[i], 0.18f)
            }
        }
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

    private fun composeCatalogShot(source: Bitmap, mask: FloatArray, bounds: RectF): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        drawCatalogBackground(canvas, width, height)

        val toned = applyPhotographerLook(source)
        val objectBitmap = applyMask(toned, mask)
        if (toned !== objectBitmap && !toned.isRecycled) toned.recycle()

        val contentWidth = bounds.width().coerceAtLeast(1f)
        val contentHeight = bounds.height().coerceAtLeast(1f)
        val targetWidth = width * 0.885f
        val targetHeight = height * 0.785f
        val scale = min(targetWidth / contentWidth, targetHeight / contentHeight)
        val desiredBottom = height * 0.912f
        val placedWidth = contentWidth * scale
        val placedHeight = contentHeight * scale
        val left = (width - placedWidth) / 2f
        val top = desiredBottom - placedHeight
        val placedBounds = RectF(left, top, left + placedWidth, top + placedHeight)

        drawGroundedShadow(canvas, mask, bounds, scale, placedBounds, width, height)

        val matrix = Matrix().apply {
            postTranslate(-bounds.left, -bounds.top)
            postScale(scale, scale)
            postTranslate(placedBounds.left, placedBounds.top)
        }
        canvas.drawBitmap(objectBitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (!objectBitmap.isRecycled) objectBitmap.recycle()
        return output
    }

    private fun applyMask(source: Bitmap, mask: FloatArray): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (mask[i].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun drawCatalogBackground(canvas: Canvas, width: Int, height: Int) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                Color.rgb(236, 232, 226),
                Color.rgb(226, 220, 212),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                height * 0.60f,
                0f,
                height.toFloat(),
                Color.argb(0, 255, 255, 255),
                Color.argb(50, 255, 255, 255),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, height * 0.60f, width.toFloat(), height.toFloat(), floorPaint)
    }

    private fun drawGroundedShadow(
        canvas: Canvas,
        mask: FloatArray,
        sourceBounds: RectF,
        scale: Float,
        placedBounds: RectF,
        width: Int,
        height: Int,
    ) {
        val objectWidth = placedBounds.width().coerceAtLeast(width * 0.20f)
        val baseY = placedBounds.bottom
        val shiftX = width * 0.020f

        val broadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(54, 48, 40, 34)
            maskFilter = BlurMaskFilter(max(width, height) * 0.018f, BlurMaskFilter.Blur.NORMAL)
        }
        val mediumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(76, 40, 33, 28)
            maskFilter = BlurMaskFilter(max(width, height) * 0.010f, BlurMaskFilter.Blur.NORMAL)
        }
        val contactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 24, 20, 18)
            maskFilter = BlurMaskFilter(max(width, height) * 0.0036f, BlurMaskFilter.Blur.NORMAL)
        }

        canvas.drawOval(
            RectF(
                placedBounds.left + objectWidth * 0.03f + shiftX,
                baseY - height * 0.002f,
                placedBounds.right - objectWidth * 0.01f + shiftX,
                baseY + height * 0.050f,
            ),
            broadPaint,
        )
        canvas.drawOval(
            RectF(
                placedBounds.left + objectWidth * 0.12f + shiftX * 0.6f,
                baseY - height * 0.001f,
                placedBounds.right - objectWidth * 0.08f + shiftX * 0.6f,
                baseY + height * 0.026f,
            ),
            mediumPaint,
        )

        val srcWidth = width
        val srcHeight = height
        val startX = max(0, sourceBounds.left.toInt())
        val endX = min(srcWidth - 1, sourceBounds.right.toInt())
        val nearBottom = sourceBounds.bottom - srcHeight * 0.060f
        val bottomByColumn = IntArray(srcWidth) { -1 }
        for (x in startX..endX) {
            var y = min(srcHeight - 1, sourceBounds.bottom.toInt())
            val topLimit = max(0, sourceBounds.top.toInt())
            while (y >= topLimit) {
                if (mask[y * srcWidth + x] >= 0.55f) {
                    bottomByColumn[x] = y
                    break
                }
                y--
            }
        }

        var runStart = -1
        var x = startX
        while (x <= endX + 1) {
            val isSupport = x <= endX && bottomByColumn[x] >= nearBottom
            if (isSupport && runStart < 0) runStart = x
            if ((!isSupport || x > endX) && runStart >= 0) {
                val runEnd = x - 1
                val runWidth = runEnd - runStart + 1
                if (runWidth >= max(2, (sourceBounds.width() * 0.008f).roundToInt())) {
                    val srcCenter = (runStart + runEnd) / 2f
                    val outCenter = placedBounds.left + (srcCenter - sourceBounds.left) * scale
                    val half = max(runWidth * scale * 0.95f, objectWidth * 0.028f)
                    canvas.drawOval(
                        RectF(
                            outCenter - half,
                            baseY - height * 0.0015f,
                            outCenter + half,
                            baseY + height * 0.010f,
                        ),
                        contactPaint,
                    )
                    canvas.drawOval(
                        RectF(
                            outCenter - half * 0.55f + shiftX * 0.35f,
                            baseY + height * 0.001f,
                            outCenter + half * 1.05f + shiftX * 0.35f,
                            baseY + height * 0.017f,
                        ),
                        mediumPaint,
                    )
                }
                runStart = -1
            }
            x++
        }
    }

    private fun applyPhotographerLook(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            fun curve(v: Float): Float {
                val lifted = if (v < 0.36f) v + (0.36f - v) * 0.08f else v
                val mid = ((lifted - 0.5f) * 1.07f + 0.5f).coerceIn(0f, 1f)
                return if (mid > 0.86f) 0.86f + (mid - 0.86f) * 0.62f else mid
            }
            val nr = (curve(r) * 255f).roundToInt().coerceIn(0, 255)
            val ng = (curve(g) * 255f).roundToInt().coerceIn(0, 255)
            val nb = (curve(b) * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(255, nr, ng, nb)
        }
        val toned = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        return unsharpMask(toned, amount = 0.19f)
    }

    private fun unsharpMask(source: Bitmap, amount: Float): Bitmap {
        val width = source.width
        val height = source.height
        val src = IntArray(width * height)
        source.getPixels(src, 0, width, 0, 0, width, height)
        val blur = src.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var rs = 0
                var gs = 0
                var bs = 0
                var count = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val c = src[(y + dy) * width + (x + dx)]
                        rs += Color.red(c)
                        gs += Color.green(c)
                        bs += Color.blue(c)
                        count++
                    }
                }
                blur[y * width + x] = Color.argb(255, rs / count, gs / count, bs / count)
            }
        }
        val out = IntArray(src.size)
        for (i in src.indices) {
            val sr = Color.red(src[i])
            val sg = Color.green(src[i])
            val sb = Color.blue(src[i])
            val br = Color.red(blur[i])
            val bg = Color.green(blur[i])
            val bb = Color.blue(blur[i])
            out[i] = Color.argb(
                255,
                (sr + (sr - br) * amount).roundToInt().coerceIn(0, 255),
                (sg + (sg - bg) * amount).roundToInt().coerceIn(0, 255),
                (sb + (sb - bb) * amount).roundToInt().coerceIn(0, 255),
            )
        }
        if (!source.isRecycled) source.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(out, 0, width, 0, 0, width, height)
        }
    }
}
