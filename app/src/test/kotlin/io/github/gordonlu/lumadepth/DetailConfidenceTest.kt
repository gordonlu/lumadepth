// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.analysis.DetailConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * 连续细节置信度测试。
 * 核心性质：增加噪声不会增加细节增益；真实结构在噪声中仍被识别；
 * 点光源/彩噪/暗部行为符合设计。
 */
class DetailConfidenceTest {

    private val rng = java.util.Random(42)

    private fun gray(v: Float): FloatArray = FloatArray(v.toInt()) { 0f }

    private fun lumaNoiseImage(w: Int, h: Int, amplitude: Float): FloatArray {
        // 平场 0.5 + 均匀噪声
        return FloatArray(w * h) { 0.5f + (rng.nextFloat() - 0.5f) * 2f * amplitude }
    }

    private fun structurePlusNoise(w: Int, h: Int, amplitude: Float): FloatArray {
        val y = lumaNoiseImage(w, h, amplitude)
        // 2px 宽水平细线（0.5 -> 0.6，对比显著高于噪声）作为真实高频结构
        for (yy in h / 2 - 1..h / 2) {
            for (xx in 0 until w) {
                y[yy * w + xx] = 0.6f + (rng.nextFloat() - 0.5f) * 2f * amplitude
            }
        }
        return y
    }

    private fun confOf(y: FloatArray, w: Int, h: Int): FloatArray {
        // 灰度图：r = g = b = y
        return DetailConfidence.compute(y, y, y, y, w, h)
    }

    /** 性质 1：纯噪声图，增大噪声幅度不应增加细节置信度。 */
    @Test
    fun pureNoise_moreNoise_doesNotIncreaseConfidence() {
        val w = 96
        val h = 96
        val cSmall = confOf(lumaNoiseImage(w, h, 0.01f), w, h)
        val cLarge = confOf(lumaNoiseImage(w, h, 0.05f), w, h)
        val meanSmall = cSmall.average().toFloat()
        val meanLarge = cLarge.average().toFloat()
        assertTrue(
            "噪声增大不应提高置信度 small=$meanSmall large=$meanLarge",
            meanLarge <= meanSmall + 0.02f,
        )
        // 纯噪声整体置信度低（弱于真实结构）
        assertTrue("纯噪声置信度应低 small=$meanSmall", meanSmall < 0.15f)
    }

    /** 性质 2：结构 + 噪声：结构区域置信度明显高于纯噪声区域。 */
    @Test
    fun structurePlusNoise_structureHasHigherConfidence() {
        val w = 96
        val h = 96
        val img = structurePlusNoise(w, h, 0.02f)
        val c = confOf(img, w, h)
        var structureSum = 0f
        var noiseSum = 0f
        var sCount = 0
        var nCount = 0
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                val inLine = yy in h / 2 - 3 until h / 2 + 3
                if (inLine) {
                    structureSum += c[yy * w + xx]
                    sCount++
                } else {
                    noiseSum += c[yy * w + xx]
                    nCount++
                }
            }
        }
        val meanStructure = structureSum / sCount
        val meanNoise = noiseSum / nCount
        assertTrue(
            "结构区域置信度应更高 structure=$meanStructure noise=$meanNoise",
            meanStructure > meanNoise + 0.05f,
        )
        assertTrue("结构置信度应显著", meanStructure > 0.12f)
    }

    /** 性质 3：暗底亮点（点光源通路）置信度高。 */
    @Test
    fun darkSpot_pointLightPath_highConfidence() {
        val w = 96
        val h = 96
        val y = FloatArray(w * h) { 0.02f }
        for (yy in h / 2 - 3 until h / 2 + 3) {
            for (xx in w / 2 - 3 until w / 2 + 3) {
                y[yy * w + xx] = 0.6f
            }
        }
        val c = confOf(y, w, h)
        val center = c[(h / 2) * w + (w / 2)]
        assertTrue("点光源置信度应高 center=$center", center > 0.5f)
    }

    /** 性质 4：暗部彩噪（色度波动大、亮度残差小）置信度被抑制。 */
    @Test
    fun chromaNoise_suppressed() {
        val w = 64
        val h = 64
        val n = w * h
        // 暗部平场亮度，色度随机波动（彩噪）
        val y = FloatArray(n) { 0.03f }
        val r = FloatArray(n) { 0.03f }
        val g = FloatArray(n) { 0.03f }
        val b = FloatArray(n) { 0.03f }
        for (i in 0 until n) {
            val jitter = (rng.nextFloat() - 0.5f) * 0.08f
            r[i] = (0.03f + jitter).coerceAtLeast(0f)
            g[i] = (0.03f - jitter * 0.5f).coerceAtLeast(0f)
            b[i] = (0.03f - jitter * 0.5f).coerceAtLeast(0f)
        }
        // 同幅度亮度结构（灰阶扰动）作为对照：色度不变
        val yStruct = FloatArray(n) { 0.03f }
        for (i in 0 until n) {
            yStruct[i] = 0.03f + (rng.nextFloat() - 0.5f) * 0.03f
        }
        val cChroma = DetailConfidence.compute(y, r, g, b, w, h)
        val cLuma = DetailConfidence.compute(yStruct, yStruct, yStruct, yStruct, w, h)
        val meanChroma = cChroma.average().toFloat()
        val meanLuma = cLuma.average().toFloat()
        assertTrue(
            "色噪应被抑制 chroma=$meanChroma luma=$meanLuma",
            meanChroma <= meanLuma + 0.02f,
        )
    }

    /** 性质 5：暗部保护：同幅度结构在暗部置信度更低。 */
    @Test
    fun darkRegion_moreConservative() {
        val w = 64
        val h = 64
        val dark = FloatArray(w * h) { 0.05f }
        val bright = FloatArray(w * h) { 0.4f }
        for (i in 0 until w * h step 3) {
            dark[i] += 0.02f
            bright[i] += 0.02f
        }
        val cDark = confOf(dark, w, h)
        val cBright = confOf(bright, w, h)
        val meanDark = cDark.average().toFloat()
        val meanBright = cBright.average().toFloat()
        assertTrue("暗部应更保守 dark=$meanDark bright=$meanBright", meanDark <= meanBright)
    }

    /** 性质 6：单像素孤立噪声不产生高置信度（无邻域支持）。 */
    @Test
    fun isolatedSinglePixel_lowConfidence() {
        val w = 64
        val h = 64
        val y = FloatArray(w * h) { 0.4f }
        // 单个孤立亮点
        y[32 * w + 32] = 0.6f
        val c = confOf(y, w, h)
        val center = c[32 * w + 32]
        assertTrue("孤立单像素置信度应低 center=$center", center < 0.3f)
    }

    /** 性质 7：输出有限、任意输入安全。 */
    @Test
    fun outputsFinite() {
        val w = 48
        val h = 48
        val n = w * h
        val y = FloatArray(n) { 0.5f }
        val r = FloatArray(n) { 0.5f }
        val g = FloatArray(n) { 0.5f }
        val b = FloatArray(n) { 0.5f }
        y[10] = Float.NaN
        r[20] = Float.POSITIVE_INFINITY
        val c = DetailConfidence.compute(y, r, g, b, w, h)
        for (v in c) {
            assertTrue(v.isFinite())
            assertTrue(v in 0f..1f)
        }
    }
}
