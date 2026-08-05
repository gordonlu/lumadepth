// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.filter.LocalLaplacianFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 保边滤波（高质量细节增强）测试。
 */
class LocalLaplacianFilterTest {

    @Test
    fun flatRegion_isUnchanged() {
        val w = 64
        val h = 64
        val input = FloatArray(w * h) { 0.5f }
        val out = LocalLaplacianFilter.filter(input, w, h, sigmaR = 0.03f, alpha = 0.5f)
        for (v in out) {
            assertEquals(0.5f, v, 0.01f)
        }
    }

    @Test
    fun strongEdge_isPreserved() {
        val w = 64
        val h = 16
        val input = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                input[y * w + x] = if (x < w / 2) 0.8f else 0.1f
            }
        }
        val out = LocalLaplacianFilter.filter(input, w, h, sigmaR = 0.03f, alpha = 0.5f)
        // 边缘两侧保持接近原值（保边，不模糊）
        assertEquals(0.8f, out[4 * w + 4], 0.04f)
        assertEquals(0.1f, out[4 * w + w - 4], 0.04f)
        // 边缘处仍有较大对比（无光晕）
        val left = out[4 * w + w / 2 - 1]
        val right = out[4 * w + w / 2]
        assertTrue(left - right > 0.5f)
    }

    @Test
    fun smallDetails_areEnhanced() {
        val w = 64
        val h = 64
        val base = FloatArray(w * h) { 0.4f }
        // 小幅细节：±0.01 的微扰（5x5 区域）
        for (y in 30..34) {
            for (x in 30..34) {
                base[y * w + x] = 0.41f
            }
        }
        val out = LocalLaplacianFilter.filter(base, w, h, sigmaR = 0.03f, alpha = 0.5f)
        // 增强后细节幅度应大于原始
        val detailIn = base[32 * w + 32] - base[0]
        val detailOut = out[32 * w + 32] - out[0]
        assertTrue("细节应被增强 in=$detailIn out=$detailOut", detailOut > detailIn)
    }

    @Test
    fun alphaOne_isIdentity() {
        val w = 48
        val h = 48
        val input = FloatArray(w * h) { i -> ((i * 7) % 23) / 22f }
        val out = LocalLaplacianFilter.filter(input, w, h, sigmaR = 0.05f, alpha = 1f)
        for (i in input.indices) {
            assertEquals(input[i], out[i], 0.02f)
        }
    }

    @Test
    fun allOutputsFinite() {
        val w = 40
        val h = 40
        val input = FloatArray(w * h) { i -> ((i * 13) % 29) / 28f }
        val out = LocalLaplacianFilter.filter(input, w, h, sigmaR = 0.02f, alpha = 0.4f)
        for (v in out) assertTrue(v.isFinite())
    }

    @Test
    fun extremeSizes_doNotCrash() {
        // 1x1
        val one = LocalLaplacianFilter.filter(floatArrayOf(0.5f), 1, 1, 0.03f, 0.5f)
        assertTrue(one[0].isFinite())
        // 超宽 / 超高
        val wide = LocalLaplacianFilter.filter(FloatArray(256) { it / 255f }, 256, 1, 0.03f, 0.5f)
        assertTrue(wide.all { it.isFinite() })
        val tall = LocalLaplacianFilter.filter(FloatArray(256) { it / 255f }, 1, 256, 0.03f, 0.5f)
        assertTrue(tall.all { it.isFinite() })
    }

    @Test
    fun nanInputs_safe() {
        val input = FloatArray(64) { Float.NaN }
        val out = LocalLaplacianFilter.filter(input, 8, 8, 0.03f, 0.5f)
        for (v in out) assertTrue(v.isFinite())
    }

    @Test
    fun largerAlpha_strongerEnhancement() {
        val w = 32
        val h = 32
        val input = FloatArray(w * h) { 0.4f }
        input[16 * w + 16] = 0.405f
        val weak = LocalLaplacianFilter.filter(input, w, h, 0.03f, alpha = 0.8f)
        val strong = LocalLaplacianFilter.filter(input, w, h, 0.03f, alpha = 0.3f)
        val dWeak = abs(weak[16 * w + 16] - weak[0])
        val dStrong = abs(strong[16 * w + 16] - strong[0])
        assertTrue("更小 alpha 增强更强 $dWeak vs $dStrong", dStrong > dWeak)
    }
}
