package com.tiagocrispo.furnitureshot.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Conservative high-frequency preservation pass.
 *
 * This intentionally avoids the old "denoise then sharpen" behavior. Real wood grain,
 * pores, joints and fine edges stay anchored to the source pixel. Only a very small
 * edge-aware high-pass boost is added where neighboring pixels support real structure.
 */
object DetailEnhancementEngine {
    private const val TILE_HEIGHT = 160
    private const val OVERLAP = 1
    private const val SAME_SURFACE_THRESHOLD = 24f
    private const val HIGH_PASS_LIMIT = 24f
    private const val DETAIL_AMOUNT = 0.085f
    private const val FLAT_DENOISE_BLEND = 0.055f

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
                source.getPixels(input, 0, source.width, 0, readTop, source.width, readHeight)

                val tileOutput = IntArray(source.width * (writeBottom - writeTop))
                for (globalY in writeTop until writeBottom) {
                    val localY = globalY - readTop
                    val outY = globalY - writeTop
                    for (x in 0 until source.width) {
                        val center = input[localY * source.width + x]
                        tileOutput[outY * source.width + x] = if (
                            x == 0 || x == source.width - 1 ||
                            globalY == 0 || globalY == source.height - 1
                        ) {
                            center
                        } else {
                            enhancePixel(input, source.width, x, localY, center)
                        }
                    }
                }

                output.setPixels(
                    tileOutput,
                    0,
                    source.width,
                    0,
                    writeTop,
                    source.width,
                    writeBottom - writeTop,
                )
                writeTop = writeBottom
            }
            output
        } catch (_: Throwable) {
            if (!output.isRecycled) output.recycle()
            source
        }
    }

    private fun enhancePixel(
        input: IntArray,
        width: Int,
        x: Int,
        y: Int,
        center: Int,
    ): Int {
        val cr = Color.red(center).toFloat()
        val cg = Color.green(center).toFloat()
        val cb = Color.blue(center).toFloat()
        val centerLuma = luma(cr, cg, cb)

        var sumR = cr * 4f
        var sumG = cg * 4f
        var sumB = cb * 4f
        var totalWeight = 4f
        var maxNeighborDelta = 0f

        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val neighbor = input[(y + dy) * width + x + dx]
                val nr = Color.red(neighbor).toFloat()
                val ng = Color.green(neighbor).toFloat()
                val nb = Color.blue(neighbor).toFloat()
                val difference = abs(luma(nr, ng, nb) - centerLuma)
                maxNeighborDelta = max(maxNeighborDelta, difference)
                if (difference <= SAME_SURFACE_THRESHOLD) {
                    val weight = 1f - difference / (SAME_SURFACE_THRESHOLD + 1f)
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
        val hpR = (cr - baseR).coerceIn(-HIGH_PASS_LIMIT, HIGH_PASS_LIMIT)
        val hpG = (cg - baseG).coerceIn(-HIGH_PASS_LIMIT, HIGH_PASS_LIMIT)
        val hpB = (cb - baseB).coerceIn(-HIGH_PASS_LIMIT, HIGH_PASS_LIMIT)

        val chroma = max(cr, max(cg, cb)) - min(cr, min(cg, cb))
        val flatArea = maxNeighborDelta < 4.5f && chroma < 18f
        val denoiseBlend = if (flatArea) FLAT_DENOISE_BLEND else 0f

        val preservedR = cr * (1f - denoiseBlend) + baseR * denoiseBlend
        val preservedG = cg * (1f - denoiseBlend) + baseG * denoiseBlend
        val preservedB = cb * (1f - denoiseBlend) + baseB * denoiseBlend

        val structural = maxNeighborDelta in 4.5f..80f
        val detailAmount = if (structural) DETAIL_AMOUNT else DETAIL_AMOUNT * 0.45f
        val r = preservedR + hpR * detailAmount
        val g = preservedG + hpG * detailAmount
        val b = preservedB + hpB * detailAmount

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
