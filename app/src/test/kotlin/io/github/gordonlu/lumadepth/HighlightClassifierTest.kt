// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.analysis.HighlightClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高光区域分类测试：区分"有效光源/反光"与"无细节剪裁"（天空/白墙）。
 */
class HighlightClassifierTest {

    private fun classify(clipped: FloatArray, variance: FloatArray, w: Int, h: Int): FloatArray =
        HighlightClassifier.computeConfidence(clipped, variance, w, h)

    /** 大块平坦、贴边的白色（天空/白墙场景）。 */
    @Test
    fun largeFlatEdgeTouchingWhite_hasLowConfidence() {
        val w = 100
        val h = 100
        val clipped = FloatArray(w * h) { 1f } // 全图剪裁
        val variance = FloatArray(w * h) { 0f } // 完全平坦
        val c = classify(clipped, variance, w, h)
        // 全图是一个贴边大区域 → confidence 低
        val avg = c.average().toFloat()
        assertTrue("avg=$avg", avg < 0.25f)
    }

    /** 小面积、不贴边、有纹理的亮点（灯光/太阳/反光场景）。 */
    @Test
    fun smallInternalTexturedSpot_hasHighConfidence() {
        val w = 100
        val h = 100
        val clipped = FloatArray(w * h) { 0f }
        val variance = FloatArray(w * h) { 0f }
        // 中心 5x5 亮点，内部有纹理（variance 高）
        for (y in 48..52) {
            for (x in 48..52) {
                clipped[y * w + x] = 1f
                variance[y * w + x] = 0.01f
            }
        }
        val c = classify(clipped, variance, w, h)
        val center = c[50 * w + 50]
        assertTrue("center=$center", center > 0.6f)
    }

    /** 无剪裁区域 confidence 为 0。 */
    @Test
    fun nonClippedArea_isZero() {
        val w = 64
        val h = 64
        val clipped = FloatArray(w * h) { 0f }
        val variance = FloatArray(w * h) { 0.005f }
        val c = classify(clipped, variance, w, h)
        for (v in c) assertEquals(0f, v, 1e-6f)
    }

    /** 贴边小区域（角落的灯）介于两者之间。 */
    @Test
    fun cornerSpot_isModerateConfidence() {
        val w = 100
        val h = 100
        val clipped = FloatArray(w * h) { 0f }
        val variance = FloatArray(w * h) { 0f }
        for (y in 0..4) {
            for (x in 0..4) {
                clipped[y * w + x] = 1f
                variance[y * w + x] = 0.01f
            }
        }
        val c = classify(clipped, variance, w, h)
        val corner = c[2 * w + 2]
        // 面积小 → 得分高，但贴边 → 扣分；整体应高于天空、低于中心光源
        assertTrue("corner=$corner", corner in 0.3f..0.8f)
    }

    /** 多个分离区域各自独立评分。 */
    @Test
    fun separateRegions_scoredIndependently() {
        val w = 100
        val h = 100
        val clipped = FloatArray(w * h) { 0f }
        val variance = FloatArray(w * h) { 0f }
        // 大平坦贴边区域（上半全部）
        for (y in 0 until 40) {
            for (x in 0 until w) {
                clipped[y * w + x] = 1f
            }
        }
        // 小纹理亮点（中心）
        for (y in 48..52) {
            for (x in 48..52) {
                clipped[y * w + x] = 1f
                variance[y * w + x] = 0.01f
            }
        }
        val c = classify(clipped, variance, w, h)
        val sky = c[10 * w + 50]
        val lamp = c[50 * w + 50]
        assertTrue("sky=$sky lamp=$lamp", sky < lamp)
        assertTrue("lamp=$lamp", lamp > 0.6f)
    }

    @Test
    fun extremeInputs_noCrash() {
        val w = 32
        val h = 32
        // 全 0
        val c0 = classify(FloatArray(w * h) { 0f }, FloatArray(w * h) { 0f }, w, h)
        assertTrue(c0.all { it == 0f })
        // 全 1
        val c1 = classify(FloatArray(w * h) { 1f }, FloatArray(w * h) { 0f }, w, h)
        assertTrue(c1.all { it in 0f..1f })
        // 1x1
        val c2 = classify(floatArrayOf(1f), floatArrayOf(0f), 1, 1)
        assertTrue(c2.all { it in 0f..1f })
        // NaN 输入不崩溃
        val c3 = classify(floatArrayOf(Float.NaN), floatArrayOf(Float.NaN), 1, 1)
        assertTrue(c3.all { it in 0f..1f })
    }
}
