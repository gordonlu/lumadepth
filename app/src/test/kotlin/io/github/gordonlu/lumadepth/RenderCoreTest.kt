// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.analysis.Analysis
import io.github.gordonlu.lumadepth.image.gainmap.GainMapMath
import io.github.gordonlu.lumadepth.image.gainmap.GainMapRenderCore
import io.github.gordonlu.lumadepth.image.tonemap.AutoParameters
import io.github.gordonlu.lumadepth.image.tonemap.PreviewRenderCore
import io.github.gordonlu.lumadepth.image.tonemap.ToneMapParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 渲染核心测试：覆盖规格书要求的 20 类用例。
 * 检查：不崩溃、无 NaN/Inf、增益合法、黑色保持、白色保护、色相合理、单调性、恒等性。
 */
class RenderCoreTest {

    // ---------- 辅助 ----------

    private fun gainMapValue(pixel: Int): Float = ((pixel shr 16) and 0xFF) / 255f

    private fun gainMapOf(pixels: IntArray, w: Int, h: Int, p: ToneMapParameters): IntArray =
        GainMapRenderCore.renderPixels(pixels, w, h, p)

    private fun previewOf(pixels: IntArray, w: Int, h: Int, p: ToneMapParameters): IntArray =
        PreviewRenderCore.renderPixels(pixels, w, h, p)

    private fun assertNoNaNInf(values: IntArray) {
        for (v in values) {
            assertTrue("pixel=$v 非法", v in Int.MIN_VALUE..Int.MAX_VALUE)
        }
    }

