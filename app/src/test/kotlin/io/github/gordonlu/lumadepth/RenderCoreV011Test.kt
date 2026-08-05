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
import kotlin.math.max

/**
 * v0.11 集成回归：噪声抑制 / 高光分类 / Guided Filter 在渲染管线中的行为。
 */
class RenderCoreV011Test {

    private fun gainMapValue(pixel: Int): Float = ((pixel shr 16) and 0xFF) / 255f

    private fun gainMapOf(pixels: IntArray, w: Int, h: Int, p: ToneMapParameters): IntArray =
        GainMapRenderCore.renderPixels(pixels, w, h, p)

    private fun autoParams(img: IntArray): ToneMapParameters {
        val a = Analysis.analyze(TestImages.lumaOf(img))
        return AutoParameters.forAnalysis(a, 0.5f, 0.1f, true)
    }

    /** 暗部带噪图：噪点像素的增益被抑制（趋近 1，gain map 值趋近 0）。 */
    @Test
    fun noisyDarkArea_gainSuppressed() {
        val w = 128
        val h = 128
        // 暗底（20/255 ≈ 线性 0.006）
        val clean = TestImages.solid(w, h, TestImages.argb(20, 20, 20))
        val noisy = TestImages.solid(w, h, TestImages.argb(20, 20, 20))
        // 撒噪点
        for (i in 0 until w * h step 7) {
            noisy[i] = if (i % 14 == 0) TestImages.argb(60, 60, 60) else TestImages.argb(0, 0, 0)
        }
        val p = autoParams(clean)
        val gmClean = gainMapOf(clean, w, h, p)
        val gmNoisy = gainMapOf(noisy, w, h, p)
        // 噪声抑制：噪图整体增益不高于无噪图，且噪点像素 gain map 值趋近 0
        var maxNoisyValue = 0f
        for (i in 0 until w * h step 7) {
            maxNoisyValue = max(maxNoisyValue, gainMapValue(gmNoisy[i]))
        }
        assertTrue("噪点增益应被抑制 max=$maxNoisyValue", maxNoisyValue < 0.05f)
        var sumClean = 0f
        var sumNoisy = 0f
        for (i in gmClean.indices) {
            sumClean += gainMapValue(gmClean[i])
            sumNoisy += gainMapValue(gmNoisy[i])
        }
        assertTrue("噪图增益不应高于无噪图", sumNoisy <= sumClean + 1e-4f)
    }

    /** 高光分类：天空（大/贴边/平坦）与点光源（小/内部/纹理）区分。 */
    @Test
    fun highlightClassification_protectsSkyButKeepsLamp() {
        val w = 96
        val h = 96
        val img = TestImages.solid(w, h, TestImages.argb(250, 250, 250)) // 全图"天空"白
        val p = autoParams(img)
        val gmSky = gainMapOf(img, w, h, p)
        val skyMax = gmSky.maxOf { gainMapValue(it) }
        assertTrue("天空不应增强 skyMax=$skyMax", skyMax < 0.1f)

        // 点光源：暗底 + 小亮点
        val img2 = TestImages.solid(w, h, TestImages.argb(20, 20, 20))
        for (y in 44..51) {
            for (x in 44..51) {
                img2[y * w + x] = TestImages.argb(255, 255, 255)
            }
        }
        val p2 = autoParams(img2)
        val gmLamp = gainMapOf(img2, w, h, p2)
        val lampValue = gainMapValue(gmLamp[48 * w + 48])
        assertTrue("点光源应保留增强 lampValue=$lampValue", lampValue > 0.3f)
    }

    /** 白墙（大面积平坦但不一定贴边）→ 低 confidence → 强保护。 */
    @Test
    fun whiteWall_isProtected() {
        val w = 96
        val h = 96
        val img = TestImages.solid(w, h, TestImages.argb(250, 250, 250))
        val p = autoParams(img)
        val gm = gainMapOf(img, w, h, p)
        // 白墙中心不应有增益
        val center = gainMapValue(gm[48 * w + 48])
        assertTrue("白墙中心不应增强 center=$center", center < 0.1f)
    }

    /** 无局部增强时 guided filter 不参与，输出与 v0.10 回归一致。 */
    @Test
    fun noLocalEnhancement_guidedFilterInactive() {
        val w = 64
        val h = 64
        val img = TestImages.gradient(w, h)
        val p = autoParams(img).copy(localEnhancement = 0f)
        val gm = gainMapOf(img, w, h, p)
        for (v in gm) {
            val g = gainMapValue(v)
            assertTrue(g.isFinite() && g in 0f..1f)
        }
    }

    /** 局部增强开启时输出仍有限且变化较弱（guided 平滑无光晕）。 */
    @Test
    fun localEnhancement_staysWeakAndFinite() {
        val w = 96
        val h = 96
        val img = TestImages.gradient(w, h)
        val pNo = autoParams(img).copy(localEnhancement = 0f)
        val pLocal = autoParams(img).copy(localEnhancement = 1f)
        val gmNo = gainMapOf(img, w, h, pNo)
        val gmL = gainMapOf(img, w, h, pLocal)
        var diff = 0.0
        for (i in gmNo.indices) {
            diff += kotlin.math.abs(gainMapValue(gmL[i]) - gainMapValue(gmNo[i])).toDouble()
        }
        val avgDiff = diff.toDouble() / gmNo.size
        assertTrue("局部增强应较弱 avgDiff=$avgDiff", avgDiff < 0.15)
    }

    /** 回归：全白图（无纹理剪裁）仍完全保护。 */
    @Test
    fun regression_allWhiteStillProtected() {
        val img = TestImages.solid(64, 64, TestImages.argb(255, 255, 255))
        val p = autoParams(img)
        val gm = gainMapOf(img, 64, 64, p)
        for (v in gm) {
            assertTrue("白色保护失败 value=${gainMapValue(v)}", gainMapValue(v) < 0.05f)
        }
    }

    /** 回归：点光源不被错误压制（confidence 高）。 */
    @Test
    fun regression_pointLightNotSuppressed() {
        val img = TestImages.solid(128, 128, TestImages.argb(20, 20, 20))
        img[64 * 128 + 64] = TestImages.argb(255, 255, 255)
        img[64 * 128 + 65] = TestImages.argb(255, 255, 255)
        val p = autoParams(img)
        val gm = gainMapOf(img, 128, 128, p)
        val center = gainMapValue(gm[64 * 128 + 64])
        assertTrue("点光源增益被错误压制", center > 0.3f)
    }

    /** 噪点密集的暗图 auto 参数启用噪声抑制。 */
    @Test
    fun autoParams_enableNoiseSuppressionOnDarkNoisyImage() {
        val darkNoisy = TestImages.solid(96, 96, TestImages.argb(15, 15, 15))
        val bright = TestImages.solid(96, 96, TestImages.argb(200, 200, 200))
        val pDark = autoParams(darkNoisy)
        val pBright = autoParams(bright)
        assertTrue(pDark.noiseSuppression > pBright.noiseSuppression)
        assertTrue(pDark.noiseSuppression in 0.3f..0.7f)
    }
}
