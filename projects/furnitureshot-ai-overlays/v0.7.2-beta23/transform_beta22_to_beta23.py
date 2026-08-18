from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()


def replace_once(old: str, new: str, name: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{name}: expected 1 match, got {count}")
    text = text.replace(old, new, 1)


def replace_between(start: str, end: str, replacement: str, name: str) -> None:
    global text
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"{name}: start marker not found")
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f"{name}: end marker not found")
    text = text[:a] + replacement + text[b:]


replace_once(
    "private const val SEGMENTATION_LONG_EDGE = 1024",
    "private const val SEGMENTATION_LONG_EDGE = 1536",
    "segmentation resolution",
)

replace_once(
'''    private fun chooseProcessingDimension(context: Context): Int {
        val manager = context.getSystemService(ActivityManager::class.java)
        return when (manager?.memoryClass ?: 256) {
            in 0..192 -> 2200
            in 193..256 -> 2800
            in 257..384 -> 3200
            else -> 3840
        }
    }
''',
'''    private fun chooseProcessingDimension(context: Context): Int {
        val manager = context.getSystemService(ActivityManager::class.java)
        // QUALITY MAX: preserve substantially more of the camera file while keeping
        // a conservative ceiling for mid-range Android devices.
        return when (manager?.memoryClass ?: 256) {
            in 0..192 -> 2400
            in 193..256 -> 3000
            in 257..384 -> 3400
            in 385..512 -> 3840
            else -> 4096
        }
    }
''',
    "processing dimension",
)

replace_once(
    "alpha[i] = (center * 0.70f + avg * 0.30f).coerceIn(0f, 1f)",
    "alpha[i] = (center * 0.86f + avg * 0.14f).coerceIn(0f, 1f)",
    "edge feather",
)

enhancement = r'''    private fun enhanceObjectConservatively(
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

        // Reference-match diagnosis: beta22 was too high-key and its detail gain was
        // so small that real wood grain barely survived perceptually. beta23 keeps
        // every detail signal anchored to ORIGINAL neighbouring pixels and applies a
        // modest S-curve instead of lifting the entire shadow range.
        val targetLuma = 142f
        val exposure = (1f + (targetLuma - quality.meanLuma) / 255f * 0.060f)
            .coerceIn(0.94f, if (quality.highlightClip > 0.020f) 0.995f else 1.015f)
        val saturation = (settings.saturation + 0.006f).coerceIn(0.995f, 1.026f)
        val noiseGuard = if (quality.noiseProxy > 5.5f) 0.52f else if (quality.noiseProxy > 3.8f) 0.72f else 1f
        val requestedDetail = settings.detailStrength.coerceIn(0f, 0.10f)

        for (y in 2 until height - 2) {
            for (x in 2 until width - 2) {
                val i = y * width + x
                val a = alpha[i]
                if (a < 0.16f) continue
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

                // A second, wider cross gives us recoverable medium-frequency grain
                // without fabricating a texture map.
                var wideR = r0
                var wideG = g0
                var wideB = b0
                var wn = 1
                for (d in intArrayOf(-2, 2)) {
                    var p = src[y * width + x + d]
                    wideR += Color.red(p); wideG += Color.green(p); wideB += Color.blue(p); wn++
                    p = src[(y + d) * width + x]
                    wideR += Color.red(p); wideG += Color.green(p); wideB += Color.blue(p); wn++
                }
                wideR /= wn; wideG /= wn; wideB /= wn

                val originalLuma = luma(c)
                val structuralGradient = max(
                    abs(luma(src[y * width + x + 1]) - luma(src[y * width + x - 1])),
                    abs(luma(src[(y + 1) * width + x]) - luma(src[(y - 1) * width + x])),
                )
                val wood = looksLikeWood(r0, g0, b0)
                val detailGain = ((if (wood) 0.22f else 0.12f) + requestedDetail * (if (wood) 1.55f else 0.85f)) * noiseGuard
                val mediumGain = (if (wood) 0.085f else 0.040f) * noiseGuard

                // STRUCTURE LOCK: structural edges are not repainted. They receive
                // only a fraction of the tonal/detail transform and remain anchored
                // to the exact source edge pixels.
                val transformAllowance = when {
                    structuralGradient >= 34f -> 0.22f
                    structuralGradient >= 24f -> 0.42f
                    structuralGradient >= 15f -> 0.68f
                    else -> 1f
                } * a.coerceIn(0f, 1f)

                var r = r0 + (r0 - localR).coerceIn(-14f, 14f) * detailGain +
                    (r0 - wideR).coerceIn(-18f, 18f) * mediumGain
                var g = g0 + (g0 - localG).coerceIn(-14f, 14f) * detailGain +
                    (g0 - wideG).coerceIn(-18f, 18f) * mediumGain
                var b = b0 + (b0 - localB).coerceIn(-14f, 14f) * detailGain +
                    (b0 - wideB).coerceIn(-18f, 18f) * mediumGain

                r = toneChannel(r / 255f, exposure) * 255f
                g = toneChannel(g / 255f, exposure) * 255f
                b = toneChannel(b / 255f, exposure) * 255f

                val gray = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r = gray + (r - gray) * saturation
                g = gray + (g - gray) * saturation
                b = gray + (b - gray) * saturation

                // Never permit a large photometric jump at a real structural edge.
                val candidateLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val excessiveToneShift = abs(candidateLuma - originalLuma) > 20f
                val allowance = if (excessiveToneShift) transformAllowance * 0.55f else transformAllowance
                r = r0 + (r - r0) * allowance
                g = g0 + (g - g0) * allowance
                b = b0 + (b - b0) * allowance

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

'''
replace_between(
    "    private fun enhanceObjectConservatively(",
    "    private fun toneChannel(",
    enhancement,
    "material/detail recovery",
)

