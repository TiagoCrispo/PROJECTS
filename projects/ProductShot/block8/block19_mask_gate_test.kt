package com.tiagocrispo.furnitureshot.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationMaskQualityGateTest {
    private val width = 160
    private val height = 160

    @Test
    fun centeredTableWithThinLegsPasses() {
        val a = FloatArray(width * height)
        fill(a, 30, 50, 130, 78, 1f)
        fill(a, 38, 78, 47, 142, 1f)
        fill(a, 113, 78, 122, 142, 1f)
        val m = gate(a)
        assertTrue(m.accepted)
        assertTrue(m.verticalSupportRatio > 0.05f)
    }

    @Test
    fun fullFrameLeakIsRejected() {
        val a = FloatArray(width * height) { 1f }
        assertFalse(gate(a).accepted)
    }

    @Test
    fun tinyFragmentIsRejected() {
        val a = FloatArray(width * height)
        fill(a, 76, 76, 84, 84, 1f)
        assertFalse(gate(a).accepted)
    }

    @Test
    fun broadEdgeTouchIsRejected() {
        val a = FloatArray(width * height)
        fill(a, 0, 35, 100, 105, 1f)
        assertFalse(gate(a).accepted)
    }

    @Test
    fun fuzzyUncertainMaskIsRejected() {
        val a = FloatArray(width * height)
        fill(a, 25, 35, 135, 140, 0.5f)
        assertFalse(gate(a).accepted)
    }

    @Test
    fun consensusRewardsSameObjectAndRejectsDisjointMasks() {
        val a = FloatArray(width * height)
        val b = FloatArray(width * height)
        val c = FloatArray(width * height)
        fill(a, 30, 50, 130, 78, 1f)
        fill(a, 38, 78, 47, 142, 1f)
        fill(a, 113, 78, 122, 142, 1f)
        fill(b, 32, 52, 132, 80, 1f)
        fill(b, 40, 80, 49, 144, 1f)
        fill(b, 115, 80, 124, 144, 1f)
        fill(c, 5, 5, 45, 45, 1f)
        assertTrue(SegmentationMaskQualityGate.intersectionOverUnion(a, b, 0.48f) > 0.72f)
        assertTrue(SegmentationMaskQualityGate.intersectionOverUnion(a, c, 0.48f) < 0.05f)
    }

    private fun gate(a: FloatArray) = SegmentationMaskQualityGate.evaluate(
        alpha = a,
        width = width,
        height = height,
        threshold = 0.48f,
        minCoverage = 0.022f,
        maxCoverage = 0.86f,
        maxUncertainRatio = 0.16f,
    )

    private fun fill(a: FloatArray, left: Int, top: Int, right: Int, bottom: Int, value: Float) {
        for (y in top until bottom) for (x in left until right) a[y * width + x] = value
    }
}
