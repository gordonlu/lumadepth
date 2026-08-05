// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.filter.FastGuidedFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastGuidedFilterTest {

    @Test
    fun flatRegion_isSmoothedTowardMean() {
        // 引导图平坦、输入带脉冲 → 输出接近输入均值（脉冲被平滑）
        val w = 32
        val h = 32
        val guide = FloatArray(w * h) { 0.5f }
        val input = FloatArray(w * h) { 0.1f }
        input[16 * w + 16] = 0.9f
        val out = FastGuidedFilter.filter(guide, input, w, h, radius = 2, eps = 0.01f)
        // 中心脉冲被强烈平滑
        assertTrue(out[16 * w + 16] < 0.5f)
        // 远离脉冲处接近均值
        assertTrue(kotlin.math.abs(out[0] - 0.1f) < 0.05f)
    }

    @Test
    fun strongEdge_isPreserved() {
        // 引导图有强边缘（左右两半亮度不同），输入与引导一致 → 输出应保持边缘
        val w = 64
        val h = 16
        val guide = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                guide[y * w + x] = if (x < w / 2) 0.9f else 0.1f
            }
        }
        val out = FastGuidedFilter.filter(guide, guide, w, h, radius = 3, eps = 0.01f)
        // 边缘两侧仍接近原值（guided filter 保边）
        assertEquals(0.9f, out[4 * w + 4], 0.06f)
        assertEquals(0.1f, out[4 * w + w - 4], 0.06f)
    }

    @Test
    fun edgeNotBlurredLikeBoxFilter() {
        // 对比：box filter 会把边缘糊掉，guided filter 保持
        val w = 64
        val h = 16
        val guide = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                guide[y * w + x] = if (x < w / 2) 0.9f else 0.1f
            }
        }
        val out = FastGuidedFilter.filter(guide, guide, w, h, radius = 4, eps = 0.01f)
        val edgeLeft = out[4 * w + w / 2 - 1]
        val edgeRight = out[4 * w + w / 2]
        // 边缘处对比度仍然较大（左侧远高于右侧）
        assertTrue(edgeLeft - edgeRight > 0.4f)
    }

    @Test
    fun constantInput_staysConstant() {
        val w = 16
        val h = 16
        val guide = FloatArray(w * h) { i -> (i % 16) / 15f }
        val input = FloatArray(w * h) { 0.4f }
        val out = FastGuidedFilter.filter(guide, input, w, h, radius = 2, eps = 0.01f)
        for (v in out) {
            assertEquals(0.4f, v, 0.02f)
        }
    }

    @Test
    fun allOutputsFinite() {
        val w = 24
        val h = 24
        val guide = FloatArray(w * h) { i -> ((i * 7) % 23) / 22f }
        val input = FloatArray(w * h) { i -> ((i * 13) % 17) / 16f }
        val out = FastGuidedFilter.filter(guide, input, w, h, radius = 2, eps = 0.001f)
        for (v in out) assertTrue(v.isFinite())
    }

    @Test
    fun downsampled_matchesSingleScaleOnFlatArea() {
        val w = 32
        val h = 32
        val guide = FloatArray(w * h) { 0.5f }
        val input = FloatArray(w * h) { 0.3f }
        val direct = FastGuidedFilter.filter(guide, input, w, h, radius = 2, eps = 0.01f)
        val down = FastGuidedFilter.filterDownsampled(guide, input, w, h, scale = 2, radius = 2, eps = 0.01f)
        for (i in direct.indices) {
            assertEquals(direct[i], down[i], 0.05f)
        }
    }

    @Test
    fun invalidInputs_throw() {
        val a = FloatArray(10)
        val b = FloatArray(9)
        try {
            FastGuidedFilter.filter(a, b, 10, 1, 1, 0.01f)
            assertTrue("should have thrown", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
