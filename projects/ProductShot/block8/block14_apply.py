#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()

def read(rel):
    return (root / rel).read_text(encoding='utf-8')

def write(rel, text):
    (root / rel).write_text(text, encoding='utf-8')

def rep(rel, old, new, count=1):
    s = read(rel)
    if old not in s:
        raise SystemExit(f'missing fragment in {rel}: {old[:120]!r}')
    write(rel, s.replace(old, new, count))

# Version identity.
rep('app/build.gradle.kts', 'versionCode = 59', 'versionCode = 60')
rep('app/build.gradle.kts', 'versionName = "0.9.13-block2-camera-visual-foundation"', 'versionName = "0.9.14-block3-premium-compositor"')

engine_rel = 'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt'
s = read(engine_rel)

# Pass object bounds into source-anchored photometric enhancement.
s = s.replace(
    'enhanced = enhanceObjectConservatively(source, alpha, quality, settings)',
    'enhanced = enhanceObjectConservatively(source, alpha, maskReport.bounds, quality, settings)',
    1,
)
s = s.replace(
    '''    private fun enhanceObjectConservatively(\n        source: Bitmap,\n        alpha: FloatArray,\n        quality: InputQuality,\n        settings: ProcessSettings,\n    ): Bitmap {''',
    '''    private fun enhanceObjectConservatively(\n        source: Bitmap,\n        alpha: FloatArray,\n        bounds: RectF,\n        quality: InputQuality,\n        settings: ProcessSettings,\n    ): Bitmap {''',
    1,
)

needle = '''                if (wood) {\n                    var woodLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b\n                    if (woodLuma > 135f) {'''
if needle not in s:
    raise SystemExit('wood block anchor missing')
anchor = '''                // Never permit a large photometric jump at a real structural edge.\n                val candidateLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b'''
insert = '''                // PREMIUM RELIGHT — SOURCE ANCHORED: a broad virtual softbox from\n                // upper-left plus a very mild lower-right density falloff. The same\n                // luminance delta is applied to R/G/B, so hue/material identity is\n                // preserved and no geometry or texture is invented. Structural edges\n                // receive only a fraction of the relight through transformAllowance.\n                val nx = ((x - bounds.left) / bounds.width().coerceAtLeast(1f)).coerceIn(0f, 1f)\n                val ny = ((y - bounds.top) / bounds.height().coerceAtLeast(1f)).coerceIn(0f, 1f)\n                val softboxField = (0.66f * (1f - nx) + 0.34f * (1f - ny) - 0.50f)\n                val relightDelta = (softboxField * 9.0f).coerceIn(-4.2f, 4.2f)\n                val relightAllowance = when {\n                    structuralGradient >= 34f -> 0.25f\n                    structuralGradient >= 24f -> 0.42f\n                    structuralGradient >= 15f -> 0.68f\n                    else -> 1f\n                } * a.coerceIn(0f, 1f)\n                r += relightDelta * relightAllowance\n                g += relightDelta * relightAllowance\n                b += relightDelta * relightAllowance\n\n                // Never permit a large photometric jump at a real structural edge.\n                val candidateLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b'''
if anchor not in s:
    raise SystemExit('structure lock anchor missing')
s = s.replace(anchor, insert, 1)

