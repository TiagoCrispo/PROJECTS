from pathlib import Path
import sys

engine = Path(sys.argv[1])
prompt = Path(sys.argv[2])
text = engine.read_text()


def replace_once(old: str, new: str, label: str):
    global text
    if old not in text:
        raise SystemExit(f'missing engine anchor for {label}')
    text = text.replace(old, new, 1)

# Reference-derived diagnosis for the current real SOURCE vs QUALITY REFERENCE:
# the source wood is materially brighter/flatter than the premium reference.
# Darken wood conservatively while increasing source-anchored texture contrast.
replace_once('val targetLuma = 136f', 'val targetLuma = 130f', 'target luma')
replace_once(
    'val exposure = (1f + (targetLuma - quality.meanLuma) / 255f * 0.052f)\n            .coerceIn(0.93f, if (quality.highlightClip > 0.018f) 0.992f else 1.010f)',
    'val exposure = (1f + (targetLuma - quality.meanLuma) / 255f * 0.048f)\n            .coerceIn(0.92f, if (quality.highlightClip > 0.018f) 0.990f else 1.005f)',
    'exposure range',
)
replace_once(
    'val saturation = (settings.saturation + 0.004f).coerceIn(0.995f, 1.022f)',
    'val saturation = (settings.saturation + 0.002f).coerceIn(0.995f, 1.018f)',
    'saturation guard',
)
replace_once(
    'val detailGain = ((if (wood) 0.22f else 0.12f) + requestedDetail * (if (wood) 1.55f else 0.85f)) * noiseGuard\n                val mediumGain = (if (wood) 0.085f else 0.040f) * noiseGuard',
    '''val residualEnergy = (abs(r0 - localR) + abs(g0 - localG) + abs(b0 - localB)) / 3f\n                val textureConfidence = ((residualEnergy - 0.8f) / 8.5f).coerceIn(0.45f, 1f)\n                val detailGain = (((if (wood) 0.235f else 0.12f) +\n                    requestedDetail * (if (wood) 1.62f else 0.85f)) *\n                    noiseGuard * textureConfidence)\n                val mediumGain = (if (wood) 0.095f else 0.040f) * noiseGuard * textureConfidence''',
    'texture confidence',
)
replace_once(
    '''                val gray = 0.2126f * r + 0.7152f * g + 0.0722f * b\n                r = gray + (r - gray) * saturation\n                g = gray + (g - gray) * saturation\n                b = gray + (b - gray) * saturation\n\n                // Never permit a large photometric jump at a real structural edge.''',
    '''                val gray = 0.2126f * r + 0.7152f * g + 0.0722f * b\n                r = gray + (r - gray) * saturation\n                g = gray + (g - gray) * saturation\n                b = gray + (b - gray) * saturation\n\n                // REFERENCE MATCH: the supplied premium reference carries visibly\n                // denser wood midtones than the outdoor source. Apply density only\n                // to pixels already classified as wood, and keep structural edges\n                // source-locked below. This is tonal presentation, not recoloring.\n                if (wood) {\n                    val woodLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b\n                    val density = when {\n                        woodLuma > 175f -> 0.930f\n                        woodLuma > 125f -> 0.915f\n                        woodLuma > 82f -> 0.945f\n                        else -> 0.975f\n                    }\n                    r *= density\n                    g *= density\n                    b *= density\n                }\n\n                // Never permit a large photometric jump at a real structural edge.''',
    'wood density',
)
replace_once(
    'v = ((v - 0.5f) * 1.050f + 0.5f).coerceIn(0f, 1f)',
    'v = ((v - 0.5f) * 1.062f + 0.5f).coerceIn(0f, 1f)',
    's curve',
)
replace_once(
    '''        if (v in 0.12f..0.80f) {\n            v = (v * 0.978f).coerceIn(0f, 1f)\n        }\n        if (v > 0.84f) v = 0.84f + (v - 0.84f) * 0.62f''',
    '''        if (v in 0.12f..0.80f) {\n            v = (v * 0.970f).coerceIn(0f, 1f)\n        }\n        if (v > 0.82f) v = 0.82f + (v - 0.82f) * 0.56f''',
    'midtone/highlight density',
)

# Cleaner neutral premium background: softly transitions from warm wall to a
# slightly denser neutral floor without a hard synthetic horizon.
replace_once(
    '''            shader = LinearGradient(\n                0f,\n                0f,\n                0f,\n                sourceHeight.toFloat(),\n                Color.rgb(247, 243, 237),\n                Color.rgb(238, 232, 224),\n                Shader.TileMode.CLAMP,\n            )''',
    '''            shader = LinearGradient(\n                0f,\n                0f,\n                0f,\n                sourceHeight.toFloat(),\n                intArrayOf(\n                    Color.rgb(246, 243, 238),\n                    Color.rgb(241, 238, 233),\n                    Color.rgb(230, 227, 222),\n                ),\n                floatArrayOf(0f, 0.70f, 1f),\n                Shader.TileMode.CLAMP,\n            )''',
    'studio background',
)

# Contact shadow remains support-derived only; make the penumbra slightly more
# natural while keeping opacity low and never creating an oval/drop shape.
replace_once(
    'val depth = (bounds.height() * 0.012f).roundToInt().coerceIn(3, 13)',
    'val depth = (bounds.height() * 0.016f).roundToInt().coerceIn(4, 16)',
    'contact depth',
)
replace_once(
    'mask[i] = max(mask[i], falloff * falloff * horizontal * 0.34f)',
    'mask[i] = max(mask[i], falloff * falloff * horizontal * 0.30f)',
    'contact opacity',
)
replace_once(
    '''            mask[i] = (temp[i] * 0.56f + (temp[i - 1] + temp[i + 1]) * 0.14f +\n                (temp[i - width] + temp[i + width]) * 0.08f).coerceIn(0f, 0.38f)''',
    '''            mask[i] = (temp[i] * 0.50f + (temp[i - 1] + temp[i + 1]) * 0.16f +\n                (temp[i - width] + temp[i + width]) * 0.09f).coerceIn(0f, 0.34f)''',
    'contact softness',
)
replace_once(
    'val a = (mask[i] * 108f).roundToInt().coerceIn(0, 34)',
    'val a = (mask[i] * 98f).roundToInt().coerceIn(0, 30)',
    'contact alpha',
)

# Preserve the already-stable matte behavior. We only tighten the final feather
# a little so fine shelf slats keep separation against the cleaner background.
replace_once(
    'alpha[i] = (center * 0.95f + avg * 0.05f).coerceIn(0f, 1f)',
    'alpha[i] = (center * 0.965f + avg * 0.035f).coerceIn(0f, 1f)',
    'final feather',
)

engine.write_text(text)

ptext = prompt.read_text()
old = '''        contrast = 1.010f,\n        warmth = 0.001f,\n        shadowStrength = 0f,\n        saturation = 1.012f,\n        detailStrength = 0.094f,'''
new = '''        contrast = 1.018f,\n        warmth = 0.000f,\n        shadowStrength = 0f,\n        saturation = 1.006f,\n        detailStrength = 0.108f,'''
if old not in ptext:
    raise SystemExit('missing PromptPolicy anchor')
prompt.write_text(ptext.replace(old, new, 1))
print('v0.8.4 reference-match transform applied')
