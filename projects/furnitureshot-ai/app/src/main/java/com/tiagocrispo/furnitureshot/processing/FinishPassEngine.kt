package com.tiagocrispo.furnitureshot.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        val source = BitmapFactory.decodeFile(resultPath) ?: return resultPath
        val finished = try {
            val mask = createMask(source)
            refineMask(source, mask)
            val bounds = boundsOf(mask, source.width, source.height) ?: return resultPath
            compose(source, mask, bounds)
        } finally {
            if (!source.isRecycled) source.recycle()
        }

        val temp = File(sourceFile.parentFile, "result.finish.tmp.jpg")
        try {
            FileOutputStream(temp).use { out ->
                check(finished.compress(Bitmap.CompressFormat.JPEG, 99, out))
            }
            temp.copyTo(sourceFile, overwrite = true)
        } finally {
            if (temp.exists()) temp.delete()
            if (!finished.isRecycled) finished.recycle()
        }
        return sourceFile.absolutePath
    }

    private fun createMask(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val bg = cornerBackground(pixels, w, h)
        val candidate = BooleanArray(pixels.size)
        val mask = FloatArray(pixels.size)

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
            val nearBg = dist <= 28f && sat <= 30
            val possibleBg = dist <= 44f && sat <= 38 && lum >= 172
            candidate[i] = nearBg || possibleBg
            mask[i] = when {
                nearBg -> 0f
                sat >= 20 || lum <= 168 -> 1f
                else -> 0.70f
            }
        }

        val outer = floodFromBorder(candidate, w, h)
        for (i in mask.indices) {
            if (outer[i]) mask[i] = 0f
            else if (mask[i] < 0.78f) mask[i] = 0.94f
        }
        return mask
    }

    private fun cornerBackground(pixels: IntArray, w: Int, h: Int): FloatArray {
        var rs = 0L
        var gs = 0L
        var bs = 0L
        var count = 0L
        val sx = max(2, w / 10)
        val sy = max(2, h / 10)
        val stepX = max(1, sx / 5)
        val stepY = max(1, sy / 5)

        fun sample(x0: Int, y0: Int, x1: Int, y1: Int) {
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val c = pixels[y * w + x]
                    rs += Color.red(c)
                    gs += Color.green(c)
                    bs += Color.blue(c)
                    count++
                    x += stepX
                }
                y += stepY
            }
        }

        sample(0, 0, sx, sy)
        sample(w - sx, 0, w, sy)
        sample(0, h - sy, sx, h)
        sample(w - sx, h - sy, w, h)
        if (count == 0L) return floatArrayOf(240f, 238f, 235f)
        return floatArrayOf(rs.toFloat() / count, gs.toFloat() / count, bs.toFloat() / count)
    }

    private fun floodFromBorder(candidate: BooleanArray, w: Int, h: Int): BooleanArray {
        val visited = BooleanArray(candidate.size)
        val queue = IntArray(candidate.size)
        var head = 0
        var tail = 0

        fun push(i: Int) {
            if (i in candidate.indices && candidate[i] && !visited[i]) {
                visited[i] = true
                queue[tail++] = i
            }
        }

        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x + 1 < w) push(i + 1)
            if (y > 0) push(i - w)
            if (y + 1 < h) push(i + w)
        }
        return visited
    }

    private fun refineMask(bitmap: Bitmap, mask: FloatArray) {
        val w = bitmap.width
        val h = bitmap.height
        repeat(2) {
            val copy = mask.clone()
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    var objectNeighbors = 0
                    var bgNeighbors = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val v = copy[(y + dy) * w + x + dx]
                        if (v >= 0.88f) objectNeighbors++
                        if (v <= 0.12f) bgNeighbors++
                    }
                    mask[i] = when {
                        copy[i] < 0.30f && objectNeighbors >= 7 -> 0.92f
                        copy[i] > 0.70f && bgNeighbors >= 7 -> 0.06f
                        else -> copy[i]
                    }
                }
            }
        }
        cleanInteriorLeaks(bitmap, mask)
        cleanBottomEdge(mask, w, h)
    }

    private fun cleanInteriorLeaks(bitmap: Bitmap, mask: FloatArray) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val bg = cornerBackground(pixels, w, h)
        val copy = mask.clone()
        fun strong(x: Int, y: Int): Boolean = x in 0 until w && y in 0 until h && copy[y * w + x] >= 0.82f

        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val i = y * w + x
                if (copy[i] <= 0.15f) continue
                val c = pixels[i]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val sat = max(r, max(g, b)) - min(r, min(g, b))
                val dist = abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2])
                if (dist > 48f || sat > 45) continue
                val horizontalGap = (strong(x - 1, y) || strong(x - 2, y)) && (strong(x + 1, y) || strong(x + 2, y))
                val verticalGap = (strong(x, y - 1) || strong(x, y - 2)) && (strong(x, y + 1) || strong(x, y + 2))
                var around = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if ((dx != 0 || dy != 0) && strong(x + dx, y + dy)) around++
                }
                if ((horizontalGap || verticalGap) && around >= 3) mask[i] = 0.04f
            }
        }
    }

    private fun cleanBottomEdge(mask: FloatArray, w: Int, h: Int) {
        val copy = mask.clone()
        val bandTop = max(0, h - h / 7)
        for (x in 1 until w - 1) {
            var bottom = -1
            for (y in h - 1 downTo bandTop) {
                if (copy[y * w + x] >= 0.58f) {
                    bottom = y
                    break
                }
            }
            if (bottom < 0) continue
            val under = bottom + 1
            if (under < h) {
                val i = under * w + x
                if (copy[i] in 0.10f..0.45f) mask[i] = 0.04f
            }
        }
    }

    private fun boundsOf(mask: FloatArray, w: Int, h: Int): RectF? {
        var l = w
        var t = h
        var r = -1
        var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (mask[y * w + x] >= 0.5f) {
                l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            }
        }
        return if (r <= l || b <= t) null else RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
    }

    private fun compose(source: Bitmap, mask: FloatArray, bounds: RectF): Bitmap {
        val w = source.width
        val h = source.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawBackground(canvas, w, h)

        val toned = tone(source)
        val objectBitmap = applyMask(toned, mask)
        if (!toned.isRecycled) toned.recycle()

        val contentW = bounds.width().coerceAtLeast(1f)
        val contentH = bounds.height().coerceAtLeast(1f)
        val aspect = contentH / contentW
        val tall = aspect > 1.18f
        val wide = aspect < 0.78f
        val targetW = w * when { tall -> 0.69f; wide -> 0.88f; else -> 0.86f }
        val targetH = h * when { tall -> 0.845f; wide -> 0.77f; else -> 0.79f }
        val scale = min(targetW / contentW, targetH / contentH)
        val bottom = h * when { tall -> 0.944f; wide -> 0.932f; else -> 0.936f }
        val placedW = contentW * scale
        val placedH = contentH * scale
        val left = (w - placedW) / 2f
        val placed = RectF(left, bottom - placedH, left + placedW, bottom)

        drawShadow(canvas, mask, bounds, scale, placed, w, h)
        val matrix = Matrix().apply {
            postTranslate(-bounds.left, -bounds.top)
            postScale(scale, scale)
            postTranslate(placed.left, placed.top)
        }
        canvas.drawBitmap(objectBitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (!objectBitmap.isRecycled) objectBitmap.recycle()
        return out
    }

    private fun drawBackground(canvas: Canvas, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.rgb(240, 237, 232), Color.rgb(228, 223, 216), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val floor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, h * 0.60f, 0f, h.toFloat(), Color.argb(0, 255, 255, 255), Color.argb(34, 255, 255, 255), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, h * 0.60f, w.toFloat(), h.toFloat(), floor)
    }

    private fun drawShadow(canvas: Canvas, mask: FloatArray, sourceBounds: RectF, scale: Float, placed: RectF, w: Int, h: Int) {
        val startX = max(0, sourceBounds.left.toInt())
        val endX = min(w - 1, sourceBounds.right.toInt())
        val nearBottom = sourceBounds.bottom - h * 0.03f
        val bottomByX = IntArray(w) { -1 }
        for (x in startX..endX) {
            var y = min(h - 1, sourceBounds.bottom.toInt())
            while (y >= sourceBounds.top.toInt()) {
                if (mask[y * w + x] >= 0.60f) { bottomByX[x] = y; break }
                y--
            }
        }
        var first = -1
        var last = -1
        for (x in startX..endX) if (bottomByX[x] >= nearBottom) {
            if (first < 0) first = x
            last = x
        }
        val objectW = placed.width()
        val weak = first < 0 || last <= first
        val center = if (!weak) placed.left + (((first + last) / 2f) - sourceBounds.left) * scale else placed.centerX()
        val span = if (!weak) (last - first) * scale else objectW * 0.55f
        val half = max(objectW * 0.15f, span * 0.30f).coerceAtMost(objectW * 0.34f)
        val baseY = placed.bottom
        val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(24, 42, 36, 32)
            maskFilter = BlurMaskFilter(max(w, h) * 0.008f, BlurMaskFilter.Blur.NORMAL)
        }
        val contact = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(66, 28, 24, 22)
            maskFilter = BlurMaskFilter(max(w, h) * 0.0020f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawOval(RectF(center - half, baseY - h * 0.001f, center + half, baseY + h * 0.010f), soft)

        var runStart = -1
        var x = startX
        while (x <= endX + 1) {
            val support = x <= endX && bottomByX[x] >= nearBottom
            if (support && runStart < 0) runStart = x
            if ((!support || x > endX) && runStart >= 0) {
                val runEnd = x - 1
                val runW = runEnd - runStart + 1
                if (runW >= max(2, (sourceBounds.width() * 0.01f).roundToInt())) {
                    val cx = placed.left + (((runStart + runEnd) / 2f) - sourceBounds.left) * scale
                    val rh = max(runW * scale * 0.28f, objectW * 0.017f)
                    canvas.drawOval(RectF(cx - rh, baseY - h * 0.0008f, cx + rh, baseY + h * 0.0032f), contact)
                }
                runStart = -1
            }
            x++
        }
    }

    private fun tone(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var lumSum = 0.0
        var satSum = 0.0
        var count = 0
        val step = max(1, pixels.size / 12000)
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            lumSum += (r * 30 + g * 59 + b * 11) / 100.0
            satSum += (max(r, max(g, b)) - min(r, min(g, b))).toDouble()
            count++; i += step
        }
        val lum = if (count > 0) lumSum / count else 160.0
        val sat = if (count > 0) satSum / count else 30.0
        val exposure = when { lum < 110 -> 1.05f; lum < 140 -> 1.025f; lum > 205 -> 0.94f; lum > 185 -> 0.97f; else -> 1f }
        val saturation = when { sat < 18 -> 1.08f; sat < 28 -> 1.04f; sat > 60 -> 0.94f; else -> 1f }

        for (p in pixels.indices) {
            val c = pixels[p]
            var r = Color.red(c) / 255f
            var g = Color.green(c) / 255f
            var b = Color.blue(c) / 255f
            fun curve(v: Float): Float {
                val mid = ((v - 0.5f) * 1.045f + 0.5f).coerceIn(0f, 1f)
                val compressed = if (mid > 0.86f) 0.86f + (mid - 0.86f) * 0.58f else mid
                return (compressed * exposure).coerceIn(0f, 1f)
            }
            r = curve(r); g = curve(g); b = curve(b)
            val gray = (r + g + b) / 3f
            r = (gray + (r - gray) * saturation).coerceIn(0f, 1f)
            g = (gray + (g - gray) * saturation).coerceIn(0f, 1f)
            b = (gray + (b - gray) * saturation).coerceIn(0f, 1f)
            pixels[p] = Color.argb(255, (r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt())
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    private fun applyMask(source: Bitmap, mask: FloatArray): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = Color.argb((mask[i].coerceIn(0f, 1f) * 255f).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, w, 0, 0, w, h) }
    }
}
