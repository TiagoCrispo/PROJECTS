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

# v0.8.5: encode the development QUALITY REFERENCE into deterministic image
# processing. SOURCE remains truth for geometry, pixels and material identity.
# The goal is not to recolor/regenerate the product, only to reproduce the
# reference's photographic presentation more faithfully.
replace_once('val targetLuma = 130f', 'val targetLuma = 132f', 'balanced target luma')
replace_once(
    '''val exposure = (1f + (targetLuma - quality.meanLuma) / 255f * 0.048f)\n            .coerceIn(0.92f, if (quality.highlightClip > 0.018f) 0.990f else 1.005f)''',
    '''val exposure = (1f + (targetLuma - quality.meanLuma) / 255f * 0.045f)\n            .coerceIn(0.93f, if (quality.highlightClip > 0.018f) 0.992f else 1.004f)''',
    'exposure restraint',
)
replace_once(
    'val textureConfidence = ((residualEnergy - 0.8f) / 8.5f).coerceIn(0.45f, 1f)',
    'val textureConfidence = ((residualEnergy - 0.7f) / 8.0f).coerceIn(0.30f, 1f)',
    'texture confidence',
)
replace_once(
    '''                var r = r0 + (r0 - localR).coerceIn(-14f, 14f) * detailGain +\n                    (r0 - wideR).coerceIn(-18f, 18f) * mediumGain\n                var g = g0 + (g0 - localG).coerceIn(-14f, 14f) * detailGain +\n                    (g0 - wideG).coerceIn(-18f, 18f) * mediumGain\n                var b = b0 + (b0 - localB).coerceIn(-14f, 14f) * detailGain +\n                    (b0 - wideB).coerceIn(-18f, 18f) * mediumGain\n\n                r = toneChannel(r / 255f, exposure) * 255f''',
    '''                var r = r0 + (r0 - localR).coerceIn(-14f, 14f) * detailGain +\n                    (r0 - wideR).coerceIn(-18f, 18f) * mediumGain\n                var g = g0 + (g0 - localG).coerceIn(-14f, 14f) * detailGain +\n                    (g0 - wideG).coerceIn(-18f, 18f) * mediumGain\n                var b = b0 + (b0 - localB).coerceIn(-14f, 14f) * detailGain +\n                    (b0 - wideB).coerceIn(-18f, 18f) * mediumGain\n\n                // SOURCE-ANCHORED LUMA MICROCONTRAST: reinforce only luminance\n                // structure already present in the original RGB. Applying the same\n                // delta to R/G/B avoids inventing colored grain or changing wood hue.\n                val localLuma = 0.2126f * localR + 0.7152f * localG + 0.0722f * localB\n                val lumaResidual = (originalLuma - localLuma).coerceIn(-9f, 9f)\n                val lumaMicro = lumaResidual * (if (wood) 0.14f else 0.045f) *\n                    textureConfidence * noiseGuard\n                r += lumaMicro\n                g += lumaMicro\n                b += lumaMicro\n\n                r = toneChannel(r / 255f, exposure) * 255f''',
    'luma microcontrast',
)
replace_once(
    '''                // REFERENCE MATCH: the supplied premium reference carries visibly\n                // denser wood midtones than the outdoor source. Apply density only\n                // to pixels already classified as wood, and keep structural edges\n                // source-locked below. This is tonal presentation, not recoloring.\n                if (wood) {\n                    val woodLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b\n                    val density = when {\n                        woodLuma > 175f -> 0.930f\n                        woodLuma > 125f -> 0.915f\n                        woodLuma > 82f -> 0.945f\n                        else -> 0.975f\n                    }\n                    r *= density\n                    g *= density\n                    b *= density\n                }''',
    '''                // REFERENCE MATCH 2: outdoor highlights on varnished/finished wood\n                // often become orange and washed. Restrain only excessive highlight\n                // chroma, then apply a conservative density curve. The source hue and\n                // all structural pixels remain the authority.\n                if (wood) {\n                    var woodLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b\n                    if (woodLuma > 135f) {\n                        val warmExcess = ((r - b - 50f) / 105f).coerceIn(0f, 1f)\n                        val highlightWeight = ((woodLuma - 135f) / 105f).coerceIn(0f, 1f)\n                        val restraint = warmExcess * highlightWeight * 0.10f\n                        val neutral = 0.2126f * r + 0.7152f * g + 0.0722f * b\n                        r = r + (neutral - r) * restraint\n                        g = g + (neutral - g) * restraint\n                        b = b + (neutral - b) * restraint\n                    }\n                    woodLuma = 0.2126f * r + 0.7152f * g + 0.0722f * b\n                    val density = when {\n                        woodLuma > 178f -> 0.945f\n                        woodLuma > 128f -> 0.930f\n                        woodLuma > 84f -> 0.958f\n                        else -> 0.982f\n                    }\n                    r *= density\n                    g *= density\n                    b *= density\n                }''',
    'highlight chroma and wood density',
)

