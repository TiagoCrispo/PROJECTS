#!/usr/bin/env python3
from pathlib import Path
import hashlib
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
here = Path(__file__).resolve().parent

def read(rel: str) -> str:
    p = root / rel
    if not p.exists():
        raise SystemExit(f'MISSING: {rel}')
    return p.read_text(encoding='utf-8')

def write(rel: str, text: str) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')

def replace_exact(rel: str, old: str, new: str, count: int = 1) -> None:
    text = read(rel)
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f'REPLACE COUNT MISMATCH {rel}: expected {count}, found {actual}: {old!r}')
    write(rel, text.replace(old, new))

expected = {
    'app/build.gradle.kts': '06c9f243522509f04ff444c3288e5905b56d3a509912e8b0311255b61f1d5dcf',
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt': '6cdc8417fd0cd40cf1f991d9f876ce54bb6b4f7e621d853e1f73e9c4abde6a18',
    'scripts/static_validate.py': '09dd24f7f844d2b5482e83be451fa1c23dca0217f39b5219e95ab6a7a8defc82',
}
for rel, digest in expected.items():
    got = hashlib.sha256((root / rel).read_bytes()).hexdigest()
    if got != digest:
        raise SystemExit(f'BLOCK19 BASE HASH MISMATCH {rel}: {got}')

replace_exact(
    'app/build.gradle.kts',
    '    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")\n',
    '    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")\n'
    '    testImplementation("junit:junit:4.13.2")\n',
)

engine_rel = 'app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalEnhancementEngine.kt'
replace_exact(
    engine_rel,
    '    private const val MAGIC_TOUCH_OUTPUT_CHANNELS = 2\n',
    '    private const val MAGIC_TOUCH_OUTPUT_CHANNELS = 2\n'
    '    private const val COARSE_MAX_UNCERTAIN_RATIO = 0.30f\n'
    '    private const val COARSE_MIN_CONSENSUS_IOU = 0.30f\n',
)
replace_exact(
    engine_rel,
    '    private data class CoarseAlpha(val values: FloatArray, val width: Int, val height: Int)\n',
    '    private data class CoarseAlpha(val values: FloatArray, val width: Int, val height: Int)\n'
    '    private data class CoarseCandidate(\n'
    '        val values: FloatArray,\n'
    '        val metrics: SegmentationMaskMetrics,\n'
    '    )\n',
)
replace_exact(
    engine_rel,
    '''            var previousPriorOffset = -1
            var best: CoarseAlpha? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for ((probeX, probeY) in SEGMENTATION_PROBES) {
''',
    '''            var previousPriorOffset = -1
            val candidates = ArrayList<CoarseCandidate>(SEGMENTATION_PROBES.size)
            for ((probeX, probeY) in SEGMENTATION_PROBES) {
''',
)
replace_exact(
    engine_rel,
    '''                if (!retainMainComponent(values, contentWidth, contentHeight)) continue
                protectThinStructures(values, contentWidth, contentHeight)
                val score = scoreCoarseCandidate(values, contentWidth, contentHeight)
                if (score > bestScore) {
                    bestScore = score
                    best = CoarseAlpha(values, contentWidth, contentHeight)
                }
            }
            return best
''',
    '''                if (!retainMainComponent(values, contentWidth, contentHeight)) continue
                protectThinStructures(values, contentWidth, contentHeight)
                val localProbeX = (px - offsetX).coerceIn(0, contentWidth - 1)
                val localProbeY = (py - offsetY).coerceIn(0, contentHeight - 1)
                if (values[localProbeY * contentWidth + localProbeX] < 0.18f) continue
                val metrics = SegmentationMaskQualityGate.evaluate(
                    alpha = values,
                    width = contentWidth,
                    height = contentHeight,
                    threshold = COMPONENT_THRESHOLD,
                    minCoverage = MIN_COVERAGE,
                    maxCoverage = MAX_COVERAGE,
                    maxUncertainRatio = COARSE_MAX_UNCERTAIN_RATIO,
                )
                if (!metrics.accepted) continue
                candidates += CoarseCandidate(values, metrics)
            }
            if (candidates.isEmpty()) return null
            if (candidates.size == 1) {
                return CoarseAlpha(candidates[0].values, contentWidth, contentHeight)
            }

            var best: CoarseCandidate? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for (i in candidates.indices) {
                var maxConsensus = 0f
                for (j in candidates.indices) {
                    if (i == j) continue
                    maxConsensus = max(
                        maxConsensus,
                        SegmentationMaskQualityGate.intersectionOverUnion(
                            candidates[i].values,
                            candidates[j].values,
                            COMPONENT_THRESHOLD,
                        ),
                    )
                }
                if (maxConsensus < COARSE_MIN_CONSENSUS_IOU) continue
                val score = candidates[i].metrics.score + maxConsensus * 0.32f
                if (score > bestScore) {
                    bestScore = score
                    best = candidates[i]
                }
            }
            val selected = best ?: return null
            return CoarseAlpha(selected.values, contentWidth, contentHeight)
''',
)

