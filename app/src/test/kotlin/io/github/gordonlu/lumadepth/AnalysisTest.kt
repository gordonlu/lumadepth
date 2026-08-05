// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.analysis.Analysis
import io.github.gordonlu.lumadepth.image.analysis.AnalysisResult
import io.github.gordonlu.lumadepth.image.tonemap.Srgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal object TestImages {
    fun argb(r: Int, g: Int, b: Int): Int = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

    fun solid(width: Int, height: Int, color: Int): IntArray = IntArray(width * height) { color }

    /** 50% 灰（sRGB 编码 128）。 */
    fun midGray(width: Int, height: Int): IntArray = solid(width, height, argb(128, 128, 128))

    fun gradient(width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = ((x + y) % 256)
                out[y * width + x] = argb(v, v, v)
            }
        }
        return out
    }

    fun lumaOf(pixels: IntArray): FloatArray = FloatArray(pixels.size) { i ->
        val a = pixels[i]
        Srgb.luminanceLinear(
            Srgb.toLinear(((a shr 16) and 0xFF) / 255f),
            Srgb.toLinear(((a shr 8) and 0xFF) / 255f),
            Srgb.toLinear((a and 0xFF) / 255f),
        )
    }

    /** 转置（模拟 90° 旋转后的像素布局）。返回 (转置像素, 新宽, 新高)。 */
    fun transpose(pixels: IntArray, width: Int, height: Int): Triple<IntArray, Int, Int> {
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[x * height + y] = pixels[y * width + x]
            }
        }
        return Triple(out, height, width)
    }
}

class AnalysisTest {

    @Test
    fun allBlack_returnsZeroLumaStats() {
        val a = Analysis.analyze(TestImages.lumaOf(TestImages.solid(64, 64, 0xFF000000.toInt())))
        assertEquals(0f, a.p50, 1e-4f)
        assertEquals(1f, a.blackFraction, 1e-4f)
        assertEquals(0f, a.whiteFraction, 1e-4f)
        assertEquals(0f, a.dynamicRangeStops, 1e-4f)
        assertTrue(a.p99.isFinite())
    }

    @Test
    fun allWhite_returnsWhiteStats() {
        val a = Analysis.analyze(TestImages.lumaOf(TestImages.solid(64, 64, TestImages.argb(255, 255, 255))))
        assertEquals(1f, a.p50, 1e-4f)
        assertEquals(1f, a.whiteFraction, 1e-4f)
        assertTrue(a.p99.isFinite())
    }

    @Test
    fun midGray_percentilesAroundHalf() {
        val a = Analysis.analyze(TestImages.lumaOf(TestImages.midGray(64, 64)))
        assertEquals(0.214f, a.p50, 0.01f)
    }

    @Test
    fun lowContrast_hasSmallDynamicRange() {
        val low = Analysis.analyze(TestImages.lumaOf(TestImages.solid(64, 64, TestImages.argb(120, 120, 120))))
        val high = Analysis.analyze(TestImages.lumaOf(TestImages.gradient(128, 128)))
        assertTrue(low.dynamicRangeStops < high.dynamicRangeStops)
    }

    @Test
    fun highContrast_hasLargerDynamicRangeThanFlat() {
        val flat = Analysis.analyze(TestImages.lumaOf(TestImages.midGray(64, 64)))
        val strong = TestImages.solid(64, 32, 0xFF000000.toInt()) +
            TestImages.solid(64, 32, TestImages.argb(255, 255, 255))
        val contrast = Analysis.analyze(TestImages.lumaOf(strong))
        assertTrue(contrast.dynamicRangeStops > flat.dynamicRangeStops)
    }

    @Test
    fun largeHighlightArea_detected() {
        // 250/255 编码 ≈ 0.955 线性，属于白色区（≥0.95）
        val img = TestImages.solid(64, 48, TestImages.argb(250, 250, 250)) +
            TestImages.solid(64, 16, 0xFF000000.toInt())
        val a = Analysis.analyze(TestImages.lumaOf(img))
        assertTrue(a.whiteFraction > 0.5f)
    }

    @Test
    fun smallPointSource_notLargeWhiteArea() {
        // 暗底 + 少量亮像素
        val img = TestImages.solid(128, 128, TestImages.argb(20, 20, 20))
        img[64 * 128 + 64] = TestImages.argb(255, 255, 255)
        img[64 * 128 + 65] = TestImages.argb(255, 255, 255)
        val a = Analysis.analyze(TestImages.lumaOf(img))
        assertTrue(a.whiteFraction < 0.02f)
    }

    @Test
    fun landscape_vs_portrait_rotation_sameAnalysis() {
        val pixels = TestImages.gradient(120, 40)
        val a1 = Analysis.analyze(TestImages.lumaOf(pixels))
        val (rotated, newWidth, newHeight) = TestImages.transpose(pixels, 120, 40)
        val a2 = Analysis.analyze(TestImages.lumaOf(rotated))
        assertEquals(newWidth, 40)
        assertEquals(newHeight, 120)
        assertEquals(a1.p50, a2.p50, 1e-3f)
        assertEquals(a1.p99, a2.p99, 1e-3f)
        assertEquals(a1.whiteFraction, a2.whiteFraction, 1e-3f)
    }

    @Test
    fun ultraWide_and_ultraTall_doNotCrash() {
        val wide = Analysis.analyze(TestImages.lumaOf(TestImages.gradient(800, 8)))
        assertTrue(wide.p50.isFinite())
        val tall = Analysis.analyze(TestImages.lumaOf(TestImages.gradient(8, 800)))
        assertTrue(tall.p50.isFinite())
    }

    @Test
    fun clippedFraction_reported() {
        val img = TestImages.solid(64, 64, TestImages.argb(255, 255, 255))
        val a = Analysis.analyze(TestImages.lumaOf(img), clippedFraction = 1f)
        assertEquals(1f, a.clippedFraction, 1e-4f)
    }
}
