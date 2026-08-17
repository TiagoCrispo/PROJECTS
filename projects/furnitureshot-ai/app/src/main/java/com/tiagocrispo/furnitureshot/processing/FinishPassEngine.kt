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
            val bgLike = r >= 236 && g >= 236 && b >= 234 && sat <= 22
            alpha[i] = when {
                bgLike -> 0f
                sat >= 24 || lum <= 230 -> 1f
                else -> 0.75f
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

        val objectBitmap = applyPhotographerLook(source)
        val objectRect = RectF(bounds)
        val contentWidth = objectRect.width().coerceAtLeast(1f)
        val contentHeight = objectRect.height().coerceAtLeast(1f)
        val targetWidth = width * 0.86f
        val targetHeight = height * 0.72f
        val scale = min(targetWidth / contentWidth, targetHeight / contentHeight)
        val desiredBottom = height * 0.84f
        val placedWidth = contentWidth * scale
        val placedHeight = contentHeight * scale
        val left = (width - placedWidth) / 2f
        val top = desiredBottom - placedHeight
        val placedBounds = RectF(left, top, left + placedWidth, top + placedHeight)

        drawGroundedShadow(canvas, placedBounds, width, height)

        val matrix = Matrix().apply {
            postTranslate(-bounds.left, -bounds.top)
            postScale(scale, scale)
            postTranslate(placedBounds.left, placedBounds.top)
        }
        canvas.drawBitmap(objectBitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (!objectBitmap.isRecycled) objectBitmap.recycle()
        return output
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

    private fun drawGroundedShadow(canvas: Canvas, bounds: RectF, width: Int, height: Int) {
        val objectWidth = bounds.width().coerceAtLeast(width * 0.20f)
        val baseY = bounds.bottom

        val broadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(56, 40, 32, 24)
            maskFilter = BlurMaskFilter(max(width, height) * 0.018f, BlurMaskFilter.Blur.NORMAL)
        }
        val mediumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(86, 32, 26, 22)
            maskFilter = BlurMaskFilter(max(width, height) * 0.010f, BlurMaskFilter.Blur.NORMAL)
        }
        val contactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(116, 26, 22, 18)
            maskFilter = BlurMaskFilter(max(width, height) * 0.0048f, BlurMaskFilter.Blur.NORMAL)
        }

        canvas.drawOval(
            RectF(
                bounds.left + objectWidth * 0.04f,
                baseY - height * 0.004f,
                bounds.right - objectWidth * 0.04f,
                baseY + height * 0.060f,
            ),
            broadPaint,
        )
        canvas.drawOval(
            RectF(
                bounds.left + objectWidth * 0.14f,
                baseY - height * 0.002f,
                bounds.right - objectWidth * 0.14f,
                baseY + height * 0.034f,
            ),
            mediumPaint,
        )

        val supportXs = listOf(
            bounds.left + objectWidth * 0.10f,
            bounds.left + objectWidth * 0.36f,
            bounds.left + objectWidth * 0.66f,
            bounds.right - objectWidth * 0.10f,
        )
        val contactHeight = height * 0.014f
        val contactWidth = objectWidth * 0.16f
        supportXs.forEach { x ->
            canvas.drawOval(
                RectF(
                    x - contactWidth / 2f,
                    baseY - contactHeight * 0.10f,
                    x + contactWidth / 2f,
                    baseY + contactHeight,
                ),
                contactPaint,
            )
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