old = '''            if (a in 0.42f..0.94f) {\n                // Avoid dividing uncertain fringe pixels by a tiny alpha. That was a\n                // source of bright/colored halos in high-contrast furniture edges.\n                r = ((r - bg[0] * (1f - a)) / max(a, 0.58f)).coerceIn(0f, 255f)\n                g = ((g - bg[1] * (1f - a)) / max(a, 0.58f)).coerceIn(0f, 255f)\n                b = ((b - bg[2] * (1f - a)) / max(a, 0.58f)).coerceIn(0f, 255f)\n            }\n            out[i] = Color.argb('''
new = '''            if (a in 0.42f..0.94f) {\n                // Avoid dividing uncertain fringe pixels by a tiny alpha. That was a\n                // source of bright/colored halos in high-contrast furniture edges.\n                r = ((r - bg[0] * (1f - a)) / max(a, 0.58f)).coerceIn(0f, 255f)\n                g = ((g - bg[1] * (1f - a)) / max(a, 0.58f)).coerceIn(0f, 255f)\n                b = ((b - bg[2] * (1f - a)) / max(a, 0.58f)).coerceIn(0f, 255f)\n            }\n\n            // EDGE COLOR ANCHOR: suppress pale/colored cutout halos without blurring\n            // or growing the mask. Only RGB on already semi-transparent pixels is\n            // pulled slightly toward neighbouring high-confidence source pixels.\n            if (a in 0.08f..0.72f) {\n                val x = i % width\n                val y = i / width\n                if (x in 1 until width - 1 && y in 1 until height - 1) {\n                    var nr = 0f\n                    var ng = 0f\n                    var nb = 0f\n                    var strong = 0\n                    for (dy in -1..1) for (dx in -1..1) {\n                        if (dx == 0 && dy == 0) continue\n                        val ni = (y + dy) * width + x + dx\n                        if (alpha[ni] < 0.80f) continue\n                        val nc = src[ni]\n                        nr += Color.red(nc)\n                        ng += Color.green(nc)\n                        nb += Color.blue(nc)\n                        strong++\n                    }\n                    if (strong > 0) {\n                        nr /= strong\n                        ng /= strong\n                        nb /= strong\n                        val anchorWeight = (((0.72f - a) / 0.64f) * 0.24f).coerceIn(0f, 0.24f)\n                        r += (nr - r) * anchorWeight\n                        g += (ng - g) * anchorWeight\n                        b += (nb - b) * anchorWeight\n                    }\n                }\n            }\n            out[i] = Color.argb('''
if old not in s:
    raise SystemExit('cutout decontamination anchor missing')
s = s.replace(old, new, 1)

old = '''            for (dy in 0 until depth) {\n                val y = foot + 1 + dy\n                if (y >= height) break\n                val spread = min(12, 2 + dy / 3)\n                val falloff = (1f - dy.toFloat() / depth).coerceIn(0f, 1f)\n                for (dx in -spread..spread) {\n                    val xx = x + dx\n                    if (xx !in 0 until width) continue\n                    val i = y * width + xx\n                    if (alpha[i] > 0.10f) continue\n                    val horizontal = (1f - abs(dx).toFloat() / (spread + 1f)).coerceIn(0f, 1f)\n                    val contact = falloff * falloff * horizontal * 0.50f\n                    val ambient = falloff * horizontal * 0.13f\n                    mask[i] = max(mask[i], contact + ambient)\n                }\n            }'''
new = '''            for (dy in 0 until depth) {\n                val y = foot + 1 + dy\n                if (y >= height) break\n                val spread = min(12, 2 + dy / 3)\n                val falloff = (1f - dy.toFloat() / depth).coerceIn(0f, 1f)\n                for (dx in -spread..spread) {\n                    val xx = x + dx\n                    if (xx !in 0 until width) continue\n                    val i = y * width + xx\n                    if (alpha[i] > 0.10f) continue\n                    val horizontal = (1f - abs(dx).toFloat() / (spread + 1f)).coerceIn(0f, 1f)\n                    val contact = falloff * falloff * horizontal * 0.50f\n                    val ambient = falloff * horizontal * 0.13f\n                    mask[i] = max(mask[i], contact + ambient)\n                }\n            }\n\n            // A second, weaker cast is projected from the SAME detected support\n            // coordinate. This produces floor depth from real feet/base contacts,\n            // never from a generic oval/drop-shadow primitive.\n            val castDepth = (bounds.height() * 0.095f).roundToInt().coerceIn(14, 72)\n            for (dy in 1 until castDepth) {\n                val y = foot + 1 + dy\n                if (y >= height) break\n                val shift = (dy * 0.42f).roundToInt()\n                val spread = min(16, 2 + dy / 5)\n                val falloff = (1f - dy.toFloat() / castDepth).coerceIn(0f, 1f)\n                for (dx in -spread..spread) {\n                    val xx = x + shift + dx\n                    if (xx !in 0 until width) continue\n                    val i = y * width + xx\n                    if (alpha[i] > 0.10f) continue\n                    val horizontal = (1f - abs(dx).toFloat() / (spread + 1f)).coerceIn(0f, 1f)\n                    val cast = falloff * falloff * horizontal * 0.16f\n                    mask[i] = max(mask[i], cast)\n                }\n            }'''
if old not in s:
    raise SystemExit('support shadow anchor missing')
s = s.replace(old, new, 1)

s = s.replace(
    'val a = (mask[i] * 126f).roundToInt().coerceIn(0, 64)',
    'val a = (mask[i] * 132f).roundToInt().coerceIn(0, 72)',
    1,
)