replace_between(
    "    private fun toneChannel(",
    "    private fun looksLikeWood(",
    r'''    private fun toneChannel(value: Float, exposure: Float): Float {
        var v = (value * exposure).coerceIn(0f, 1f)
        // Gentle density/S-curve: recover depth without the beta22 shadow lift and
        // without a crunchy HDR/clarity look.
        v = ((v - 0.5f) * 1.035f + 0.5f).coerceIn(0f, 1f)
        if (v in 0.10f..0.78f) {
            v = (v * 0.985f).coerceIn(0f, 1f)
        }
        if (v > 0.86f) v = 0.86f + (v - 0.86f) * 0.70f
        return v.coerceIn(0f, 1f)
    }

''',
    "smart exposure curve",
)

replace_between(
    "    private data class FidelityReport(",
    "    private fun extractCutoutWithDecontamination(",
    r'''    private data class FidelityReport(
        val accepted: Boolean,
        val edgeRatio: Float,
        val colorDelta: Float,
        val novelEdgeRatio: Float,
        val doubleEdgeRatio: Float,
        val highlightClip: Float,
        val shadowClip: Float,
    )

    private fun compareFidelity(original: Bitmap, candidate: Bitmap, alpha: FloatArray): FidelityReport {
        val step = max(1, max(original.width, original.height) / 560)
        var edgeOriginal = 0f
        var edgeCandidate = 0f
        var colorDelta = 0f
        var count = 0
        var novel = 0
        var doubleEdges = 0
        var structural = 0
        var highlights = 0
        var shadows = 0
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
                val oe = max(
                    abs(luma(original.getPixel(x + 1, y)) - luma(original.getPixel(x - 1, y))),
                    abs(luma(original.getPixel(x, y + 1)) - luma(original.getPixel(x, y - 1))),
                )
                val ce = max(
                    abs(luma(candidate.getPixel(x + 1, y)) - luma(candidate.getPixel(x - 1, y))),
                    abs(luma(candidate.getPixel(x, y + 1)) - luma(candidate.getPixel(x, y - 1))),
                )
                edgeOriginal += oe
                edgeCandidate += ce
                if (oe < 5.5f && ce > 17f) novel++
                if (oe > 10f) {
                    structural++
                    if (ce > oe * 1.70f + 3f) doubleEdges++
                }
                val cl = luma(c)
                if (cl >= 249f) highlights++
                if (cl <= 7f) shadows++
                count++
            }
        }
        if (count == 0) return FidelityReport(false, 0f, 1f, 1f, 1f, 1f, 1f)
        val edgeRatio = edgeCandidate / edgeOriginal.coerceAtLeast(0.001f)
        val delta = colorDelta / count
        val novelRatio = novel.toFloat() / count
        val doubleRatio = doubleEdges.toFloat() / max(1, structural)
        val hi = highlights.toFloat() / count
        val lo = shadows.toFloat() / count
        val accepted = edgeRatio in 0.82f..1.22f &&
            delta < 0.065f && novelRatio < 0.12f && doubleRatio < 0.11f &&
            hi < 0.035f && lo < 0.030f
        return FidelityReport(accepted, edgeRatio, delta, novelRatio, doubleRatio, hi, lo)
    }

''',
    "artifact/fidelity detector",
)

replace_once(
'''            if (a in 0.08f..0.96f) {
                val safeA = max(0.22f, a)
                r = ((r - bg[0] * (1f - safeA)) / safeA).coerceIn(0f, 255f)
                g = ((g - bg[1] * (1f - safeA)) / safeA).coerceIn(0f, 255f)
                b = ((b - bg[2] * (1f - safeA)) / safeA).coerceIn(0f, 255f)
            }
''',
'''            if (a in 0.30f..0.96f) {
                // Avoid dividing uncertain fringe pixels by a tiny alpha. That was a
                // source of bright/colored halos in high-contrast furniture edges.
                r = ((r - bg[0] * (1f - a)) / a).coerceIn(0f, 255f)
                g = ((g - bg[1] * (1f - a)) / a).coerceIn(0f, 255f)
                b = ((b - bg[2] * (1f - a)) / a).coerceIn(0f, 255f)
            }
''',
    "edge decontamination",
)