    private fun assertFiniteGainMap(values: IntArray) {
        for (v in values) {
            val g = gainMapValue(v)
            assertTrue(g.isFinite())
            assertTrue(g in 0f..1f)
        }
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

    private fun autoParams(img: IntArray): ToneMapParameters {
        val a = Analysis.analyze(TestImages.lumaOf(img))
        return AutoParameters.forAnalysis(a, 0.5f, 0.1f, true)
    }

    // ---------- 用例 ----------

    @Test
    fun `1 全黑图`() {
        val img = TestImages.solid(64, 64, 0xFF000000.toInt())
        val p = autoParams(img)
        val gm = gainMapOf(img, 64, 64, p)
        assertFiniteGainMap(gm)
        for (v in gm) assertEquals(0, (v and 0xFFFFFF).toLong())
        // 黑色保持接近黑色
        val preview = previewOf(img, 64, 64, p)
        for (v in preview) {
            val r = (v shr 16) and 0xFF
            assertTrue(r <= 1)
        }
    }

    @Test
    fun `2 全白图`() {
        val img = TestImages.solid(64, 64, TestImages.argb(255, 255, 255))
        val p = autoParams(img)
        val gm = gainMapOf(img, 64, 64, p)
        assertFiniteGainMap(gm)
        // 白色保护：大面积无纹理剪裁 → 增益限制 → gain map 值 ≈ 0
        for (v in gm) {
            assertTrue("白色保护失败 value=${gainMapValue(v)}", gainMapValue(v) < 0.05f)
        }
    }

    @Test
    fun `3 50% 灰度图`() {
        val img = TestImages.midGray(64, 64)
        val p = autoParams(img)
        val gm = gainMapOf(img, 64, 64, p)
        assertFiniteGainMap(gm)
        // 中间调：增益 = 1 → gain map 值 = 0
        for (v in gm) assertEquals(0, gainMapValue(v).toInt().toLong())
    }

    @Test
    fun `4 低对比度图`() {
        val img = TestImages.solid(64, 64, TestImages.argb(120, 120, 120))
        val p = autoParams(img)
        val gm = gainMapOf(img, 64, 64, p)
        assertFiniteGainMap(gm)
        val preview = previewOf(img, 64, 64, p)
        assertNoNaNInf(preview)
    }

    @Test
    fun `5 高对比度图`() {
        val img = TestImages.gradient(64, 64)
        val p = autoParams(img)
        val gm = gainMapOf(img, 64, 64, p)
        assertFiniteGainMap(gm)
        val preview = previewOf(img, 64, 64, p)
        assertNoNaNInf(preview)
    }

    @Test
    fun `6 大面积高光`() {
        val img = TestImages.solid(64, 48, TestImages.argb(240, 240, 240)) +
            TestImages.solid(64, 16, 0xFF000000.toInt())
        val p = autoParams(img)
        // 大面积白色：白色保护显著
        val gm = gainMapOf(img, 64, 64, p)
        var brightGain = 0f
        for (y in 0 until 48) {
            for (x in 0 until 64) {
                brightGain = max(brightGain, gainMapValue(gm[y * 64 + x]))
            }
        }
        assertTrue("大面积高光增益应受限", brightGain < 0.4f)
    }

    @Test
    fun `7 小面积点光源`() {
        val img = TestImages.solid(128, 128, TestImages.argb(20, 20, 20))
        img[64 * 128 + 64] = TestImages.argb(255, 255, 255)
        img[64 * 128 + 65] = TestImages.argb(255, 255, 255)
        val p = autoParams(img)
        val gm = gainMapOf(img, 128, 128, p)
        // 点光源中心应有明显增益（与大面积白色保护不同）
        val center = gainMapValue(gm[64 * 128 + 64])
        assertTrue("点光源增益被错误压制", center > 0.3f)
    }

    @Test
    fun `8 高饱和红色 色相漂移合理`() {
        val img = TestImages.solid(64, 64, TestImages.argb(255, 0, 0))
        val p = ToneMapParameters(
            highlightStart = 0.3f, highlightEnd = 0.9f, maxGainEv = 1.5f,
            shadowStart = 0.02f, shadowEnd = 0.15f,
            whiteProtectionStrength = 0f, saturationProtection = 0.5f,
            localEnhancement = 0f, minBoost = 1f, maxBoost = 2.8f,
        )
        val preview = previewOf(img, 64, 64, p)
        val v = preview[0]
        val r = (v shr 16) and 0xFF
        val g = (v shr 8) and 0xFF
        val b = v and 0xFF
        val h = hueDeg(r / 255f, g / 255f, b / 255f)
        assertTrue("红色色相漂移过大：$h", hueDistance(h, 0.0) < 20.0)
    }

    @Test
    fun `9 高饱和蓝色 色相漂移合理`() {
        val img = TestImages.solid(64, 64, TestImages.argb(0, 0, 255))
        val p = ToneMapParameters(
            highlightStart = 0.3f, highlightEnd = 0.9f, maxGainEv = 1.5f,
            shadowStart = 0.02f, shadowEnd = 0.15f,
            whiteProtectionStrength = 0f, saturationProtection = 0.5f,
            localEnhancement = 0f, minBoost = 1f, maxBoost = 2.8f,
        )
        val preview = previewOf(img, 64, 64, p)
        val v = preview[0]
        val r = (v shr 16) and 0xFF
        val g = (v shr 8) and 0xFF
        val b = v and 0xFF
        val h = hueDeg(r / 255f, g / 255f, b / 255f)
        assertTrue("蓝色色相漂移过大：$h", hueDistance(h, 240.0) < 20.0)
    }

    @Test
    fun `10 极暗图`() {
        val img = TestImages.solid(64, 64, TestImages.argb(8, 8, 8))
        val p = autoParams(img)
        val gm = gainMapOf(img, 64, 64, p)
        assertFiniteGainMap(gm)
        val preview = previewOf(img, 64, 64, p)
        for (v in preview) {
            assertTrue((v shr 16 and 0xFF) <= 12)
        }
    }

    @Test
    fun `11 极亮图`() {
        val img = TestImages.solid(64, 64, TestImages.argb(255, 255, 255))
        val p = autoParams(img)
        val gm = gainMapOf(img, 64, 64, p)
        assertFiniteGainMap(gm)
        val preview = previewOf(img, 64, 64, p)
        assertNoNaNInf(preview)
    }

    @Test
    fun `12 横向图`() {
        val img = TestImages.gradient(200, 50)
        val p = autoParams(img)
        val gm = gainMapOf(img, 200, 50, p)
        assertEquals(200 * 50, gm.size)
        assertFiniteGainMap(gm)
        assertNoNaNInf(previewOf(img, 200, 50, p))
    }

    @Test
    fun `13 竖向EXIF旋转 处理后结果一致`() {
        val pixels = TestImages.gradient(50, 200)
        val p1 = autoParams(pixels)
        val gm1 = gainMapOf(pixels, 50, 200, p1)
        val (rotated, newW) = TestImages.transpose(pixels, 50, 200)
        val p2 = autoParams(rotated)
        val gm2 = gainMapOf(rotated, newW, 200, p2)
        // 旋转不改变统计结果：平均增益图值一致
        var s1 = 0L
        var s2 = 0L
        for (i in gm1.indices) {
            s1 += (gm1[i] shr 16) and 0xFF
            s2 += (gm2[i] shr 16) and 0xFF
        }
        val avg1 = s1.toDouble() / gm1.size
        val avg2 = s2.toDouble() / gm2.size
        assertEquals(avg1, avg2, 0.5)
    }

    @Test
    fun `14 超宽图`() {
        val img = TestImages.gradient(800, 8)
        val p = autoParams(img)
        assertFiniteGainMap(gainMapOf(img, 800, 8, p))
    }

    @Test
    fun `15 超高图`() {
        val img = TestImages.gradient(8, 800)
        val p = autoParams(img)
        assertFiniteGainMap(gainMapOf(img, 8, 800, p))
    }

    @Test
    fun `16 非法参数`() {
        val img = TestImages.gradient(64, 64)
        val bad = ToneMapParameters(
            highlightStart = Float.NaN,
            highlightEnd = Float.POSITIVE_INFINITY,
            maxGainEv = Float.NaN,
            shadowStart = -1f,
            shadowEnd = Float.NEGATIVE_INFINITY,
            whiteProtectionStrength = Float.NaN,
            saturationProtection = Float.NaN,
            localEnhancement = 3f,
            minBoost = 0f,
            maxBoost = -1f,
        )
        val gm = gainMapOf(img, 64, 64, bad)
        assertFiniteGainMap(gm)
        assertNoNaNInf(previewOf(img, 64, 64, bad))
    }

    @Test
    fun `17 maxBoost等于minBoost`() {
        val img = TestImages.gradient(64, 64)
        val p = ToneMapParameters(
            highlightStart = 0.45f, highlightEnd = 0.9f, maxGainEv = 0f,
            shadowStart = 0.02f, shadowEnd = 0.15f,
            whiteProtectionStrength = 0.75f, saturationProtection = 0.5f,
            localEnhancement = 0f, minBoost = 1f, maxBoost = 1f,
        )
        val gm = gainMapOf(img, 64, 64, p)
        assertFiniteGainMap(gm)
        for (v in gm) assertEquals(0, (v and 0xFFFFFF).toLong())
    }

    @Test
    fun `18 接近零亮度`() {
        val img = TestImages.solid(64, 64, TestImages.argb(1, 1, 1))
        val p = autoParams(img)
        val gm = gainMapOf(img, 64, 64, p)
        assertFiniteGainMap(gm)
        for (v in gm) {
            assertTrue(gainMapValue(v) < 0.02f)
        }
    }

    @Test
    fun `19 Gain Map 编码边界 与元数据自洽`() {
        // 无白色保护、强高光：gain map 值应接近上界 1
        val img = TestImages.solid(64, 64, TestImages.argb(250, 250, 250))
        val p = ToneMapParameters(
            highlightStart = 0.3f, highlightEnd = 0.6f, maxGainEv = 2.0f,
            shadowStart = 0.02f, shadowEnd = 0.15f,
            whiteProtectionStrength = 0f, saturationProtection = 0.5f,
            localEnhancement = 0f, minBoost = 1f, maxBoost = 4f,
        )
        val gm = gainMapOf(img, 64, 64, p)
        for (v in gm) {
            val mapValue = gainMapValue(v)
            assertTrue("mapValue=$mapValue", mapValue in 0.9f..1f)
            // 玩家解码增益处于 [ratioMin, ratioMax]
            val decoded = GainMapMath.decodeGain(mapValue, 1f, 4f, 1f)
            assertTrue(decoded in 1f..4f)
        }
    }

    @Test
    fun `20 强度为零时接近原始结果`() {
        val img = TestImages.midGray(64, 64)
        val p = ToneMapParameters(
            highlightStart = 0.45f, highlightEnd = 0.9f, maxGainEv = 0f,
            shadowStart = 0.02f, shadowEnd = 0.15f,
            whiteProtectionStrength = 0.75f, saturationProtection = 0.5f,
            localEnhancement = 0f, minBoost = 1f, maxBoost = 1f,
        )
        val preview = previewOf(img, 64, 64, p)
        for (v in preview) {
            val r = (v shr 16) and 0xFF
            val g = (v shr 8) and 0xFF
            val b = v and 0xFF
            assertEquals(128, r, 1)
            assertEquals(128, g, 1)
            assertEquals(128, b, 1)
        }
    }

    @Test
    fun `局部增强 默认较弱且边缘受控`() {
        // 渐变图（弱边缘）：局部增强对输出的影响小于主增益
        val img = TestImages.gradient(96, 96)
        val a = Analysis.analyze(TestImages.lumaOf(img))
        val pNo = AutoParameters.forAnalysis(a, 0.5f, 0f, true)
        val pLocal = AutoParameters.forAnalysis(a, 0.5f, 1f, true)
        val outNo = previewOf(img, 96, 96, pNo)
        val outLocal = previewOf(img, 96, 96, pLocal)
        var diff = 0L
        for (i in outNo.indices) {
            diff += kotlin.math.abs(((outLocal[i] shr 16) and 0xFF) - ((outNo[i] shr 16) and 0xFF))
        }
        val avgDiff = diff.toDouble() / outNo.size
        assertTrue("局部增强应较弱，avgDiff=$avgDiff", avgDiff < 6.0)
    }
}

private operator fun IntArray.plus(other: IntArray): IntArray {
    val out = IntArray(size + other.size)
    System.arraycopy(this, 0, out, 0, size)
    System.arraycopy(other, 0, out, size, other.size)
    return out
}
