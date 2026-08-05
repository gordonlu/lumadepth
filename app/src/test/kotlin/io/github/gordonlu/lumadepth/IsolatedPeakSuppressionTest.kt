// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.gainmap.IsolatedPeakSuppression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 孤立增益峰值清理测试。
 */
class IsolatedPeakSuppressionTest {

    @Test
    fun isolatedPeak_pulledBack() {
        val w = 32
        val h = 32
        val gain = FloatArray(w * h) { 1.3f }
        gain[16 * w + 16] = 2.0f // 孤立峰值
        val out = IsolatedPeakSuppression.suppress(gain, w, h)
        val center = out[16 * w + 16]
        assertTrue("孤立峰值应被拉回 center=$center", center < 1.6f)
        assertTrue("仍高于邻域（拉回而非抹平）", center > 1.3f)
    }

    @Test
    fun uniformRegion_untouched() {
        val w = 32
        val h = 32
        val gain = FloatArray(w * h) { 1.5f }
        val out = IsolatedPeakSuppression.suppress(gain, w, h)
        for (v in out) assertEquals(1.5f, v, 1e-4f)
    }

    @Test
    fun brightBlob_withNeighborSupport_kept() {
        val w = 32
        val h = 32
        val gain = FloatArray(w * h) { 1.3f }
        // 3x3 亮斑（邻域支持，不应被当作孤立峰值）
        for (yy in 14..17) {
            for (xx in 14..17) {
                gain[yy * w + xx] = 1.9f
            }
        }
        val out = IsolatedPeakSuppression.suppress(gain, w, h)
        // 亮斑中心应保持（邻域同样高）
        assertEquals(1.9f, out[16 * w + 16], 0.02f)
    }

    @Test
    fun boundaries_safe() {
        val w = 16
        val h = 16
        val gain = FloatArray(w * h) { 1.0f }
        gain[0] = 2.0f // 角落孤立峰值
        gain[15 * w + 15] = 2.0f
        val out = IsolatedPeakSuppression.suppress(gain, w, h)
        assertTrue(out[0] < 1.5f)
        assertTrue(out[15 * w + 15] < 1.5f)
    }

    @Test
    fun thresholdZero_noOp() {
        val w = 16
        val h = 16
        val gain = FloatArray(w * h) { 1.2f }
        gain[8 * w + 8] = 2.0f
        val out = IsolatedPeakSuppression.suppress(gain, w, h, threshold = 0f)
        assertEquals(2.0f, out[8 * w + 8], 1e-4f)
    }

    @Test
    fun allOutputsFinite() {
        val w = 24
        val h = 24
        val gain = FloatArray(w * h) { i -> 1f + (i % 10) / 20f }
        val out = IsolatedPeakSuppression.suppress(gain, w, h)
        for (v in out) assertTrue(v.isFinite())
    }
}
