// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.analysis.SkinProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 非 AI 肤色保护测试：保守置信度，弱约束。
 */
class SkinProtectionTest {

    private fun confidenceOf(r: Float, g: Float, b: Float): Float =
        SkinProtection.computeConfidence(
            floatArrayOf(r),
            floatArrayOf(g),
            floatArrayOf(b),
        )[0]

    /** 典型肤色（线性光近似）：暖色、中等亮度。 */
    @Test
    fun skinTone_hasConfidence() {
        val c = confidenceOf(0.55f, 0.34f, 0.25f)
        assertTrue("肤色置信度应较高 c=$c", c > 0.5f)
    }

    /** 较亮的肤色（额头高光）。 */
    @Test
    fun brightSkin_lowerButPresent() {
        val c = confidenceOf(0.62f, 0.42f, 0.32f)
        assertTrue("亮肤色应有中等置信度 c=$c", c > 0.3f)
    }

    /** 纯红不误伤（greenRatio 排除）。 */
    @Test
    fun pureRed_notSkin() {
        val c = confidenceOf(1f, 0f, 0f)
        assertTrue("纯红不应判为肤色 c=$c", c < 0.2f)
    }

    /** 纯蓝/纯绿不误伤。 */
    @Test
    fun blueGreen_notSkin() {
        assertTrue(confidenceOf(0f, 0f, 1f) < 0.2f)
        assertTrue(confidenceOf(0f, 1f, 0f) < 0.2f)
    }

    /** 极暗/极亮不误伤。 */
    @Test
    fun extremes_notSkin() {
        assertTrue(confidenceOf(0.02f, 0.01f, 0.008f) < 0.2f)
        assertTrue(confidenceOf(0.95f, 0.93f, 0.91f) < 0.2f)
    }

    /** 蓝天（饱和蓝）不误伤。 */
    @Test
    fun skyBlue_notSkin() {
        val c = confidenceOf(0.18f, 0.30f, 0.62f)
        assertTrue("蓝天不应判为肤色 c=$c", c < 0.2f)
    }

    /** 弱约束：置信度高时增益受限，但不会强制为 1。 */
    @Test
    fun applyProtection_weakConstraint() {
        val gain = 1.8f
        val protected = SkinProtection.applyProtection(gain, confidence = 1f, strength = 0.35f)
        assertTrue(protected < gain)
        assertTrue(protected > 1f) // 弱约束：不完全取消增强
        // 低置信度不影响
        assertEquals(gain, SkinProtection.applyProtection(gain, confidence = 0f, strength = 0.35f), 1e-5f)
        // strength=0 不影响
        assertEquals(gain, SkinProtection.applyProtection(gain, confidence = 1f, strength = 0f), 1e-5f)
    }

    @Test
    fun invalidInputs_safe() {
        val c = SkinProtection.computeConfidence(
            floatArrayOf(Float.NaN, -1f, 0f),
            floatArrayOf(0f, Float.POSITIVE_INFINITY, 0f),
            floatArrayOf(0f, 0f, Float.NaN),
        )
        for (v in c) {
            assertTrue(v.isFinite())
            assertTrue(v in 0f..1f)
        }
    }
}
