package com.tiagocrispo.furnitureshot.processing

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.tiagocrispo.furnitureshot.data.ImageStore
import com.tiagocrispo.furnitureshot.model.ProcessResult
import com.tiagocrispo.furnitureshot.model.ProcessSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v0.7.1-beta22 consolidated catalog pipeline.
 *
 * Principles:
 * - original product pixels remain the source of truth;
 * - segmentation is only a coarse guide, with full-resolution RGB refinement;
 * - geometry is locked to one uniform scale + translation (never warp/rebuild);
 * - no generic/drop/oval shadow is ever created;
 * - a real shadow is reused only when it is attached to detected supports with
 *   enough confidence; otherwise the final background stays clean;
 * - tonal/detail enhancement is bounded and automatically falls back when it
 *   diverges too far from the source.
 */
private object CatalogPipelineV22 {
    private const val SEGMENTATION_LONG_EDGE = 1024
    private const val SEGMENTATION_TIMEOUT_MS = 35_000L
    private const val COMPONENT_THRESHOLD = 0.28f
    private const val MIN_COVERAGE = 0.022f
    private const val MAX_COVERAGE = 0.86f

    suspend fun process(
        context: Context,
        originalPath: String,
        settings: ProcessSettings,
        onProgress: suspend (Int, String) -> Unit,
    ): ProcessResult = withContext(Dispatchers.Default) {
        val preferredDimension = chooseProcessingDimension(context)
        try {
            processAttempt(originalPath, settings, preferredDimension, null, onProgress)
        } catch (_: OutOfMemoryError) {
            System.gc()
            processAttempt(
                originalPath,
                settings,
                1800,
                "La foto era muy grande; se procesó en modo de memoria segura.",
                onProgress,
            )
        }
    }

