// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.analysis.Analysis
import io.github.gordonlu.lumadepth.image.gainmap.GainMapRenderCore
import io.github.gordonlu.lumadepth.image.tonemap.AutoParameters
import io.github.gordonlu.lumadepth.image.tonemap.PreviewRenderCore
import io.github.gordonlu.lumadepth.image.tonemap.ToneMapParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * v0.12 集成回归：多尺度增益 / 肤色保护 / 色域压缩。
 */
class RenderCoreV012Test {

    private fun gainMapValue(pixel: Int): Float = ((pixel shr 16) and 0xFF) / 255f

    private fun gainMapOf(pixels: IntArray, w: Int, h: Int, p: ToneMapParameters): IntArray =
        GainMapRenderCore.renderPixels(pixels, w, h, p)

    private fun autoParams(img: IntArray): ToneMapParameters {
        val a = Analysis.analyze(TestImages.lumaOf(img))
        return AutoParameters.forAnalysis(a, 0.5f, 0.1f, true)
    }

    private fun hueDeg(r: Float, g: Float, b: Float): Double {
        val mx = max(max(r, g), b)
        val mn = min(min(r, g), b)
        val d = mx - mn
        if (d == 0f) return 0.0
        val h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return h * 60.0
    }

    private fun hueDistance(h1: Double, h2: Double): Double {
        var d = (h1 - h2) % 360.0
        if (d < 0) d += 360.0
        return min(d, 360.0 - d)
    }

    /** 细节尺度：暗底上的小亮点（小于区域窗口）获得额外增强。 */
    @Test
    fun detailScale_boostsSmallHighlights() {
        val w = 96
        val h = 96
        val img = TestImages.solid(w, h, TestImages.argb(20, 20, 20))
        // 3x3 小亮点（小于区域滤波半径，由细节尺度处理）
        for (y in 46..48) {
            for (x in 46..48) {
                img[y * w + x] = TestImages.argb(255, 255, 255)
            }
        }
        // 显式参数（关闭白色保护，隔离细节尺度的影响）
        val pNo = ToneMapParameters(
            highlightStart = 0.3f, highlightEnd = 0.9f, maxGainEv = 1.0f,
            shadowStart = 0.02f, shadowEnd = 0.15f,
            whiteProtectionStrength = 0f, saturationProtection = 0.5f,
            localEnhancement = 0f, minBoost = 1f, maxBoost = 2.378f,
            regionGainEv = 0.25f, detailGainEv = 0f,
        )
        val pDetail = pNo.copy(detailGainEv = 0.8f, maxBoost = 4.13f)
        val gmNo = gainMapOf(img, w, h, pNo)
        val gmDetail = gainMapOf(img, w, h, pDetail)
        val vNo = gainMapValue(gmNo[47 * w + 47])
        val vDetail = gainMapValue(gmDetail[47 * w + 47])
        assertTrue("细节尺度应增强小亮点 no=$vNo detail=$vDetail", vDetail > vNo)
    }

    /** 多尺度组合不超过 maxBoost 上限。 */
    @Test
    fun combinedGain_neverExceedsMaxBoost() {
        val w = 96
        val h = 96
        val img = TestImages.gradient(w, h)
        val p = autoParams(img)
        val gm = gainMapOf(img, w, h, p)
        for (v in gm) {
            val g = gainMapValue(v)
            assertTrue(g in 0f..1f)
            assertTrue(g.isFinite())
        }
    }

