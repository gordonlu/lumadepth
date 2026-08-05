// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.analysis.NoiseEstimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 噪声感知增益抑制测试。
 */
class NoiseEstimationTest {

    @Test
    fun flatDarkArea_lowNoiseMask() {
        val y = FloatArray(64) { 0.02f } // 平坦暗部
        val blur = FloatArray(64) { 0.02f } // 平滑后相同
        val mask = NoiseEstimation.estimateNoiseMask(y, blur)
        for (m in mask) assertEquals(0f, m, 1e-6f)
    }

    @Test
    fun noisyDarkArea_highNoiseMask() {
        val y = FloatArray(64) { 0.02f }
        val blur = FloatArray(64) { 0.02f }
        // 模拟噪点：原亮度带脉冲
        y[10] = 0.09f
        y[20] = 0.11f
        val mask = NoiseEstimation.estimateNoiseMask(y, blur)
        assertTrue("噪声点应被检测 mask=${mask[10]}", mask[10] > 0.5f)
        assertTrue("噪声点应被检测 mask=${mask[20]}", mask[20] > 0.5f)
        // 平坦处不受影响
        assertEquals(0f, mask[0], 1e-6f)
    }

    @Test
    fun brightArea_neverSuppressed() {
        val y = FloatArray(64) { 0.5f } // 亮部
        val blur = FloatArray(64) { 0.5f }
        y[5] = 0.9f // 大残差
        val mask = NoiseEstimation.estimateNoiseMask(y, blur)
        for (m in mask) assertEquals(0f, m, 1e-6f)
    }

    @Test
    fun gradientDarkArea_lowMask() {
        // 暗部有真实渐变（结构性）→ 残差小 → mask 低
        val y = FloatArray(100)
        for (i in 0 until 100) y[i] = 0.005f + i * 0.0005f
        val blur = y.copyOf()
        val mask = NoiseEstimation.estimateNoiseMask(y, blur)
        for (m in mask) assertEquals(0f, m, 1e-6f)
    }

    @Test
    fun invalidInputs_safe() {
        val y = FloatArray(16) { Float.NaN }
        val blur = FloatArray(16) { 0f }
        val mask = NoiseEstimation.estimateNoiseMask(y, blur)
        for (m in mask) {
            assertTrue(m.isFinite())
            assertTrue(m in 0f..1f)
        }
    }

    @Test
    fun maskBounded() {
        val y = FloatArray(64) { 0.01f }
        val blur = FloatArray(64) { 0f } // 极端残差
        val mask = NoiseEstimation.estimateNoiseMask(y, blur)
        for (m in mask) assertTrue(m in 0f..1f)
    }
}
