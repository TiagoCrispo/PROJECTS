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
            refineInteriorCutouts(bitmap, refinedMask)
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

        // Estimate the plain studio background from the corners of the already-processed image.
        val (bgR, bgG, bgB) = estimateCornerBackground(bitmap)

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

        // Flood-fill only the true outer background; enclosed light holes are kept with the object,
        // which helps shelves, slats and open furniture interiors.
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
        tightenBottomSupportMask(out, width, height)
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

    private fun tightenBottomSupportMask(mask: FloatArray, width: Int, height: Int) {
        val copy = mask.clone()
        val bottomBand = max(8, height / 9)
        for (x in 1 until width - 1) {
            var bottom = -1
            var top = -1
            val startY = height - 1
            val limitY = max(0, height - bottomBand)
            for (y in startY downTo limitY) {
                if (copy[y * width + x] >= 0.56f) {
                    if (bottom < 0) bottom = y
                    top = y
                } else if (bottom >= 0) {
                    break
                }
            }
            if (bottom < 0 || top < 0) continue
            for (y in top..bottom) {
                val i = y * width + x
                mask[i] = max(mask[i], 0.94f)
            }
            val under = bottom + 1
            if (under in 0 until height) {
                val ui = under * width + x
                val left = under * width + (x - 1)
                val right = under * width + (x + 1)
                if (copy[ui] <= 0.10f && (copy[left] >= 0.65f || copy[right] >= 0.65f)) {
                    mask[ui] = 0.16f
                }
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
        val aspectRatio = contentHeight / contentWidth
        val tall = aspectRatio > 1.18f
        val veryWide = aspectRatio < 0.78f
         // Perspective guard: keep tall objects slightly narrower and very wide objects a bit lower
        // so the final catalog frame feels less distorted across varied capture angles.
        val targetWidth = width * when {
            tall -> 0.69f
            veryWide -> 0.88f
            else -> 0.865f
        }
        val targetHeight = height * when {
            tall -> 0.845f
            veryWide -> 0.77f
            else -> 0.792f
        }
        val scale = min(targetWidth / contentWidth, targetHeight / contentHeight)
        val desiredBottom = height * when {
            tall -> 0.944f
            veryWide -> 0.932f
            else -> 0.936f
        }
        val placedWidth = contentWidth * scale
        val placedHeight = contentHeight * scale
        val left = (width - placedWidth) / 2f
        val top = desiredBottom - placedHeight
        val placedBounds = RectF(left, top, left + placedWidth, top + placedHeight)

        drawGroundPlane(canvas, placedBounds, width, height)
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
