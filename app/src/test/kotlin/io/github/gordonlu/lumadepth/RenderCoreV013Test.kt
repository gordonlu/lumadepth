// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.analysis.Analysis
import io.github.gordonlu.lumadepth.image.gainmap.GainMapRenderCore
import io.github.gordonlu.lumadepth.image.tonemap.AutoParameters
import io.github.gordonlu.lumadepth.image.tonemap.ToneMapParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v0.13 集成回归：高质量模式的保边细节增强。
 */
class RenderCoreV013Test {

    private fun gainMapValue(pixel: Int): Float = ((pixel shr 16) and 0xFF) / 255f

    private fun gainMapOf(pixels: IntArray, w: Int, h: Int, p: ToneMapParameters): IntArray =
        GainMapRenderCore.renderPixels(pixels, w, h, p)

    private fun autoParams(img: IntArray, highQuality: Boolean = false): ToneMapParameters {
        val a = Analysis.analyze(TestImages.lumaOf(img))
        return AutoParameters.forAnalysis(a, 0.5f, 0.6f, true, highQuality)
    }

    /** 核心性质：同样幅度的高频随机噪声不被细节增强，真实高频结构被增强。 */
    @Test
    fun detailConfidence_noiseVsStructure() {
        val w = 96
        val h = 96
        val rng = java.util.Random(7)
        // 高频噪声图：0.5 ± 0.03 随机
        val noiseImg = IntArray(w * h)
        for (i in noiseImg.indices) {
            val v = (128 + (rng.nextFloat() - 0.5f) * 2f * 30f).toInt().coerceIn(0, 255)
            noiseImg[i] = TestImages.argb(v, v, v)
        }
        // 高频结构图：4px 周期条纹 155/125（线性差约 0.125，幅度与噪声相当）
        val stripeImg = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = if ((x / 4) % 2 == 0) 155 else 125
                stripeImg[y * w + x] = TestImages.argb(v, v, v)
            }
        }
        val p = autoParams(noiseImg)
        val gmNoise = gainMapOf(noiseImg, w, h, p)
        val gmStripe = gainMapOf(stripeImg, w, h, p)
        var sumNoise = 0L
        var sumStripe = 0L
        for (i in gmNoise.indices) {
            sumNoise += (gmNoise[i] shr 16) and 0xFF
            sumStripe += (gmStripe[i] shr 16) and 0xFF
        }
        val avgNoise = sumNoise.toDouble() / gmNoise.size
        val avgStripe = sumStripe.toDouble() / gmStripe.size
        assertTrue("结构图增益应明显高于噪声图 noise=$avgNoise stripe=$avgStripe", avgStripe > avgNoise + 1.0)
    }

    /** 保边：强边缘处高质量模式不产生光晕（边缘两侧增益差有限）。 */
    @Test
    fun highQuality_noEdgeHalo() {
        val w = 96
        val h = 64
        // 左亮右暗的强边缘
        val img = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                img[y * w + x] = if (x < w / 2) TestImages.argb(230, 230, 230) else TestImages.argb(30, 30, 30)
            }
        }
        val p = autoParams(img, highQuality = true)
        val gm = gainMapOf(img, w, h, p)
        // 检查边缘两侧（距边缘 2px）的增益值：亮侧高、暗侧低，且不存在
        // "亮侧被压暗 + 暗侧被提亮"的过度扩散
        val bright = gainMapValue(gm[32 * w + w / 2 - 2])
        val dark = gainMapValue(gm[32 * w + w / 2 + 2])
        assertTrue("亮侧应保持增强 bright=$bright", bright > 0.1f)
        assertTrue("暗侧应保持低增益 dark=$dark", dark < 0.15f)
        assertTrue("边缘对比应保持", bright - dark > 0.1f)
    }

    /** 回归：高质量模式下全白仍完全保护。 */
    @Test
    fun highQuality_allWhiteStillProtected() {
        val img = TestImages.solid(64, 64, TestImages.argb(255, 255, 255))
        val p = autoParams(img, highQuality = true)
        val gm = gainMapOf(img, 64, 64, p)
        for (v in gm) {
            assertTrue(gainMapValue(v) < 0.05f)
        }
    }

    /** 回归：高质量模式下强度 0 仍接近恒等（经手动参数验证）。 */
    @Test
    fun highQuality_zeroIntensity_identity() {
        val img = TestImages.midGray(64, 64)
        val p = ToneMapParameters(
            highlightStart = 0.45f, highlightEnd = 0.9f, maxGainEv = 0f,
            shadowStart = 0.02f, shadowEnd = 0.15f,
            whiteProtectionStrength = 0.75f, saturationProtection = 0.5f,
            localEnhancement = 0f, minBoost = 1f, maxBoost = 1f,
            highQuality = true,
        )
        val gm = gainMapOf(img, 64, 64, p)
        for (v in gm) {
            assertEquals(0, (v and 0xFFFFFF).toLong())
        }
    }

    /** 回归：点光源在高质量模式下仍保留增强。 */
    @Test
    fun highQuality_pointLightKept() {
        val w = 96
        val h = 96
        val img = TestImages.solid(w, h, TestImages.argb(20, 20, 20))
        for (y in 44..51) {
            for (x in 44..51) {
                img[y * w + x] = TestImages.argb(255, 255, 255)
            }
        }
        val p = autoParams(img, highQuality = true)
        val gm = gainMapOf(img, w, h, p)
        assertTrue(gainMapValue(gm[48 * w + 48]) > 0.3f)
    }
}
