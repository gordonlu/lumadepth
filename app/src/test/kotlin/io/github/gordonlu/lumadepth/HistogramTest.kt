// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.analysis.Histogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistogramTest {

    @Test
    fun percentiles_uniform() {
        val values = FloatArray(1000) { it / 1000f }
        val h = Histogram.of(values)
        assertEquals(0.01f, h.percentile(0.01f), 0.01f)
        assertEquals(0.5f, h.percentile(0.5f), 0.01f)
        assertEquals(0.99f, h.percentile(0.99f), 0.01f)
    }

    @Test
    fun percentiles_constant() {
        val values = FloatArray(500) { 0.5f }
        val h = Histogram.of(values)
        assertEquals(0.5f, h.percentile(0.01f), 1e-3f)
        assertEquals(0.5f, h.percentile(0.99f), 1e-3f)
    }

    @Test
    fun fractionIn_works() {
        val values = FloatArray(400) { 0f } + FloatArray(400) { 0.5f } + FloatArray(200) { 1f }
        val h = Histogram.of(values)
        assertEquals(0.4f, h.fractionIn(0f, 0.01f), 0.02f)
        assertEquals(0.4f, h.fractionIn(0.49f, 0.51f), 0.02f)
        assertEquals(0.2f, h.fractionIn(0.99f, 1f), 0.02f)
    }

    @Test
    fun empty_isSafe() {
        val h = Histogram.of(FloatArray(0))
        assertEquals(0f, h.percentile(0.5f), 1e-6f)
        assertEquals(0f, h.fractionIn(0f, 1f), 1e-6f)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val values = floatArrayOf(-5f, 0f, 0.5f, 1f, 2f, Float.NaN)
        val h = Histogram.of(values)
        // NaN 不会破坏桶计数（coerceIn 处理 NaN 后为 0）
        assertTrue(h.percentile(1f) in 0f..1f)
    }
}