old = '''        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)\n        shadow?.let { canvas.drawBitmap(it, matrix, paint) }\n        canvas.drawBitmap(cutout, matrix, paint)\n        return output'''
new = '''        // Background-only separation light. It never touches product pixels; it\n        // simply gives the locked cutout the broad softbox halo expected from a\n        // premium catalog set.\n        val scaledObjectW = objectW * uniformScale\n        val scaledObjectH = objectH * uniformScale\n        val separationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {\n            shader = android.graphics.RadialGradient(\n                desiredCenterX - scaledObjectW * 0.08f,\n                desiredBottom - scaledObjectH * 0.54f,\n                max(scaledObjectW, scaledObjectH) * 0.78f,\n                intArrayOf(Color.argb(18, 255, 252, 247), Color.TRANSPARENT),\n                floatArrayOf(0f, 1f),\n                Shader.TileMode.CLAMP,\n            )\n        }\n        canvas.drawRect(0f, 0f, backgroundPlate.width.toFloat(), backgroundPlate.height.toFloat(), separationPaint)\n\n        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)\n        shadow?.let { canvas.drawBitmap(it, matrix, paint) }\n        canvas.drawBitmap(cutout, matrix, paint)\n        return output'''
if old not in s:
    raise SystemExit('compose paint anchor missing')
s = s.replace(old, new, 1)

write(engine_rel, s)

gen_rel = 'app/src/main/java/com/tiagocrispo/furnitureshot/processing/DirectMediaPipeBackgroundGenerator.kt'
g = read(gen_rel)
g = g.replace('import kotlin.math.max\n', 'import kotlin.math.abs\nimport kotlin.math.max\nimport kotlin.math.min\n', 1)
old = '''            var acceptedPlate: Bitmap? = null\n            var lastValidationReason: String? = null\n            val promptVariants = buildPromptVariants(style)\n            val baseSeed = stableSeed(style, manifest.providerName)\n\n            for (attempt in 0 until MAX_ATTEMPTS) {'''
new = '''            var acceptedPlate: Bitmap? = null\n            var acceptedScore = Float.NEGATIVE_INFINITY\n            var lastValidationReason: String? = null\n            val promptVariants = buildPromptVariants(style)\n            val baseSeed = stableSeed(style, manifest.providerName)\n\n            for (attempt in 0 until MAX_ATTEMPTS) {'''
if old not in g:
    raise SystemExit('generator acceptedPlate anchor missing')
g = g.replace(old, new, 1)
old = '''                val validation = StudioBackgroundPlateFactory.validate(fitted!!)\n                if (validation.accepted) {\n                    acceptedPlate = fitted\n                    fitted = null\n                    break\n                }\n                lastValidationReason = validation.reason\n            }'''
new = '''                val validation = StudioBackgroundPlateFactory.validate(fitted!!)\n                if (validation.accepted) {\n                    val candidateScore = scoreCommercialPlate(fitted!!)\n                    if (candidateScore > acceptedScore) {\n                        acceptedPlate?.let { if (!it.isRecycled) it.recycle() }\n                        acceptedPlate = fitted\n                        acceptedScore = candidateScore\n                        fitted = null\n                    } else {\n                        fitted?.let { if (!it.isRecycled) it.recycle() }\n                        fitted = null\n                    }\n                    // Excellent plates stop early; merely valid plates keep competing\n                    // so the user gets the best background rather than the first one.\n                    if (acceptedScore >= 82f) break\n                } else {\n                    lastValidationReason = validation.reason\n                }\n            }'''
if old not in g:
    raise SystemExit('generator validation anchor missing')
g = g.replace(old, new, 1)

