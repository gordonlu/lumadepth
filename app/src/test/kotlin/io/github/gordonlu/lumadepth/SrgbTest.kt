// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.tonemap.Srgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrgbTest {

    @Test
    fun toLinear_knownValues() {
        assertEquals(0f, Srgb.toLinear(0f), 1e-6f)
        assertEquals(1f, Srgb.toLinear(1f), 1e-6f)
        // 0.5 sRGB encoded ≈ 0.214 linear
        assertEquals(0.214f, Srgb.toLinear(0.5f), 0.002f)
        // 线性段：0.04 → 0.04/12.92
        assertEquals(0.04f / 12.92f, Srgb.toLinear(0.04f), 1e-5f)
    }

    @Test
    fun roundTrip_roundTripsAcrossRange() {
        for (v in 0..255 step 5) {
            val c = v / 255f
            val rt = Srgb.fromLinear(Srgb.toLinear(c))
            assertEquals(c, rt, 0.002f)
        }
    }

    @Test
    fun luminance_rec709Weights() {
        assertEquals(1f, Srgb.luminanceLinear(1f, 1f, 1f), 1e-6f)
        assertEquals(0f, Srgb.luminanceLinear(0f, 0f, 0f), 1e-6f)
        assertEquals(0.2126f, Srgb.luminanceLinear(1f, 0f, 0f), 1e-6f)
        assertEquals(0.7152f, Srgb.luminanceLinear(0f, 1f, 0f), 1e-6f)
        assertEquals(0.0722f, Srgb.luminanceLinear(0f, 0f, 1f), 1e-6f)
    }

    @Test
    fun allOutputsFinite() {
        for (v in -1..257) {
            val c = v / 255f
            assertTrue(Srgb.toLinear(c).isFinite())
            assertTrue(Srgb.fromLinear(c).isFinite())
        }
    }
}
