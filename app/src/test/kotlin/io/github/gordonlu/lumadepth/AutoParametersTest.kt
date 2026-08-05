// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.analysis.Analysis
import io.github.gordonlu.lumadepth.image.analysis.AnalysisResult
import io.github.gordonlu.lumadepth.image.tonemap.AutoParameters
import io.github.gordonlu.lumadepth.image.tonemap.ToneMapParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoParametersTest {

    private fun analysisOf(img: IntArray): AnalysisResult = Analysis.analyze(TestImages.lumaOf(img))

    @Test
    fun intensityMonotonicity_maxGainEvIncreases() {
        val a = analysisOf(TestImages.gradient(96, 96))
        val prev = AutoParameters.forAnalysis(a, 0f, 0f, true)
        assertEquals(0f, prev.maxGainEv, 1e-6f)
        var last = prev.maxGainEv
        for (i in 10..100 step 10) {
            val p = AutoParameters.forAnalysis(a, i / 100f, 0.2f, true)
            assertTrue(p.maxGainEv >= last)
            last = p.maxGainEv
        }
    }

    @Test
    fun zeroIntensity_zeroGain_identity() {
        val a = analysisOf(TestImages.gradient(96, 96))
        val p = AutoParameters.forAnalysis(a, 0f, 0f, true)
        assertEquals(0f, p.maxGainEv, 1e-6f)
        assertEquals(1f, p.maxBoost, 1e-6f)
        assertEquals(1f, p.minBoost, 1e-6f)
    }

    @Test
    fun parametersWithinRanges() {
        val a = analysisOf(TestImages.gradient(96, 96))
        val p = AutoParameters.forAnalysis(a, 1f, 1f, true)
        assertTrue(p.highlightStart in 0.30f..0.65f)
        // auto 模式根据直方图动态调整：end = start + 0.30（暗图可低于 0.85）
        assertTrue(p.highlightEnd in 0.60f..1.01f)
        assertTrue(p.highlightEnd >= p.highlightStart)
        assertTrue(p.maxGainEv in 0f..2.5f)
        assertTrue(p.maxBoost in 1f..6f)
    }

    @Test
    fun darkImage_isConservative() {
        val dark = TestImages.solid(96, 96, TestImages.argb(30, 30, 30))
        val bright = TestImages.solid(96, 96, TestImages.argb(220, 220, 220))
        val aDark = analysisOf(dark)
        val aBright = analysisOf(bright)
        val pDark = AutoParameters.forAnalysis(aDark, 0.5f, 0.2f, true)
        val pBright = AutoParameters.forAnalysis(aBright, 0.5f, 0.2f, true)
        assertTrue(aDark.p50 < 0.08f)
        // 暗图 shadowEnd 增强（保护阴影），bright 图 maxGainEv 因白色占比被压低
        assertTrue(pDark.shadowEnd >= pBright.shadowEnd)
    }

    @Test
    fun largeWhiteArea_reducesGain() {
        val whiteImg = TestImages.solid(96, 72, TestImages.argb(250, 250, 250)) +
            TestImages.solid(96, 24, 0xFF000000.toInt())
        val smallImg = TestImages.solid(96, 96, TestImages.argb(180, 180, 180))
        val aWhite = analysisOf(whiteImg)
        val aSmall = analysisOf(smallImg)
        val pWhite = AutoParameters.forAnalysis(aWhite, 0.5f, 0f, true)
        val pSmall = AutoParameters.forAnalysis(aSmall, 0.5f, 0f, true)
        assertTrue(pWhite.maxGainEv <= pSmall.maxGainEv)
        assertTrue(pWhite.whiteProtectionStrength >= pSmall.whiteProtectionStrength)
    }

    @Test
    fun autoMode_localEnhancementHalved() {
        val a = analysisOf(TestImages.gradient(96, 96))
        val auto = AutoParameters.forAnalysis(a, 0.5f, 0.5f, true)
        val manual = AutoParameters.forAnalysis(a, 0.5f, 0.5f, false)
        assertEquals(auto.localEnhancement, manual.localEnhancement * 0.5f, 1e-6f)
    }

    @Test
    fun invalidInputs_doNotProduceNaN() {
        val a = analysisOf(TestImages.gradient(96, 96))
        val p = AutoParameters.forAnalysis(a, Float.NaN, Float.NaN, true)
        assertTrue(p.maxGainEv.isFinite())
        assertTrue(p.maxBoost.isFinite())
        val p2 = AutoParameters.forAnalysis(a, 2f, -1f, true)
        assertTrue(p2.maxGainEv in 0f..2.5f)
        assertTrue(p2.localEnhancement in 0f..1f)
    }

    @Test
    fun extremeImages_produceValidParams() {
        val allBlack = analysisOf(TestImages.solid(96, 96, 0xFF000000.toInt()))
        val allWhite = analysisOf(TestImages.solid(96, 96, TestImages.argb(255, 255, 255)))
        for (a in listOf(allBlack, allWhite)) {
            for (i in listOf(0f, 0.5f, 1f)) {
                val p = AutoParameters.forAnalysis(a, i, 0.3f, true)
                assertTrue(p.maxGainEv.isFinite() && !p.maxGainEv.isNaN())
                assertTrue(p.highlightStart.isFinite())
                assertTrue(p.highlightEnd.isFinite())
            }
        }
    }
}
