package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.tonemap.InverseTonemap
import io.github.gordonlu.lumadepth.image.tonemap.ToneMapParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InverseTonemapTest {

    private val params = ToneMapParameters(
        highlightStart = 0.45f,
        highlightEnd = 0.90f,
        maxGainEv = 1.1f,
        shadowStart = 0.02f,
        shadowEnd = 0.15f,
        whiteProtectionStrength = 0.75f,
        saturationProtection = 0.5f,
        localEnhancement = 0f,
        minBoost = 1f,
        maxBoost = kotlin.math.pow(2.0, 1.1).toFloat(),
    )

    @Test
    fun blackStaysBlack_gainApproachesOne() {
        val gain = InverseTonemap.baseGainFor(0f, params)
        assertEquals(1f, gain, 1e-4f)
    }

    @Test
    fun nearZeroLuma_gainStaysNearOne() {
        val gain = InverseTonemap.baseGainFor(1e-6f, params)
        assertEquals(1f, gain, 1e-3f)
        assertTrue(gain.isFinite())
    }

    @Test
    fun monotonicInLuma() {
        var last = InverseTonemap.baseGainFor(0f, params)
        for (y in 1..100) {
            val g = InverseTonemap.baseGainFor(y / 100f, params)
            assertTrue(g >= last - 1e-5f)
            last = g
        }
    }

    @Test
    fun gainsWithinRange() {
        for (y in 0..100) {
            val g = InverseTonemap.baseGainFor(y / 100f, params)
            assertTrue(g >= 1f)
            assertTrue(g <= kotlin.math.pow(2.0, 1.1).toFloat() + 1e-3f)
            assertTrue(g.isFinite())
        }
    }

    @Test
    fun shadowProtection_reducesGainInShadows() {
        val shadowGain = InverseTonemap.baseGainFor(0.05f, params)
        val highlightGain = InverseTonemap.baseGainFor(0.95f, params)
        assertTrue(highlightGain > shadowGain)
        // 阴影区域增益接近 1.0（保护生效）
        assertTrue(shadowGain < 1.1f)
    }

    @Test
    fun whiteProtection_limitsGainOnFlatWhite() {
        val gNo = InverseTonemap.baseGainFor(0.98f, params)
        val gYes = InverseTonemap.applyWhiteProtection(gNo, whiteMask = 1f, strength = 0.75f)
        assertTrue(gYes < gNo)
        assertEquals(1f, InverseTonemap.applyWhiteProtection(gNo, 1f, 1f), 1e-4f)
    }

    @Test
    fun invalidParams_returnSafeGain() {
        val bad = ToneMapParameters(
            highlightStart = Float.NaN,
            highlightEnd = 0.9f,
            maxGainEv = Float.NaN,
            shadowStart = 0.02f,
            shadowEnd = 0.15f,
            whiteProtectionStrength = 0.75f,
            saturationProtection = 0.5f,
            localEnhancement = 0f,
            minBoost = 1f,
            maxBoost = 2f,
        )
        val g = InverseTonemap.baseGainFor(0.8f, bad)
        assertEquals(1f, g, 1e-4f)
    }

    @Test
    fun smoothstepEdges() {
        assertEquals(0f, InverseTonemap.smoothstep(0f, 1f, 0f), 1e-6f)
        assertEquals(1f, InverseTonemap.smoothstep(0f, 1f, 1f), 1e-6f)
        assertEquals(1f, InverseTonemap.smoothstep(0.5f, 0.5f, 0.8f), 1e-6f)
        assertEquals(0f, InverseTonemap.smoothstep(0.5f, 0.5f, 0.2f), 1e-6f)
        assertEquals(0.5f, InverseTonemap.smoothstep(0f, 1f, 0.5f), 1e-6f)
    }
}
