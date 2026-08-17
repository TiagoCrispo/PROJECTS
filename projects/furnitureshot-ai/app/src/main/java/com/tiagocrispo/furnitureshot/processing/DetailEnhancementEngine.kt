package com.tiagocrispo.furnitureshot.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Conservative, tile-based detail reconstruction for furniture catalog photos.
 *
 * The goal is not to invent texture. It reduces small sensor/compression noise,
 * restores local edge contrast and gently protects wood grain without applying
 * global HDR or aggressive sharpening. Processing is tiled to keep peak memory
 * low on mid-range phones.
 */
object DetailEnhancementEngine {
    private const val TILE_HEIGHT = 160
    private const val OVERLAP = 1
    private const val DENOISE_LUMA_THRESHOLD = 18f
    private const val DETAIL_LIMIT = 11f
    private const val SHARPEN_AMOUNT = 0.20f
    private const val SATURATION = 1.012f

    fun enhanceForCatalog(source: Bitmap): Bitmap {
        if (source.width < 3 || source.height < 3) return source

        val output = try {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return source
        }

        return try {
            var writeTop = 0
            while (writeTop < source.height) {
                val writeBottom = min(source.height, writeTop + TILE_HEIGHT)
                val readTop = max(0, writeTop - OVERLAP)
                val readBottom = min(source.height, writeBottom + OVERLAP)
                val readHeight = readBottom - readTop

                val input = IntArray(source.width * readHeight)
                source.getPixels(
                    input,
                    0,
                    source.width,
                    0,
                    readTop,
                    source.width,
                    readHeight,
                )

                val outputHeight = writeBottom - writeTop
                val tileOutput = IntArray(source.width * outputHeight)

                for (globalY in writeTop until writeBottom) {
                    val localY = globalY - readTop
                    val outY = globalY - writeTop
                    for (x in 0 until source.width) {
                        val center = input[localY * source.width + x]

                        if (x == 0 || x == source.width - 1 || globalY == 0 || globalY == source.height - 1) {
                            tileOutput[outY * source.width + x] = center
                            continue
                        }

                        tileOutput[outY * source.width + x] = enhancePixel(
                            input = input,
                            width = source.width,
                            localX = x,
                            localY = localY,
                            center = center,
                        )
                    }
                }

                output.setPixels(
                    tileOutput,
                    0,
                    source.width,
                    0,
                    writeTop,
                    source.width,
                    outputHeight,
                )
                writeTop = writeBottom
            }
            output
        } catch (_: OutOfMemoryError) {
            if (!output.isRecycled) output.recycle()
            source
        } catch (_: Throwable) {
            if (!output.isRecycled) output.recycle()
            source
        }
    }

    private fun enhancePixel(
        input: IntArray,
        width: Int,
        localX: Int,
        localY: Int,
        center: Int,
    ): Int {
        val centerR = Color.red(center).toFloat()
        val centerG = Color.green(center).toFloat()
        val centerB = Color.blue(center).toFloat()
        val centerLuma = luma(centerR, centerG, centerB)

        var sumR = centerR * 4f
        var sumG = centerG * 4f
        var sumB = centerB * 4f
        var totalWeight = 4f

        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val neighbor = input[(localY + dy) * width + localX + dx]
                val nr = Color.red(neighbor).toFloat()
                val ng = Color.green(neighbor).toFloat()
                val nb = Color.blue(neighbor).toFloat()
                val neighborLuma = luma(nr, ng, nb)
                val difference = abs(neighborLuma - centerLuma)

                // Smooth only pixels that are likely the same local surface.
                // Strong edges and wood joints are deliberately excluded.
                if (difference <= DENOISE_LUMA_THRESHOLD) {
                    val weight = 1f - difference / (DENOISE_LUMA_THRESHOLD + 1f)
                    sumR += nr * weight
                    sumG += ng * weight
                    sumB += nb * weight
                    totalWeight += weight
                }
            }
        }

        val baseR = sumR / totalWeight
        val baseG = sumG / totalWeight
        val baseB = sumB / totalWeight

        val detailR = (centerR - baseR).coerceIn(-DETAIL_LIMIT, DETAIL_LIMIT)
        val detailG = (centerG - baseG).coerceIn(-DETAIL_LIMIT, DETAIL_LIMIT)
        val detailB = (centerB - baseB).coerceIn(-DETAIL_LIMIT, DETAIL_LIMIT)

        var r = baseR + detailR * (1f + SHARPEN_AMOUNT)
        var g = baseG + detailG * (1f + SHARPEN_AMOUNT)
        var b = baseB + detailB * (1f + SHARPEN_AMOUNT)

        // Gentle tonal protection: open very dark wood without flattening it,
        // and roll off bright reflections before clipping.
        val currentLuma = luma(r, g, b)
        val toneScale = when {
            currentLuma < 55f -> 1.025f
            currentLuma > 225f -> 0.992f
            else -> 1f
        }
        r *= toneScale
        g *= toneScale
        b *= toneScale

        val neutral = luma(r, g, b)
        r = neutral + (r - neutral) * SATURATION
        g = neutral + (g - neutral) * SATURATION
        b = neutral + (b - neutral) * SATURATION

        return Color.argb(
            Color.alpha(center),
            r.roundToInt().coerceIn(0, 255),
            g.roundToInt().coerceIn(0, 255),
            b.roundToInt().coerceIn(0, 255),
        )
    }

    private fun luma(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b
}
