package com.tiagocrispo.furnitureshot.processing

import kotlin.math.max
import kotlin.math.min

internal data class SegmentationMaskMetrics(
    val accepted: Boolean,
    val score: Float,
    val coverage: Float,
    val uncertainRatio: Float,
    val borderRatio: Float,
    val edgeStripRatio: Float,
    val maxSideTouchRatio: Float,
    val fillRatio: Float,
    val lowerSupportRatio: Float,
    val verticalSupportRatio: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Deterministic, model-agnostic gate for furniture masks.
 *
 * The model is allowed to propose a matte; this gate decides whether ProductShot
 * trusts it. It deliberately prefers a conservative fallback over a confident but
 * structurally suspicious cutout.
 */
internal object SegmentationMaskQualityGate {
    fun evaluate(
        alpha: FloatArray,
        width: Int,
        height: Int,
        threshold: Float,
        minCoverage: Float,
        maxCoverage: Float,
        maxUncertainRatio: Float,
    ): SegmentationMaskMetrics {
        if (width < 2 || height < 2 || alpha.size != width * height) return rejected()

        var foreground = 0
        var uncertain = 0
        var border = 0
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        val stripX = max(1, (width * 0.012f).toInt())
        val stripY = max(1, (height * 0.012f).toInt())
        var edgeStrip = 0
        var leftTouch = 0
        var rightTouch = 0
        var topTouch = 0
        var bottomTouch = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val a = alpha[y * width + x]
                if (a in 0.08f..0.92f) uncertain++
                if (a < threshold) continue
                foreground++
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border++
                if (x == 0) leftTouch++
                if (x == width - 1) rightTouch++
                if (y == 0) topTouch++
                if (y == height - 1) bottomTouch++
                if (x < stripX || x >= width - stripX || y < stripY || y >= height - stripY) edgeStrip++
            }
        }

        if (foreground == 0 || right <= left || bottom <= top) return rejected()
        val coverage = foreground.toFloat() / alpha.size
        val uncertainRatio = uncertain.toFloat() / alpha.size
        val boundsWidth = right - left + 1
        val boundsHeight = bottom - top + 1
        val boundsArea = boundsWidth * boundsHeight
        val fillRatio = foreground.toFloat() / boundsArea.coerceAtLeast(1)
        val borderRatio = border.toFloat() / foreground
        val edgeStripRatio = edgeStrip.toFloat() / foreground
        val maxSideTouchRatio = max(
            max(leftTouch.toFloat() / height, rightTouch.toFloat() / height),
            max(topTouch.toFloat() / width, bottomTouch.toFloat() / width),
        )

        val lowerY = top + (boundsHeight * 0.68f).toInt().coerceIn(0, boundsHeight - 1)
        var lowerForeground = 0
        val verticalHits = IntArray(boundsWidth)
        for (y in lowerY..bottom) {
            for (x in left..right) {
                if (alpha[y * width + x] >= threshold) {
                    lowerForeground++
                    verticalHits[x - left]++
                }
            }
        }
        val lowerArea = boundsWidth * (bottom - lowerY + 1)
        val lowerSupportRatio = lowerForeground.toFloat() / lowerArea.coerceAtLeast(1)
        val lowerHeight = bottom - lowerY + 1
        val supportColumns = verticalHits.count { it >= max(2, (lowerHeight * 0.22f).toInt()) }
        val verticalSupportRatio = supportColumns.toFloat() / boundsWidth.coerceAtLeast(1)

        val useful = boundsWidth > width * 0.08f && boundsHeight > height * 0.12f
        val coverageOk = coverage in minCoverage..maxCoverage
        val uncertaintyOk = uncertainRatio <= maxUncertainRatio
        val borderOk = borderRatio < 0.035f && edgeStripRatio < 0.24f && maxSideTouchRatio < 0.18f
        val notSolidBox = fillRatio < 0.985f
        val lowerStructureOk = lowerSupportRatio >= 0.006f && verticalSupportRatio >= 0.006f

        val accepted = useful && coverageOk && uncertaintyOk && borderOk && notSolidBox && lowerStructureOk
        val sizeScore = (coverage / 0.42f).coerceIn(0f, 1f)
        val uncertaintyScore = (1f - uncertainRatio / maxUncertainRatio.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        val edgeScore = (1f - edgeStripRatio / 0.24f).coerceIn(0f, 1f)
        val structureScore = (verticalSupportRatio / 0.10f).coerceIn(0f, 1f)
        val score = sizeScore * 0.30f + uncertaintyScore * 0.20f + edgeScore * 0.22f + structureScore * 0.28f

        return SegmentationMaskMetrics(
            accepted = accepted,
            score = if (accepted) score else Float.NEGATIVE_INFINITY,
            coverage = coverage,
            uncertainRatio = uncertainRatio,
            borderRatio = borderRatio,
            edgeStripRatio = edgeStripRatio,
            maxSideTouchRatio = maxSideTouchRatio,
            fillRatio = fillRatio,
            lowerSupportRatio = lowerSupportRatio,
            verticalSupportRatio = verticalSupportRatio,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
        )
    }

    fun intersectionOverUnion(a: FloatArray, b: FloatArray, threshold: Float): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var intersection = 0
        var union = 0
        for (i in a.indices) {
            val af = a[i] >= threshold
            val bf = b[i] >= threshold
            if (af && bf) intersection++
            if (af || bf) union++
        }
        return if (union == 0) 0f else intersection.toFloat() / union
    }

    private fun rejected() = SegmentationMaskMetrics(
        accepted = false,
        score = Float.NEGATIVE_INFINITY,
        coverage = 0f,
        uncertainRatio = 1f,
        borderRatio = 1f,
        edgeStripRatio = 1f,
        maxSideTouchRatio = 1f,
        fillRatio = 0f,
        lowerSupportRatio = 0f,
        verticalSupportRatio = 0f,
        left = 0,
        top = 0,
        right = 0,
        bottom = 0,
    )
}