replace_once(
'''            naturalShadow = if (
                shadowReport.confidence >= 0.34f &&
                shadowReport.floatingScore <= 0.14f
            ) shadowReport.bitmap else null
            if (naturalShadow !== shadowReport.bitmap) {
                shadowReport.bitmap?.let { if (!it.isRecycled) it.recycle() }
            }
''',
'''            naturalShadow = if (
                shadowReport.confidence >= 0.34f &&
                shadowReport.floatingScore <= 0.14f
            ) shadowReport.bitmap else null
            if (naturalShadow !== shadowReport.bitmap) {
                shadowReport.bitmap?.let { if (!it.isRecycled) it.recycle() }
            }
            // If the original floor gives us no trustworthy broad shadow, create only
            // a support-derived contact occlusion. No ellipse/blob/drop-shadow shape.
            if (naturalShadow == null) {
                naturalShadow = buildSupportContactOcclusion(alpha, maskReport.bounds, source.width, source.height)
            }
''',
    "contact shadow fallback",
)

contact_function = r'''    private fun buildSupportContactOcclusion(
        alpha: FloatArray,
        bounds: RectF,
        width: Int,
        height: Int,
    ): Bitmap? {
        val left = bounds.left.roundToInt().coerceIn(0, width - 1)
        val right = bounds.right.roundToInt().coerceIn(0, width - 1)
        val top = bounds.top.roundToInt().coerceIn(0, height - 1)
        val bottom = bounds.bottom.roundToInt().coerceIn(0, height - 1)
        val supportY = IntArray(width) { -1 }
        var globalBottom = -1
        for (x in left..right) {
            for (y in min(height - 1, bottom + 1) downTo top) {
                if (alpha[y * width + x] >= 0.62f) {
                    supportY[x] = y
                    globalBottom = max(globalBottom, y)
                    break
                }
            }
        }
        if (globalBottom < 0) return null
        val groundBand = (bounds.height() * 0.014f).roundToInt().coerceIn(2, 10)
        val depth = (bounds.height() * 0.012f).roundToInt().coerceIn(3, 13)
        val mask = FloatArray(width * height)
        var contacts = 0
        for (x in left..right) {
            val foot = supportY[x]
            if (foot < globalBottom - groundBand) continue
            contacts++
            for (dy in 0 until depth) {
                val y = foot + 1 + dy
                if (y >= height) break
                val spread = min(3, 1 + dy / 4)
                val falloff = 1f - dy.toFloat() / depth
                for (dx in -spread..spread) {
                    val xx = x + dx
                    if (xx !in 0 until width) continue
                    val i = y * width + xx
                    if (alpha[i] > 0.10f) continue
                    val horizontal = 1f - abs(dx).toFloat() / (spread + 1f)
                    mask[i] = max(mask[i], falloff * falloff * horizontal * 0.34f)
                }
            }
        }
        if (contacts < 3) return null
        // Tiny separable blur keeps the contact physically soft while remaining
        // attached to the real support coordinates.
        val temp = mask.clone()
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            mask[i] = (temp[i] * 0.56f + (temp[i - 1] + temp[i + 1]) * 0.14f +
                (temp[i - width] + temp[i + width]) * 0.08f).coerceIn(0f, 0.38f)
        }
        val pixels = IntArray(mask.size)
        for (i in mask.indices) {
            val a = (mask[i] * 120f).roundToInt().coerceIn(0, 42)
            pixels[i] = if (a == 0) Color.TRANSPARENT else Color.argb(a, 82, 77, 72)
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

'''
marker = "    private fun estimateFloorBackground("
pos = text.find(marker)
if pos < 0:
    raise SystemExit("contact occlusion insertion marker missing")
text = text[:pos] + contact_function + text[pos:]

replace_once(
    "Color.rgb(250, 247, 242),\n                Color.rgb(244, 240, 235),",
    "Color.rgb(248, 244, 238),\n                Color.rgb(240, 235, 228),",
    "studio background density",
)
replace_once(
'''        val uniformScale = min(sourceWidth * 0.82f / objectW, sourceHeight * 0.80f / objectH)
            .coerceIn(0.94f, 1.08f)
''',
'''        val uniformScale = min(sourceWidth * 0.86f / objectW, sourceHeight * 0.84f / objectH)
            // QUALITY MAX avoids meaningful digital enlargement. Preserve camera
            // microtexture instead of scaling a processed cutout by 8%.
            .coerceIn(0.95f, 1.015f)
''',
    "geometry-safe placement",
)

path.write_text(text)
print("beta23 transform applied", path, len(text))