# More reference-like catalog environment: a near-white wall that rolls softly
# into a neutral concrete/beige floor. No hard horizon and no decorative scene.
replace_once(
    '''                intArrayOf(\n                    Color.rgb(246, 243, 238),\n                    Color.rgb(241, 238, 233),\n                    Color.rgb(230, 227, 222),\n                ),\n                floatArrayOf(0f, 0.70f, 1f),''',
    '''                intArrayOf(\n                    Color.rgb(248, 246, 242),\n                    Color.rgb(246, 244, 240),\n                    Color.rgb(242, 240, 236),\n                    Color.rgb(235, 234, 231),\n                    Color.rgb(228, 228, 226),\n                ),\n                floatArrayOf(0f, 0.58f, 0.68f, 0.76f, 1f),''',
    'catalog wall floor background',
)

# Correct a composition bug inherited from the previous builds: the lower clamp
# prevented sufficiently large source objects from being reduced to the target
# catalog framing. We still never upscale above 1.0x.
replace_once(
    '''        val uniformScale = min(sourceWidth * 0.86f / objectW, sourceHeight * 0.84f / objectH)\n            // QUALITY MAX avoids meaningful digital enlargement. Preserve camera\n            // microtexture instead of scaling a processed cutout by 8%.\n            .coerceIn(0.95f, 1.0f)''',
    '''        val uniformScale = min(sourceWidth * 0.84f / objectW, sourceHeight * 0.82f / objectH)\n            // Never upscale above source resolution, but allow real downscaling when\n            // the SOURCE fills the frame so the final catalog shot has breathing room.\n            .coerceIn(0.82f, 1.0f)''',
    'catalog framing scale',
)
replace_once(
    'val desiredBottom = sourceHeight * 0.90f',
    'val desiredBottom = sourceHeight * 0.895f',
    'catalog framing bottom',
)

# Keep edges crisp without turning the mask binary.
replace_once(
    'alpha[i] = (center * 0.965f + avg * 0.035f).coerceIn(0f, 1f)',
    'alpha[i] = (center * 0.970f + avg * 0.030f).coerceIn(0f, 1f)',
    'final feather',
)

engine.write_text(text)

ptext = prompt.read_text()
old = '''        contrast = 1.018f,\n        warmth = 0.000f,\n        shadowStrength = 0f,\n        saturation = 1.006f,\n        detailStrength = 0.108f,'''
new = '''        contrast = 1.020f,\n        warmth = 0.000f,\n        shadowStrength = 0f,\n        saturation = 1.004f,\n        detailStrength = 0.112f,'''
if old not in ptext:
    raise SystemExit('missing PromptPolicy v0.8.4 anchor')
prompt.write_text(ptext.replace(old, new, 1))
print('v0.8.5 reference-match2 transform applied')
