package com.tiagocrispo.furnitureshot.processing

import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object PhotoPackAnalyzer {
    data class PhotoClassification(
        val label: String,
        val rank: Int,
        val coverScore: Float,
        val detailScore: Float,
        val sharpness: Float,
    )

    fun classify(path: String): PhotoClassification? {
        val stats = analyze(path) ?: return null
        val label = when {
            stats.coverage > 0.46f || stats.widthCoverage > 0.84f || stats.heightCoverage > 0.86f -> "Detalle"
            stats.aspect > 1.35f && stats.widthCoverage < 0.54f -> "Frontal"
            stats.widthCoverage >= 0.58f && stats.aspect in 0.70f..1.32f -> "3/4"
            stats.widthCoverage < 0.58f && stats.aspect in 0.70f..1.32f -> "Lateral"
            else -> "General"
        }
        val rank = when (label) {
            "Frontal" -> 1
            "3/4" -> 2
            "Lateral" -> 3
            "General" -> 4
            "Detalle" -> 5
            else -> 9
        }
        return PhotoClassification(
            label = label,
            rank = rank,
            coverScore = coverScore(stats, label),
            detailScore = detailScore(stats, label),
            sharpness = stats.sharpness,
        )
    }

    fun pickRecommendedCover(resultPaths: List<String>): String? {
        val classified = resultPaths.mapNotNull { path -> classify(path)?.let { path to it } }
        val nonDetail = classified.filter { it.second.label != "Detalle" }
        val pool = if (nonDetail.isNotEmpty()) nonDetail else classified
        return pool.maxByOrNull { it.second.coverScore }?.first
    }

    fun pickRecommendedDetails(
        resultPaths: List<String>,
        coverPath: String?,
        limit: Int = 2,
    ): List<String> {
        if (limit <= 0) return emptyList()
        val classified = resultPaths
            .filter { it != coverPath }
            .mapNotNull { path -> classify(path)?.let { path to it } }

        val details = classified.filter { it.second.label == "Detalle" }
        val pool = if (details.isNotEmpty()) details else classified
        return pool
            .sortedByDescending { it.second.detailScore }
            .take(limit)
            .map { it.first }
    }

    private data class Stats(
        val coverage: Float,
        val widthCoverage: Float,
        val heightCoverage: Float,
        val aspect: Float,
        val centerX: Float,
        val centerY: Float,
        val bottomNorm: Float,
        val sharpness: Float,
        val colorfulness: Float,
    )

    private fun analyze(path: String): Stats? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 4
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val bg = estimateBackground(pixels, width, height)
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            var objectCount = 0
            var colorfulnessSum = 0f
            val objectMask = BooleanArray(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val c = pixels[index]
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    val dist = abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2])
                    if (dist > 28) {
                        objectMask[index] = true
                        objectCount++
                        left = min(left, x)
                        top = min(top, y)
                        right = max(right, x)
                        bottom = max(bottom, y)
                        colorfulnessSum += (max(r, max(g, b)) - min(r, min(g, b))).toFloat() / 255f
                    }
                }
            }
            if (objectCount == 0 || right <= left || bottom <= top) return null
            val bboxW = (right - left + 1).toFloat()
            val bboxH = (bottom - top + 1).toFloat()
            Stats(
                coverage = objectCount.toFloat() / (width * height).toFloat(),
                widthCoverage = bboxW / width.toFloat(),
                heightCoverage = bboxH / height.toFloat(),
                aspect = bboxH / bboxW,
                centerX = ((left + right) / 2f) / width.toFloat(),
                centerY = ((top + bottom) / 2f) / height.toFloat(),
                bottomNorm = bottom.toFloat() / height.toFloat(),
                sharpness = estimateSharpness(pixels, objectMask, width, height),
                colorfulness = (colorfulnessSum / objectCount.toFloat()).coerceIn(0f, 1f),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun coverScore(stats: Stats, label: String): Float {
        val sizeScore = (1f - abs(stats.coverage - 0.34f) / 0.34f).coerceIn(0f, 1f)
        val widthScore = (1f - abs(stats.widthCoverage - 0.72f) / 0.72f).coerceIn(0f, 1f)
        val heightScore = (1f - abs(stats.heightCoverage - 0.67f) / 0.67f).coerceIn(0f, 1f)
        val centerScore = (1f - (abs(stats.centerX - 0.5f) * 1.2f + abs(stats.centerY - 0.54f) * 1.25f)).coerceIn(0f, 1f)
        val bottomScore = (1f - abs(stats.bottomNorm - 0.905f) / 0.905f).coerceIn(0f, 1f)
        val viewBonus = when (label) {
            "3/4" -> 1.00f
            "Frontal" -> 0.98f
            "General" -> 0.95f
            "Lateral" -> 0.90f
            "Detalle" -> 0.72f
            else -> 0.88f
        }
        return ((
            sizeScore * 0.24f +
                widthScore * 0.18f +
                heightScore * 0.16f +
                centerScore * 0.12f +
                bottomScore * 0.12f +
                stats.sharpness * 0.14f +
                stats.colorfulness * 0.04f
            ) * viewBonus).coerceIn(0f, 1f)
    }

    private fun detailScore(stats: Stats, label: String): Float {
        val closeness = ((stats.coverage - 0.22f) / 0.42f).coerceIn(0f, 1f)
        val framing = (1f - abs(stats.centerX - 0.5f) * 1.8f).coerceIn(0f, 1f)
        val detailBonus = if (label == "Detalle") 1f else 0.84f
        return ((
            stats.sharpness * 0.55f +
                closeness * 0.22f +
                stats.colorfulness * 0.10f +
                framing * 0.13f
            ) * detailBonus).coerceIn(0f, 1f)
    }

    private fun estimateSharpness(
        pixels: IntArray,
        objectMask: BooleanArray,
        width: Int,
        height: Int,
    ): Float {
        if (width < 3 || height < 3) return 0f
        var edgeEnergy = 0.0
        var count = 0
        for (y in 1 until height - 1 step 2) {
            for (x in 1 until width - 1 step 2) {
                val i = y * width + x
                if (!objectMask[i]) continue
                fun luma(index: Int): Int {
                    val c = pixels[index]
                    return (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
                }
                val center = luma(i)
                val lap = abs(
                    4 * center -
                        luma(i - 1) -
                        luma(i + 1) -
                        luma(i - width) -
                        luma(i + width),
                )
                edgeEnergy += lap.toDouble()
                count++
            }
        }
        if (count == 0) return 0f
        return ((edgeEnergy / count.toDouble()) / 28.0).toFloat().coerceIn(0f, 1f)
    }

    private fun estimateBackground(pixels: IntArray, width: Int, height: Int): IntArray {
        val samples = mutableListOf<Int>()
        val dx = max(1, width / 10)
        val dy = max(1, height / 10)
        fun sample(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0 until y1 step max(1, (y1 - y0) / 4)) {
                for (x in x0 until x1 step max(1, (x1 - x0) / 4)) {
                    samples += pixels[y * width + x]
                }
            }
        }
        sample(0, 0, dx, dy)
        sample(width - dx, 0, width, dy)
        sample(0, height - dy, dx, height)
        sample(width - dx, height - dy, width, height)
        val r = samples.map { Color.red(it) }.average().toInt()
        val g = samples.map { Color.green(it) }.average().toInt()
        val b = samples.map { Color.blue(it) }.average().toInt()
        return intArrayOf(r, g, b)
    }
}