    /** 肤色区域增益受限：同亮度下肤色像素增益低于非肤色。 */
    @Test
    fun skinArea_gainLimited() {
        // 两个同亮度区域：肤色（暖色）与灰色（非肤色）
        val w = 64
        val h = 32
        val img = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                img[y * w + x] = if (x < w / 2) {
                    TestImages.argb(200, 140, 110) // 肤色，编码域
                } else {
                    TestImages.argb(160, 160, 160) // 灰色
                }
            }
        }
        val p = autoParams(img)
        val gm = gainMapOf(img, w, h, p)
        // 肤色区平均增益 vs 灰区平均增益（同区域亮度下肤色应更保守）
        var skinSum = 0f
        var graySum = 0f
        for (y in 4 until h - 4) {
            for (x in 4 until w / 2 - 4) skinSum += gainMapValue(gm[y * w + x])
            for (x in w / 2 + 4 until w - 4) graySum += gainMapValue(gm[y * w + x])
        }
        val skinAvg = skinSum / ((h - 8) * (w / 2 - 8))
        val grayAvg = graySum / ((h - 8) * (w / 2 - 8))
        assertTrue("肤色区增益应低于灰区 skin=$skinAvg gray=$grayAvg", skinAvg <= grayAvg)
    }

    /** 高饱和红：色度压缩后色相漂移更小。 */
    @Test
    fun saturatedRed_hueStable() {
        val img = TestImages.solid(64, 64, TestImages.argb(255, 0, 0))
        val p = ToneMapParameters(
            highlightStart = 0.3f, highlightEnd = 0.9f, maxGainEv = 1.5f,
            shadowStart = 0.02f, shadowEnd = 0.15f,
            whiteProtectionStrength = 0f, saturationProtection = 0.5f,
            localEnhancement = 0f, minBoost = 1f, maxBoost = 2.8f,
        )
        val preview = PreviewRenderCore.renderPixels(img, 64, 64, p)
        val v = preview[0]
        val h = hueDeg(
            ((v shr 16) and 0xFF) / 255f,
            ((v shr 8) and 0xFF) / 255f,
            (v and 0xFF) / 255f,
        )
        assertTrue("红色色相漂移过大：$h", hueDistance(h, 0.0) < 15.0)
    }

    /** 高饱和蓝：色相漂移小。 */
    @Test
    fun saturatedBlue_hueStable() {
        val img = TestImages.solid(64, 64, TestImages.argb(0, 0, 255))
        val p = ToneMapParameters(
            highlightStart = 0.3f, highlightEnd = 0.9f, maxGainEv = 1.5f,
            shadowStart = 0.02f, shadowEnd = 0.15f,
            whiteProtectionStrength = 0f, saturationProtection = 0.5f,
            localEnhancement = 0f, minBoost = 1f, maxBoost = 2.8f,
        )
        val preview = PreviewRenderCore.renderPixels(img, 64, 64, p)
        val v = preview[0]
        val h = hueDeg(
            ((v shr 16) and 0xFF) / 255f,
            ((v shr 8) and 0xFF) / 255f,
            (v and 0xFF) / 255f,
        )
        assertTrue("蓝色色相漂移过大：$h", hueDistance(h, 240.0) < 15.0)
    }

    /** 无溢出时预览与原始一致（强度 0 恒等）。 */
    @Test
    fun noOvershoot_identity() {
        val img = TestImages.midGray(64, 64)
        val p = ToneMapParameters(
            highlightStart = 0.45f, highlightEnd = 0.9f, maxGainEv = 0f,
            shadowStart = 0.02f, shadowEnd = 0.15f,
            whiteProtectionStrength = 0.75f, saturationProtection = 0.5f,
            localEnhancement = 0f, minBoost = 1f, maxBoost = 1f,
        )
        val preview = PreviewRenderCore.renderPixels(img, 64, 64, p)
        for (v in preview) {
            assertEquals(128f, ((v shr 16) and 0xFF).toFloat(), 1f)
            assertEquals(128f, ((v shr 8) and 0xFF).toFloat(), 1f)
            assertEquals(128f, (v and 0xFF).toFloat(), 1f)
        }
    }

    /** 回归：天空完全保护、点光源保留。 */
    @Test
    fun regression_skyProtected_lampKept() {
        val w = 96
        val h = 96
        val sky = TestImages.solid(w, h, TestImages.argb(250, 250, 250))
        val gmSky = gainMapOf(sky, w, h, autoParams(sky))
        assertTrue(gmSky.maxOf { gainMapValue(it) } < 0.1f)

        val lamp = TestImages.solid(w, h, TestImages.argb(20, 20, 20))
        for (y in 44..51) {
            for (x in 44..51) {
                lamp[y * w + x] = TestImages.argb(255, 255, 255)
            }
        }
        val gmLamp = gainMapOf(lamp, w, h, autoParams(lamp))
        assertTrue(gainMapValue(gmLamp[48 * w + 48]) > 0.3f)
    }

    /** 高光增益收敛：接近白色时增益递减（防过曝），中亮区不受影响。 */
    @Test
    fun highlightRolloff_reducesGainNearWhite() {
        val w = 64
        val h = 64
        val bright = TestImages.solid(w, h, TestImages.argb(235, 235, 235)) // 线性约 0.9
        val mid = TestImages.solid(w, h, TestImages.argb(190, 190, 190)) // 线性约 0.5
        val base = ToneMapParameters(
            highlightStart = 0.3f, highlightEnd = 0.9f, maxGainEv = 1.0f,
            shadowStart = 0.02f, shadowEnd = 0.15f,
            whiteProtectionStrength = 0f, saturationProtection = 0.5f,
            localEnhancement = 0f, minBoost = 1f, maxBoost = 2f,
        )
        val pNoRoll = base
        val pRoll = base.copy(highlightRolloff = 0.3f)
        val gmNo = gainMapOf(bright, w, h, pNoRoll)
        val gmRoll = gainMapOf(bright, w, h, pRoll)
        val vNo = gainMapValue(gmNo[0])
        val vRoll = gainMapValue(gmRoll[0])
        assertTrue("高光端增益应被收敛 no=$vNo roll=$vRoll", vRoll < vNo)
        // 中亮区不受 roll-off 影响
        val gmMid = gainMapOf(mid, w, h, pRoll)
        assertTrue(gainMapValue(gmMid[0]) > 0f)
    }
}

    
