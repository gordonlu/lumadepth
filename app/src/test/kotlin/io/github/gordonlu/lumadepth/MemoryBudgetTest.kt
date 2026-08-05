// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.image.decode.MemoryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBudgetTest {

    @Test
    fun estimateScalesWithEdge() {
        val small = MemoryBudget.estimateExportMemory(1000)
        val large = MemoryBudget.estimateExportMemory(2000)
        assertTrue(large > small)
        // 面积 4 倍 → 估算约 4 倍
        assertEquals(4.0, large.toDouble() / small, 0.5)
    }

    @Test
    fun estimateBreakdownMatchesPixels() {
        // edge=1000：base = 4MB，其他项为 /16 量级
        val est = MemoryBudget.estimateExportMemory(1000)
        assertTrue(est > 1_000L * 1000 * 4) // 至少包含主图
    }

    @Test
    fun pickEdge_prefersLargestWithinBudget() {
        val candidates = listOf(3840, 2560, 1792)
        // 256MB 堆（largeHeap 常见）
        val edge = MemoryBudget.pickEdge(candidates, maxMemoryBytes = 256L * 1024 * 1024)
        assertTrue(edge != null)
        assertTrue(MemoryBudget.estimateExportMemory(edge!!) <= 256L * 1024 * 1024 * 0.6)
    }

    @Test
    fun pickEdge_smallHeap_fallsBack() {
        val candidates = listOf(3840, 2560, 1792)
        val edge = MemoryBudget.pickEdge(candidates, maxMemoryBytes = 128L * 1024 * 1024)
        assertTrue(edge != null)
        assertTrue(edge!! <= 2560)
    }

    @Test
    fun pickEdge_tinyHeap_returnsNull() {
        val candidates = listOf(3840, 2560, 1792)
        assertNull(MemoryBudget.pickEdge(candidates, maxMemoryBytes = 8L * 1024 * 1024))
    }

    @Test
    fun pickEdge_invalidInputs() {
        assertNull(MemoryBudget.pickEdge(listOf(3840), 0L))
        assertNull(MemoryBudget.pickEdge(listOf(3840), -1L))
        assertEquals(0L, MemoryBudget.estimateExportMemory(0))
        assertEquals(0L, MemoryBudget.estimateExportMemory(-5))
    }

    @Test
    fun pickEdge_alwaysWithinSafeRatio() {
        val candidates = listOf(3840, 2560, 1792, 1280)
        for (maxMemory in listOf(64L, 128L, 256L, 512L)) {
            val budget = maxMemory * 1024 * 1024
            val edge = MemoryBudget.pickEdge(candidates, budget)
            if (edge != null) {
                assertTrue(MemoryBudget.estimateExportMemory(edge) <= budget * 0.6)
            }
        }
    }
}
