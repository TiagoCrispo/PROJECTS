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
            val nearNeutral = sat <= 24
            val studioSurface = nearNeutral || (r >= 236 && g >= 236 && b >= 234)
            val warmFurniture = sat >= 24 && r >= g && g >= b - 8
            val darkColoredFurniture = lum <= 120 && sat >= 18
            alpha[i] = when {
                studioSurface -> 0f
                warmFurniture || darkColoredFurniture -> 1f
                sat >= 18 -> 0.78f
                else -> 0f
            }
        }
        return alpha
    }

    private fun refineMask(mask: FloatArray, width: Int, height: Int): FloatArray {
        val out = mask.clone()
        repeat(3) {
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
                        v < 0.30f && strong >= 7 -> 0.98f
                        v > 0.70f && weak >= 6 -> 0.02f
                        v in 0.30f..0.70f && strong >= 5 -> 0.94f
                        v in 0.30f..0.70f && weak >= 5 -> 0.06f
                        else -> v
                    }
                }
            }
        }
        return out
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
        val targetWidth = width * 0.86f
        val targetHeight = height * 0.74f
        val scale = min(targetWidth / contentWidth, targetHeight / contentHeight)
        val desiredBottom = height * 0.89f
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
                Color.rgb(242, 238, 231),
                Color.rgb(232, 227, 220),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val floorGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(22, 255, 255, 255)
        }
        canvas.drawRect(0f, height * 0.60f, width.toFloat(), height.toFloat(), floorGlow)
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

        val broadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(48, 38, 31, 25)
            maskFilter = BlurMaskFilter(max(width, height) * 0.016f, BlurMaskFilter.Blur.NORMAL)
        }
        val mediumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(64, 32, 27, 23)
            maskFilter = BlurMaskFilter(max(width, height) * 0.009f, BlurMaskFilter.Blur.NORMAL)
        }
        val contactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(104, 24, 21, 18)
            maskFilter = BlurMaskFilter(max(width, height) * 0.0038f, BlurMaskFilter.Blur.NORMAL)
        }

        val shadowShiftX = width * 0.018f
        canvas.drawOval(
            RectF(
                placedBounds.left + objectWidth * 0.07f + shadowShiftX,
                baseY - height * 0.006f,
                placedBounds.right - objectWidth * 0.02f + shadowShiftX,
                baseY + height * 0.052f,
            ),
            broadPaint,
        )
        canvas.drawOval(
            RectF(
                placedBounds.left + objectWidth * 0.16f + shadowShiftX * 0.5f,
                baseY - height * 0.004f,
                placedBounds.right - objectWidth * 0.10f + shadowShiftX * 0.5f,
                baseY + height * 0.028f,
            ),
            mediumPaint,
        )

        val sourceWidth = width
        val sourceHeight = height
        val startX = max(0, sourceBounds.left.toInt())
        val endX = min(sourceWidth - 1, sourceBounds.right.toInt())
        val nearBottom = sourceBounds.bottom - sourceHeight * 0.055f
        val bottomByColumn = IntArray(sourceWidth) { -1 }
        for (x in startX..endX) {
            var y = min(sourceHeight - 1, sourceBounds.bottom.toInt())
            val topLimit = max(0, sourceBounds.top.toInt())
            while (y >= topLimit) {
                if (mask[y * sourceWidth + x] >= 0.55f) {
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
                    val half = max(runWidth * scale * 0.75f, objectWidth * 0.024f)
                    canvas.drawOval(
                        RectF(
                            outCenter - half,
                            baseY - height * 0.0025f,
                            outCenter + half,
                            baseY + height * 0.012f,
                        ),
                        contactPaint,
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
                val lifted = if (v < 0.40f) v + (0.40f - v) * 0.10f else v
                val mid = ((lifted - 0.5f) * 1.09f + 0.5f).coerceIn(0f, 1f)
                return if (mid > 0.84f) 0.84f + (mid - 0.84f) * 0.70f else mid
            }
            val nr = (curve(r) * 255f).roundToInt().coerceIn(0, 255)
            val ng = (curve(g) * 255f).roundToInt().coerceIn(0, 255)
            val nb = (curve(b) * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb(255, nr, ng, nb)
        }
        val toned = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        return unsharpMask(toned, amount = 0.20f)
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