    private suspend fun processAttempt(
        originalPath: String,
        settings: ProcessSettings,
        maxDimension: Int,
        memoryWarning: String?,
        onProgress: suspend (Int, String) -> Unit,
    ): ProcessResult {
        var source: Bitmap? = null
        var enhanced: Bitmap? = null
        var cutout: Bitmap? = null
        var naturalShadow: Bitmap? = null
        var output: Bitmap? = null
        try {
            onProgress(7, "Cargando foto")
            source = ImageStore.loadForProcessing(originalPath, maxDimension)
            coroutineContext.ensureActive()

            onProgress(20, "Analizando calidad")
            val quality = analyzeInput(source)

            onProgress(34, "Detectando el mueble")
            val coarse = buildCoarseAlpha(source) ?: return saveConservativeFallback(
                source,
                originalPath,
                memoryWarning,
                "No se pudo separar el producto con suficiente seguridad; se conservó la foto sin inventar recorte ni sombra.",
            )
            coroutineContext.ensureActive()

            onProgress(51, "Refinando bordes")
            val alpha = upscaleAndRefineAlpha(source, coarse)
            val maskReport = validateAlpha(alpha, source.width, source.height)
            if (!maskReport.accepted) {
                return saveConservativeFallback(
                    source,
                    originalPath,
                    memoryWarning,
                    "El recorte de alta resolución no superó el control de fidelidad; se conservó la fotografía.",
                )
            }

            onProgress(63, "Recuperando textura real")
            enhanced = enhanceObjectConservatively(source, alpha, quality, settings)
            val fidelity = compareFidelity(source, enhanced, alpha)
            if (!fidelity.accepted) {
                enhanced.recycle()
                enhanced = source.copy(Bitmap.Config.ARGB_8888, false)
            }

            cutout = extractCutoutWithDecontamination(enhanced, alpha)
            val shadowReport = extractNaturalContactShadow(source, alpha, maskReport.bounds)
            naturalShadow = if (
                shadowReport.confidence >= 0.34f &&
                shadowReport.floatingScore <= 0.14f
            ) shadowReport.bitmap else null
            if (naturalShadow !== shadowReport.bitmap) {
                shadowReport.bitmap?.let { if (!it.isRecycled) it.recycle() }
            }

            onProgress(78, "Integrando fondo")
            output = composeCatalogShot(
                sourceWidth = source.width,
                sourceHeight = source.height,
                cutout = cutout,
                shadow = naturalShadow,
                bounds = maskReport.bounds,
            )

            onProgress(87, "Control de calidad")
            val compositeReport = inspectComposite(output, cutout, maskReport.bounds)
            if (!compositeReport.accepted && naturalShadow != null) {
                output.recycle()
                output = composeCatalogShot(
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                    cutout = cutout,
                    shadow = null,
                    bounds = maskReport.bounds,
                )
            }

            onProgress(95, "Guardando resultado")
            val resultFile = saveJpeg(originalPath, output)
            onProgress(100, "Listo")
            return ProcessResult(
                resultPath = resultFile.absolutePath,
                backgroundReplaced = true,
                warning = memoryWarning,
            )
        } finally {
            output?.let { if (!it.isRecycled) it.recycle() }
            naturalShadow?.let { if (!it.isRecycled) it.recycle() }
            cutout?.let { if (!it.isRecycled) it.recycle() }
            enhanced?.let { if (it !== source && !it.isRecycled) it.recycle() }
            source?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun chooseProcessingDimension(context: Context): Int {
        val manager = context.getSystemService(ActivityManager::class.java)
        return when (manager?.memoryClass ?: 256) {
            in 0..192 -> 2200
            in 193..256 -> 2800
            in 257..384 -> 3200
            else -> 3840
        }
    }

    private data class InputQuality(
        val meanLuma: Float,
        val highlightClip: Float,
        val shadowClip: Float,
        val edgeEnergy: Float,
        val noiseProxy: Float,
    )

    private fun analyzeInput(source: Bitmap): InputQuality {
        val step = max(1, max(source.width, source.height) / 480)
        var sumL = 0f
        var count = 0
        var highlights = 0
        var shadows = 0
        var edge = 0f
        var noise = 0f
        for (y in 1 until source.height - 1 step step) {
            for (x in 1 until source.width - 1 step step) {
                val c = source.getPixel(x, y)
                val l = luma(c)
                sumL += l
                if (l >= 248f) highlights++
                if (l <= 9f) shadows++
                val gx = abs(luma(source.getPixel(x + 1, y)) - luma(source.getPixel(x - 1, y)))
                val gy = abs(luma(source.getPixel(x, y + 1)) - luma(source.getPixel(x, y - 1)))
                val g = (gx + gy) * 0.5f
                edge += g
                if (g < 8f) {
                    val local = abs(l - luma(source.getPixel(x + 1, y + 1)))
                    noise += local
                }
                count++
            }
        }
        val safeCount = max(1, count).toFloat()
        return InputQuality(
            meanLuma = sumL / safeCount,
            highlightClip = highlights / safeCount,
            shadowClip = shadows / safeCount,
            edgeEnergy = edge / safeCount,
            noiseProxy = noise / safeCount,
        )
    }

    private data class CoarseAlpha(val values: FloatArray, val width: Int, val height: Int)

    private suspend fun buildCoarseAlpha(source: Bitmap): CoarseAlpha? {
        val working = scaleForSegmentation(source)
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
        )
        try {
            val result = withTimeoutOrNull(SEGMENTATION_TIMEOUT_MS) {
                segmenter.process(InputImage.fromBitmap(working, 0)).await()
            } ?: return null
            coroutineContext.ensureActive()
            val buffer = result.foregroundConfidenceMask ?: return null
            val values = FloatArray(working.width * working.height)
            val copy = buffer.duplicate().apply { rewind() }
            var i = 0
            while (copy.hasRemaining() && i < values.size) {
                val confidence = copy.get().coerceIn(0f, 1f)
                values[i++] = confidenceToAlpha(confidence)
            }
            if (i < values.size) return null
            if (!retainMainComponent(values, working.width, working.height)) return null
            protectThinStructures(values, working.width, working.height)
            return CoarseAlpha(values, working.width, working.height)
        } catch (_: Throwable) {
            return null
        } finally {
            segmenter.close()
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    private fun scaleForSegmentation(source: Bitmap): Bitmap {
        val longEdge = max(source.width, source.height)
        if (longEdge <= SEGMENTATION_LONG_EDGE) return source
        val scale = SEGMENTATION_LONG_EDGE.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun confidenceToAlpha(confidence: Float): Float {
        val low = 0.10f
        val high = 0.84f
        if (confidence <= low) return 0f
        if (confidence >= high) return 1f
        val t = ((confidence - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun retainMainComponent(alpha: FloatArray, width: Int, height: Int): Boolean {
        val labels = IntArray(alpha.size)
        val queue = IntArray(alpha.size)
        var label = 0
        var bestLabel = 0
        var bestScore = -1f
        val imageArea = alpha.size.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        for (start in alpha.indices) {
            if (labels[start] != 0 || alpha[start] < COMPONENT_THRESHOLD) continue
            label++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var area = 0
            var sumX = 0L
            var sumY = 0L
            var border = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                area++
                sumX += x
                sumY += y
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border++
                fun push(next: Int) {
                    if (labels[next] == 0 && alpha[next] >= COMPONENT_THRESHOLD) {
                        labels[next] = label
                        queue[tail++] = next
                    }
                }
                if (x > 0) push(index - 1)
                if (x + 1 < width) push(index + 1)
                if (y > 0) push(index - width)
                if (y + 1 < height) push(index + width)
            }
            if (area < 16) continue
            val cx = sumX.toFloat() / area
            val cy = sumY.toFloat() / area
            val dx = (cx - centerX) / width
            val dy = (cy - centerY) / height
            val centerScore = (1f - sqrt(dx * dx + dy * dy) / 0.72f).coerceIn(0f, 1f)
            val areaScore = (area / imageArea).coerceIn(0f, 1f)
            val borderPenalty = (border.toFloat() / area).coerceIn(0f, 1f)
            val score = areaScore * 0.84f + centerScore * 0.16f - borderPenalty * 0.25f
            if (score > bestScore) {
                bestScore = score
                bestLabel = label
            }
        }
        if (bestLabel == 0) return false
        for (i in alpha.indices) if (labels[i] != bestLabel) alpha[i] = 0f
        return true
    }

    private fun protectThinStructures(alpha: FloatArray, width: Int, height: Int) {
        val copy = alpha.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                if (copy[i] >= 0.84f) continue
                val horizontal = copy[i - 1] >= 0.76f && copy[i + 1] >= 0.76f
                val vertical = copy[i - width] >= 0.76f && copy[i + width] >= 0.76f
                if (horizontal || vertical) alpha[i] = max(alpha[i], 0.88f)
            }
        }
    }

    private fun upscaleAndRefineAlpha(source: Bitmap, coarse: CoarseAlpha): FloatArray {
        val smallPixels = IntArray(coarse.values.size) { i ->
            Color.argb((coarse.values[i] * 255f).roundToInt().coerceIn(0, 255), 255, 255, 255)
        }
        val small = Bitmap.createBitmap(coarse.width, coarse.height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(smallPixels, 0, coarse.width, 0, 0, coarse.width, coarse.height)
        }
        val scaled = if (coarse.width == source.width && coarse.height == source.height) {
            small
        } else {
            Bitmap.createScaledBitmap(small, source.width, source.height, true)
        }
        if (scaled !== small) small.recycle()

        val maskPixels = IntArray(source.width * source.height)
        scaled.getPixels(maskPixels, 0, source.width, 0, 0, source.width, source.height)
        scaled.recycle()
        val alpha = FloatArray(maskPixels.size) { i -> Color.alpha(maskPixels[i]) / 255f }
        val rgb = IntArray(maskPixels.size)
        source.getPixels(rgb, 0, source.width, 0, 0, source.width, source.height)
        val bg = estimateCornerBackground(rgb, source.width, source.height)

        repeat(2) {
            val copy = alpha.clone()
            for (y in 1 until source.height - 1) {
                for (x in 1 until source.width - 1) {
                    val i = y * source.width + x
                    val a = copy[i]
                    if (a <= 0.001f) continue
                    val distance = colorDistance(rgb[i], bg)
                    val gradient = localGradient(rgb, source.width, x, y)
                    var strong = 0
                    var weak = 0
                    var avg = 0f
                    for (dy in -1..1) for (dx in -1..1) {
                        val n = copy[(y + dy) * source.width + x + dx]
                        avg += n
                        if (n >= 0.80f) strong++
                        if (n <= 0.16f) weak++
                    }
                    avg /= 9f
                    alpha[i] = when {
                        a < 0.38f && distance < 34f && gradient < 20f && avg < 0.44f -> 0f
                        a < 0.62f && distance < 40f && weak >= 5 -> a * 0.18f
                        a > 0.42f && (distance > 58f || gradient > 32f) && strong >= 4 -> max(a, 0.90f)
                        else -> a
                    }.coerceIn(0f, 1f)
                }
            }
            protectThinStructures(alpha, source.width, source.height)
            removeIsolatedLeaks(alpha, rgb, bg, source.width, source.height)
        }
        adaptiveFeather(alpha, source.width, source.height)
        return alpha
    }

    private fun removeIsolatedLeaks(
        alpha: FloatArray,
        rgb: IntArray,
        bg: FloatArray,
        width: Int,
        height: Int,
    ) {
        val copy = alpha.clone()
        for (y in 2 until height - 2) {
            for (x in 2 until width - 2) {
                val i = y * width + x
                if (copy[i] < 0.20f || colorDistance(rgb[i], bg) > 32f) continue
                var weak = 0
                var strong = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val n = copy[(y + dy) * width + x + dx]
                    if (n < 0.20f) weak++
                    if (n > 0.78f) strong++
                }
                if (weak >= 6 && strong <= 2) alpha[i] = 0f
            }
        }
    }

    private fun adaptiveFeather(alpha: FloatArray, width: Int, height: Int) {
        val copy = alpha.clone()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val center = copy[i]
                if (center <= 0.02f || center >= 0.98f) continue
                var sum = 0f
                for (dy in -1..1) for (dx in -1..1) sum += copy[(y + dy) * width + x + dx]
                val avg = sum / 9f
                alpha[i] = (center * 0.70f + avg * 0.30f).coerceIn(0f, 1f)
            }
        }
    }

    private data class MaskReport(
        val accepted: Boolean,
        val bounds: RectF,
        val uncertainRatio: Float,
    )

    private fun validateAlpha(alpha: FloatArray, width: Int, height: Int): MaskReport {
        var count = 0
        var uncertain = 0
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        var border = 0
        for (y in 0 until height) for (x in 0 until width) {
            val a = alpha[y * width + x]
            if (a in 0.08f..0.92f) uncertain++
            if (a >= 0.48f) {
                count++
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border++
            }
        }
        if (count == 0 || right <= left || bottom <= top) return MaskReport(false, RectF(), 1f)
        val coverage = count.toFloat() / alpha.size
        val uncertainRatio = uncertain.toFloat() / alpha.size
        val bounds = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        val useful = bounds.width() > width * 0.08f && bounds.height() > height * 0.12f
        val borderRatio = border.toFloat() / max(1, count)
        return MaskReport(
            accepted = coverage in MIN_COVERAGE..MAX_COVERAGE && useful && borderRatio < 0.035f && uncertainRatio < 0.16f,
            bounds = bounds,
            uncertainRatio = uncertainRatio,
        )
    }

    private fun enhanceObjectConservatively(
        source: Bitmap,
        alpha: FloatArray,
        quality: InputQuality,
        settings: ProcessSettings,
    ): Bitmap {
        val width = source.width
        val height = source.height
        val src = IntArray(width * height)
        source.getPixels(src, 0, width, 0, 0, width, height)
        val out = src.clone()

        val targetLuma = 151f
        val exposure = (1f + (targetLuma - quality.meanLuma) / 255f * 0.11f)
            .coerceIn(0.965f, if (quality.highlightClip > 0.025f) 1.015f else 1.045f)
        val saturation = settings.saturation.coerceIn(0.99f, 1.018f)
        val detail = settings.detailStrength.coerceIn(0f, 0.10f) *
            if (quality.noiseProxy > 4.5f) 0.50f else 1f

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                if (alpha[i] < 0.16f) continue
                val c = src[i]
                val r0 = Color.red(c).toFloat()
                val g0 = Color.green(c).toFloat()
                val b0 = Color.blue(c).toFloat()

                var localR = 0f
                var localG = 0f
                var localB = 0f
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val p = src[(y + dy) * width + x + dx]
                    localR += Color.red(p)
                    localG += Color.green(p)
                    localB += Color.blue(p)
                    n++
                }
                localR /= n
                localG /= n
                localB /= n
                val localEdge = (abs(r0 - localR) + abs(g0 - localG) + abs(b0 - localB)) / 3f
                val materialDetail = if (looksLikeWood(r0, g0, b0)) detail else detail * 0.72f
                val sharpenAmount = if (localEdge in 2.0f..28f) materialDetail else 0f

                var r = r0 + (r0 - localR).coerceIn(-7f, 7f) * sharpenAmount
                var g = g0 + (g0 - localG).coerceIn(-7f, 7f) * sharpenAmount
                var b = b0 + (b0 - localB).coerceIn(-7f, 7f) * sharpenAmount

                r = toneChannel(r / 255f, exposure) * 255f
                g = toneChannel(g / 255f, exposure) * 255f
                b = toneChannel(b / 255f, exposure) * 255f
                val gray = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r = gray + (r - gray) * saturation
                g = gray + (g - gray) * saturation
                b = gray + (b - gray) * saturation
                out[i] = Color.argb(
                    255,
                    r.roundToInt().coerceIn(0, 255),
                    g.roundToInt().coerceIn(0, 255),
                    b.roundToInt().coerceIn(0, 255),
                )
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(out, 0, width, 0, 0, width, height)
        }
    }

    private fun toneChannel(value: Float, exposure: Float): Float {
        var v = (value * exposure).coerceIn(0f, 1f)
        if (v < 0.18f) v += (0.18f - v) * 0.055f
        if (v > 0.86f) v = 0.86f + (v - 0.86f) * 0.73f
        return v.coerceIn(0f, 1f)
    }

    private fun looksLikeWood(r: Float, g: Float, b: Float): Boolean {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val sat = maxC - minC
        return r > g * 0.98f && g > b * 0.85f && sat in 12f..150f && r in 45f..245f
    }

    private data class FidelityReport(val accepted: Boolean, val edgeRatio: Float, val colorDelta: Float)

    private fun compareFidelity(original: Bitmap, candidate: Bitmap, alpha: FloatArray): FidelityReport {
        val step = max(1, max(original.width, original.height) / 520)
        var edgeOriginal = 0f
        var edgeCandidate = 0f
        var colorDelta = 0f
        var count = 0
        for (y in 1 until original.height - 1 step step) {
            for (x in 1 until original.width - 1 step step) {
                val i = y * original.width + x
                if (alpha[i] < 0.80f) continue
                val o = original.getPixel(x, y)
                val c = candidate.getPixel(x, y)
                colorDelta += (
                    abs(Color.red(o) - Color.red(c)) +
                        abs(Color.green(o) - Color.green(c)) +
                        abs(Color.blue(o) - Color.blue(c))
                    ) / (255f * 3f)
                edgeOriginal += abs(luma(original.getPixel(x + 1, y)) - luma(original.getPixel(x - 1, y)))
                edgeCandidate += abs(luma(candidate.getPixel(x + 1, y)) - luma(candidate.getPixel(x - 1, y)))
                count++
            }
        }
        if (count == 0) return FidelityReport(false, 0f, 1f)
        val edgeRatio = edgeCandidate / edgeOriginal.coerceAtLeast(0.001f)
        val delta = colorDelta / count
        return FidelityReport(edgeRatio in 0.82f..1.28f && delta < 0.075f, edgeRatio, delta)
    }

    private fun extractCutoutWithDecontamination(source: Bitmap, alpha: FloatArray): Bitmap {
        val width = source.width
        val height = source.height
        val src = IntArray(width * height)
        source.getPixels(src, 0, width, 0, 0, width, height)
        val bg = estimateCornerBackground(src, width, height)
        val out = IntArray(src.size)
        for (i in src.indices) {
            val a = alpha[i].coerceIn(0f, 1f)
            if (a <= 0.002f) {
                out[i] = Color.TRANSPARENT
                continue
            }
            var r = Color.red(src[i]).toFloat()
            var g = Color.green(src[i]).toFloat()
            var b = Color.blue(src[i]).toFloat()
            if (a in 0.08f..0.96f) {
                val safeA = max(0.22f, a)
                r = ((r - bg[0] * (1f - safeA)) / safeA).coerceIn(0f, 255f)
                g = ((g - bg[1] * (1f - safeA)) / safeA).coerceIn(0f, 255f)
                b = ((b - bg[2] * (1f - safeA)) / safeA).coerceIn(0f, 255f)
            }
            out[i] = Color.argb(
                (a * 255f).roundToInt().coerceIn(0, 255),
                r.roundToInt(),
                g.roundToInt(),
                b.roundToInt(),
            )
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(out, 0, width, 0, 0, width, height)
        }
    }

    private data class ShadowReport(val bitmap: Bitmap?, val confidence: Float, val floatingScore: Float)

    private fun extractNaturalContactShadow(source: Bitmap, alpha: FloatArray, bounds: RectF): ShadowReport {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val left = bounds.left.roundToInt().coerceIn(0, width - 1)
        val right = bounds.right.roundToInt().coerceIn(0, width - 1)
        val top = bounds.top.roundToInt().coerceIn(0, height - 1)
        val bottom = bounds.bottom.roundToInt().coerceIn(0, height - 1)
        val supportY = IntArray(width) { -1 }
        var supports = 0
        for (x in left..right) {
            for (y in min(height - 1, bottom + 1) downTo top) {
                if (alpha[y * width + x] >= 0.58f) {
                    supportY[x] = y
                    supports++
                    break
                }
            }
        }
        if (supports < max(6, (right - left + 1) / 14)) return ShadowReport(null, 0f, 0f)

        val floor = estimateFloorBackground(pixels, alpha, width, height, left, right, bottom)
        val floorLuma = 0.2126f * floor[0] + 0.7152f * floor[1] + 0.0722f * floor[2]
        val depth = (bounds.height() * 0.055f).roundToInt().coerceIn(5, max(9, height / 22))
        val mask = FloatArray(width * height)
        var attached = 0
        var candidateColumns = 0
        var averageGap = 0f
        var totalEnergy = 0f

        for (x in left..right) {
            val foot = supportY[x]
            if (foot < 0) continue
            var first = -1
            for (y in foot until min(height, foot + depth + 1)) {
                val i = y * width + x
                if (alpha[i] > 0.10f) continue
                val c = pixels[i]
                val lum = luma(c)
                val maxC = max(Color.red(c), max(Color.green(c), Color.blue(c))).toFloat()
                val minC = min(Color.red(c), min(Color.green(c), Color.blue(c))).toFloat()
                val saturation = (maxC - minC) / 255f
                val darkness = ((floorLuma - lum) / 255f).coerceIn(0f, 1f)
                val falloff = (1f - (y - foot).toFloat() / (depth + 1)).coerceIn(0f, 1f)
                val value = ((darkness - 0.035f) / 0.18f).coerceIn(0f, 1f) *
                    (1f - saturation / 0.55f).coerceIn(0f, 1f) * falloff
                if (value > 0.10f) {
                    mask[i] = (value * 0.48f).coerceAtMost(0.48f)
                    totalEnergy += mask[i]
                    if (first < 0) first = y - foot
                    if (y - foot <= 1) attached++
                }
            }
            if (first >= 0) {
                candidateColumns++
                averageGap += first
            }
        }
        if (candidateColumns == 0 || totalEnergy < 0.20f) return ShadowReport(null, 0f, 0f)
        averageGap /= candidateColumns
        val attachmentRatio = attached.toFloat() / candidateColumns.coerceAtLeast(1)
        val confidence = (attachmentRatio * 0.60f + (candidateColumns.toFloat() / supports) * 0.40f).coerceIn(0f, 1f)
        val floating = ((averageGap / 2f) * 0.72f + (1f - attachmentRatio) * 0.28f).coerceIn(0f, 1f)
        if (confidence < 0.34f || floating > 0.14f) return ShadowReport(null, confidence, floating)

        val shadowPixels = IntArray(mask.size)
        for (i in mask.indices) {
            val a = (mask[i] * 70f).roundToInt().coerceIn(0, 58)
            shadowPixels[i] = if (a == 0) Color.TRANSPARENT else Color.argb(a, 88, 83, 78)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(shadowPixels, 0, width, 0, 0, width, height)
        }
        return ShadowReport(bitmap, confidence, floating)
    }

    private fun estimateFloorBackground(
        pixels: IntArray,
        alpha: FloatArray,
        width: Int,
        height: Int,
        left: Int,
        right: Int,
        bottom: Int,
    ): FloatArray {
        var rs = 0f
        var gs = 0f
        var bs = 0f
        var count = 0
        val y0 = (bottom - height * 0.008f).roundToInt().coerceIn(0, height - 1)
        val y1 = (bottom + height * 0.065f).roundToInt().coerceIn(0, height - 1)
        val margin = max(8, (right - left + 1) / 10)
        for (y in y0..y1) {
            for (x in max(0, left - margin)..min(width - 1, right + margin)) {
                val i = y * width + x
                if (alpha[i] > 0.08f) continue
                val c = pixels[i]
                rs += Color.red(c)
                gs += Color.green(c)
                bs += Color.blue(c)
                count++
            }
        }
        if (count < 20) return estimateCornerBackground(pixels, width, height)
        return floatArrayOf(rs / count, gs / count, bs / count)
    }

    private fun composeCatalogShot(
        sourceWidth: Int,
        sourceHeight: Int,
        cutout: Bitmap,
        shadow: Bitmap?,
        bounds: RectF,
    ): Bitmap {
        val output = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                0f,
                sourceHeight.toFloat(),
                Color.rgb(250, 247, 242),
                Color.rgb(244, 240, 235),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat(), background)

        val objectW = bounds.width().coerceAtLeast(1f)
        val objectH = bounds.height().coerceAtLeast(1f)
        val uniformScale = min(sourceWidth * 0.82f / objectW, sourceHeight * 0.80f / objectH)
            .coerceIn(0.94f, 1.08f)
        val desiredBottom = sourceHeight * 0.90f
        val desiredCenterX = sourceWidth * 0.50f
        val matrix = Matrix().apply {
            postScale(uniformScale, uniformScale)
            val scaledCenterX = bounds.centerX() * uniformScale
            val scaledBottom = bounds.bottom * uniformScale
            postTranslate(desiredCenterX - scaledCenterX, desiredBottom - scaledBottom)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        shadow?.let { canvas.drawBitmap(it, matrix, paint) }
        canvas.drawBitmap(cutout, matrix, paint)
        return output
    }

    private data class CompositeReport(val accepted: Boolean)

    private fun inspectComposite(output: Bitmap, cutout: Bitmap, bounds: RectF): CompositeReport {
        // The final gate is deliberately conservative: excessive near-white clipping
        // around the object or a suspiciously small object causes the optional shadow
        // to be dropped, while product pixels remain untouched.
        val step = max(1, max(output.width, output.height) / 480)
        var clipped = 0
        var count = 0
        for (y in 0 until output.height step step) {
            for (x in 0 until output.width step step) {
                val c = output.getPixel(x, y)
                if (Color.red(c) >= 253 && Color.green(c) >= 253 && Color.blue(c) >= 252) clipped++
                count++
            }
        }
        val clipRatio = clipped.toFloat() / max(1, count)
        val usefulObject = bounds.width() > cutout.width * 0.08f && bounds.height() > cutout.height * 0.12f
        return CompositeReport(usefulObject && clipRatio < 0.88f)
    }

    private suspend fun saveConservativeFallback(
        source: Bitmap,
        originalPath: String,
        memoryWarning: String?,
        reason: String,
    ): ProcessResult {
        val file = withContext(Dispatchers.IO) { saveJpeg(originalPath, source) }
        return ProcessResult(
            resultPath = file.absolutePath,
            backgroundReplaced = false,
            warning = listOfNotNull(memoryWarning, reason).joinToString(" "),
        )
    }

    private fun saveJpeg(originalPath: String, bitmap: Bitmap): File {
        val original = File(originalPath)
        val result = File(original.parentFile, "result.jpg")
        val temp = File(original.parentFile, "result.v22.tmp.jpg")
        FileOutputStream(temp).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 99, output)) {
                "No se pudo guardar el resultado."
            }
        }
        if (result.exists()) result.delete()
        if (!temp.renameTo(result)) {
            temp.copyTo(result, overwrite = true)
            temp.delete()
        }
        return result
    }

    private fun estimateCornerBackground(pixels: IntArray, width: Int, height: Int): FloatArray {
        val sx = max(2, width / 12)
        val sy = max(2, height / 12)
        val stepX = max(1, sx / 5)
        val stepY = max(1, sy / 5)
        var rs = 0L
        var gs = 0L
        var bs = 0L
        var count = 0L
        fun sample(x0: Int, y0: Int, x1: Int, y1: Int) {
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val c = pixels[y * width + x]
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
        sample(width - sx, 0, width, sy)
        sample(0, height - sy, sx, height)
        sample(width - sx, height - sy, width, height)
        if (count == 0L) return floatArrayOf(245f, 243f, 238f)
        return floatArrayOf(rs.toFloat() / count, gs.toFloat() / count, bs.toFloat() / count)
    }

    private fun colorDistance(color: Int, bg: FloatArray): Float =
        abs(Color.red(color) - bg[0]) + abs(Color.green(color) - bg[1]) + abs(Color.blue(color) - bg[2])

    private fun localGradient(pixels: IntArray, width: Int, x: Int, y: Int): Float {
        val center = luma(pixels[y * width + x])
        var strongest = 0f
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            strongest = max(strongest, abs(center - luma(pixels[(y + dy) * width + x + dx])))
        }
        return strongest
    }

    private fun luma(color: Int): Float =
        0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)
}

/** Compatibility with all prior UI revisions. */
object LocalEnhancementEngine {
    suspend fun process(
        context: Context,
        originalPath: String,
        settings: ProcessSettings,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): ProcessResult = CatalogPipelineV22.process(
        context = context,
        originalPath = originalPath,
        settings = settings,
        onProgress = { progress, stage -> withContext(Dispatchers.Main) { onProgress(progress, stage) } },
    )
}

/** Compatibility with v0.6.x fidelity-first UI revisions. */
object HighFidelityEnhancementEngine {
    suspend fun process(
        context: Context,
        originalPath: String,
        settings: ProcessSettings,
    ): ProcessResult = CatalogPipelineV22.process(
        context = context,
        originalPath = originalPath,
        settings = settings,
        onProgress = { _, _ -> },
    )
}