old_score = '''    private fun scoreCoarseCandidate(alpha: FloatArray, width: Int, height: Int): Float {
        var foreground = 0
        var border = 0
        var centerEnergy = 0f
        val centerX0 = (width * 0.34f).roundToInt().coerceIn(0, width - 1)
        val centerX1 = (width * 0.66f).roundToInt().coerceIn(0, width - 1)
        val centerY0 = (height * 0.30f).roundToInt().coerceIn(0, height - 1)
        val centerY1 = (height * 0.72f).roundToInt().coerceIn(0, height - 1)
        var centerSamples = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val a = alpha[y * width + x]
                if (a >= COMPONENT_THRESHOLD) {
                    foreground++
                    if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border++
                }
                if (x in centerX0..centerX1 && y in centerY0..centerY1) {
                    centerEnergy += a
                    centerSamples++
                }
            }
        }
        val coverage = foreground.toFloat() / alpha.size.coerceAtLeast(1)
        if (coverage !in MIN_COVERAGE..MAX_COVERAGE) return Float.NEGATIVE_INFINITY
        val borderRatio = if (foreground == 0) 1f else border.toFloat() / foreground
        val centerMean = centerEnergy / centerSamples.coerceAtLeast(1)
        return coverage * 0.72f + centerMean * 0.28f - borderRatio * 0.35f
    }

'''
replace_exact(engine_rel, old_score, '')

old_validate = '''    private fun validateAlpha(alpha: FloatArray, width: Int, height: Int): MaskReport {
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

'''
new_validate = '''    private fun validateAlpha(alpha: FloatArray, width: Int, height: Int): MaskReport {
        val metrics = SegmentationMaskQualityGate.evaluate(
            alpha = alpha,
            width = width,
            height = height,
            threshold = 0.48f,
            minCoverage = MIN_COVERAGE,
            maxCoverage = MAX_COVERAGE,
            maxUncertainRatio = 0.16f,
        )
        if (metrics.right <= metrics.left || metrics.bottom <= metrics.top) {
            return MaskReport(false, RectF(), metrics.uncertainRatio)
        }
        return MaskReport(
            accepted = metrics.accepted,
            bounds = RectF(
                metrics.left.toFloat(),
                metrics.top.toFloat(),
                metrics.right.toFloat(),
                metrics.bottom.toFloat(),
            ),
            uncertainRatio = metrics.uncertainRatio,
        )
    }

'''
replace_exact(engine_rel, old_validate, new_validate)

write(
    'app/src/main/java/com/tiagocrispo/furnitureshot/processing/SegmentationMaskQualityGate.kt',
    (here / 'block19_mask_gate.kt').read_text(encoding='utf-8'),
)
write(
    'app/src/test/java/com/tiagocrispo/furnitureshot/processing/SegmentationMaskQualityGateTest.kt',
    (here / 'block19_mask_gate_test.kt').read_text(encoding='utf-8'),
)

replace_exact(
    'scripts/static_validate.py',
    "diagnostics = text('app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalAiDiagnostics.kt')\n",
    "diagnostics = text('app/src/main/java/com/tiagocrispo/furnitureshot/processing/LocalAiDiagnostics.kt')\n"
    "mask_gate = text('app/src/main/java/com/tiagocrispo/furnitureshot/processing/SegmentationMaskQualityGate.kt')\n"
    "mask_test = text('app/src/test/java/com/tiagocrispo/furnitureshot/processing/SegmentationMaskQualityGateTest.kt')\n",
)
replace_exact(
    'scripts/static_validate.py',
    "model = root / 'app/src/main/assets/magic_touch.tflite'\n",
    "req('testImplementation(\\\"junit:junit:4.13.2\\\")' in build, 'real unit-test dependency missing')\n"
    "req('SegmentationMaskQualityGate.evaluate' in engine and 'COARSE_MIN_CONSENSUS_IOU = 0.30f' in engine, 'multi-probe mask quality gate missing')\n"
    "req('intersectionOverUnion' in engine and 'maxConsensus < COARSE_MIN_CONSENSUS_IOU' in engine, 'multi-probe consensus rejection missing')\n"
    "req('values[localProbeY * contentWidth + localProbeX] < 0.18f' in engine, 'probe containment validation missing')\n"
    "req('maxSideTouchRatio < 0.18f' in mask_gate and 'verticalSupportRatio >= 0.006f' in mask_gate, 'structural mask checks missing')\n"
    "req('fillRatio < 0.985f' in mask_gate and 'edgeStripRatio < 0.24f' in mask_gate, 'mask leak/solid-box checks missing')\n"
    "req('centeredTableWithThinLegsPasses' in mask_test and 'broadEdgeTouchIsRejected' in mask_test and 'consensusRewardsSameObjectAndRejectsDisjointMasks' in mask_test, 'deterministic mask unit tests missing')\n"
    "model = root / 'app/src/main/assets/magic_touch.tflite'\n",
)

print('PRODUCTSHOT_BLOCK19_MASK_QUALITY_GATE_APPLIED')