anchor = '''    private fun stableSeed(style: CatalogReferenceStyle, provider: String): Int {'''
score_fun = '''    private fun scoreCommercialPlate(bitmap: Bitmap): Float {\n        val step = max(1, max(bitmap.width, bitmap.height) / 220)\n        var samples = 0\n        var lumaSum = 0f\n        var satSum = 0f\n        var wallLuma = 0f\n        var wallCount = 0\n        var floorLuma = 0f\n        var floorCount = 0\n        var nearWhite = 0\n        var centerEdges = 0\n        var centerSamples = 0\n        val wallLimit = (bitmap.height * 0.66f).toInt()\n        val floorStart = (bitmap.height * 0.77f).toInt()\n        val centerLeft = (bitmap.width * 0.18f).toInt()\n        val centerRight = (bitmap.width * 0.82f).toInt()\n        val centerTop = (bitmap.height * 0.15f).toInt()\n        val centerBottom = (bitmap.height * 0.68f).toInt()\n\n        for (y in 0 until bitmap.height step step) {\n            for (x in 0 until bitmap.width step step) {\n                val c = bitmap.getPixel(x, y)\n                val r = Color.red(c).toFloat()\n                val gg = Color.green(c).toFloat()\n                val b = Color.blue(c).toFloat()\n                val luma = 0.2126f * r + 0.7152f * gg + 0.0722f * b\n                val sat = max(r, max(gg, b)) - min(r, min(gg, b))\n                lumaSum += luma\n                satSum += sat\n                if (luma > 249f) nearWhite++\n                if (y <= wallLimit) { wallLuma += luma; wallCount++ }\n                if (y >= floorStart) { floorLuma += luma; floorCount++ }\n                if (x in centerLeft..centerRight && y in centerTop..centerBottom && x + step < bitmap.width) {\n                    val n = bitmap.getPixel(x + step, y)\n                    val delta = abs(r - Color.red(n).toFloat()) + abs(gg - Color.green(n).toFloat()) + abs(b - Color.blue(n).toFloat())\n                    if (delta > 46f) centerEdges++\n                    centerSamples++\n                }\n                samples++\n            }\n        }\n        if (samples == 0) return -100f\n        val mean = lumaSum / samples\n        val meanSat = satSum / samples\n        val wall = wallLuma / max(1, wallCount)\n        val floor = floorLuma / max(1, floorCount)\n        val floorDepth = wall - floor\n        val whiteRatio = nearWhite.toFloat() / samples\n        val edgeRatio = centerEdges.toFloat() / max(1, centerSamples)\n\n        val exposureScore = (30f - abs(mean - 210f) * 0.70f).coerceIn(0f, 30f)\n        val depthScore = (24f - abs(floorDepth - 13f) * 1.15f).coerceIn(0f, 24f)\n        val chromaScore = (20f - abs(meanSat - 12f) * 0.85f).coerceIn(0f, 20f)\n        val whiteScore = ((0.30f - whiteRatio) / 0.30f * 14f).coerceIn(0f, 14f)\n        val simplicityScore = (12f - edgeRatio * 110f).coerceIn(0f, 12f)\n        return exposureScore + depthScore + chromaScore + whiteScore + simplicityScore\n    }\n\n'''
if anchor not in g:
    raise SystemExit('stableSeed anchor missing')
g = g.replace(anchor, score_fun + anchor, 1)
write(gen_rel, g)

val_rel = 'scripts/static_validate.py'
v = read(val_rel)
v = v.replace("req('versionCode = 59' in build, 'versionCode 59 missing')", "req('versionCode = 60' in build, 'versionCode 60 missing')")
v = v.replace("req('versionName = \"0.9.13-block2-camera-visual-foundation\"' in build, 'versionName mismatch')", "req('versionName = \"0.9.14-block3-premium-compositor\"' in build, 'versionName mismatch')")
v = v.replace("req('contact + ambient' in engine and 'coerceIn(0, 64)' in engine, 'stronger support-derived grounding shadow missing')", "req('contact + ambient' in engine and 'coerceIn(0, 72)' in engine, 'stronger support-derived grounding shadow missing')")
marker = "print('PRODUCTSHOT_BLOCK13_CAMERA_VISUAL_STATIC_VALIDATION_OK')"
if marker not in v:
    raise SystemExit('block13 validator marker missing')
extra = '''req('scoreCommercialPlate' in generator and 'acceptedScore >= 82f' in generator, 'best-of-three commercial plate scoring missing')\nreq('PREMIUM RELIGHT' in engine and 'softboxField' in engine, 'source-anchored premium relight missing')\nreq('EDGE COLOR ANCHOR' in engine and 'anchorWeight' in engine, 'fringe color anchoring missing')\nreq('castDepth' in engine and 'val cast = falloff * falloff * horizontal * 0.16f' in engine, 'support-derived cast shadow missing')\nreq('Background-only separation light' in engine and 'separationPaint' in engine, 'background-only subject separation missing')\nprint('PRODUCTSHOT_BLOCK14_PREMIUM_COMPOSITOR_STATIC_VALIDATION_OK')'''
v = v.replace(marker, extra, 1)
write(val_rel, v)

print('PRODUCTSHOT_BLOCK14_APPLIED')
