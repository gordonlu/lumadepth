package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.gainmap.GainMapMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class GainMapMathTest {

    @Test
    fun zeroLuma_zeroGainValue() {
        // 黑色像素：gain map 值应为 0（解码后 gain = ratioMin = 1）
        val v = GainMapMath.gainMapValueForPixel(sdrY = 0f, toneGain = 2f, minBoost = 1f, maxBoost = 2.2f, gamma = 1f)
        assertEquals(0f, v, 1e-4f)
    }

    @Test
    fun fullGain_encodedToOne() {
        // sdrY=1, toneGain=maxBoost → hdrY=maxBoost → logGain=log2(maxBoost) → value=1
        val maxBoost = 2.2f
        val v = GainMapMath.gainMapValueForPixel(sdrY = 1f, toneGain = maxBoost, minBoost = 1f, maxBoost = maxBoost, gamma = 1f)
        assertEquals(1f, v, 1e-3f)
    }

    @Test
    fun gainOne_encodedToZero() {
        val v = GainMapMath.gainMapValueForPixel(sdrY = 0.5f, toneGain = 1f, minBoost = 1f, maxBoost = 2.2f, gamma = 1f)
        assertEquals(0f, v, 1e-4f)
    }

    @Test
    fun decodeMatchesEncoding() {
        val minBoost = 1f
        val maxBoost = 2.2f
        for (mapValue in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val gain = GainMapMath.decodeGain(mapValue, minBoost, maxBoost, gamma = 1f)
            assertTrue(gain >= minBoost)
            assertTrue(gain <= maxBoost + 1e-3f)
            // 编码还原
            val reEncoded = GainMapMath.normalizedValue(
                kotlin.math.ln(gain) / kotlin.math.ln(2f),
                minBoost,
                maxBoost,
                1f,
            )
            assertEquals(mapValue, reEncoded, 0.02f)
        }
    }

    @Test
    fun maxBoostEqualsMinBoost_noDivisionByZero() {
        val v = GainMapMath.normalizedValue(log2Gain = 1f, minBoost = 1f, maxBoost = 1f, gamma = 1f)
        assertEquals(0f, v, 1e-6f)
        assertTrue(v.isFinite())
    }

    @Test
    fun outOfRangeValues_clampedToUnitRange() {
        val vHigh = GainMapMath.normalizedValue(log2Gain = 99f, minBoost = 1f, maxBoost = 2f, gamma = 1f)
        assertEquals(1f, vHigh, 1e-6f)
        val vLow = GainMapMath.normalizedValue(log2Gain = -99f, minBoost = 1f, maxBoost = 2f, gamma = 1f)
        assertEquals(0f, vLow, 1e-6f)
    }

    @Test
    fun invalidInputs_safeOutputs() {
        assertEquals(0f, GainMapMath.normalizedValue(Float.NaN, 1f, 2f, 1f), 1e-6f)
        assertEquals(0f, GainMapMath.normalizedValue(1f, Float.NaN, 2f, 1f), 1e-6f)
        assertEquals(0f, GainMapMath.normalizedValue(1f, 1f, Float.POSITIVE_INFINITY, 1f), 1e-6f)
        assertEquals(0f, GainMapMath.log2Gain(Float.NaN, 1f), 1e-6f)
        assertEquals(1f, GainMapMath.decodeGain(Float.NaN, 1f, 2f, 1f), 1e-6f)
        assertEquals(1f, GainMapMath.decodeGain(0.5f, 0f, 2f, 1f), 1e-6f)
    }

    @Test
    fun gammaCurve_applied() {
        // gamma=2 时，mapValue=0.5 → pow(0.5,2)=0.25 → 解码增益偏小
        val g1 = GainMapMath.decodeGain(0.5f, 1f, 4f, gamma = 1f)
        val g2 = GainMapMath.decodeGain(0.5f, 1f, 4f, gamma = 2f)
        assertTrue(g2 < g1)
    }

    @Test
    fun epsilonPreventsDivisionByZero() {
        val v = GainMapMath.log2Gain(sdrY = 0f, hdrY = 0f)
        assertEquals(0f, v, 1e-6f)
        assertTrue(v.isFinite())
    }

    @Test
    fun nearZeroLuma_bounded() {
        // 接近零亮度：增益有界（eps 保护），归一化值有界
        val v = GainMapMath.gainMapValueForPixel(sdrY = 1e-8f, toneGain = 1f, minBoost = 1f, maxBoost = 2.2f, gamma = 1f)
        assertTrue(v.isFinite())
        assertTrue(v in 0f..1f)
    }

    @Test
    fun mapping_consistentWithPlayerFormula() {
        // 玩家：L = mix(log(ratioMin), log(ratioMax), pow(G, gamma))；gain = exp(L)
        // 我们写入的 G 应使玩家解码出我们想要的增益
        val minBoost = 1f
        val maxBoost = 2.5f
        val sdrY = 0.7f
        val toneGain = 1.8f
        val g = GainMapMath.gainMapValueForPixel(sdrY, toneGain, minBoost, maxBoost, 1f)
        val decoded = GainMapMath.decodeGain(g, minBoost, maxBoost, 1f)
        // 玩家侧增益 = 线性增益（与 LumaDepth 目标一致）
        val expectedLinearGain = (sdrY * toneGain + GainMapMath.EPSILON_HDR) / (sdrY + GainMapMath.EPSILON_SDR)
        assertEquals(expectedLinearGain, decoded, 0.02f)
    }
}
