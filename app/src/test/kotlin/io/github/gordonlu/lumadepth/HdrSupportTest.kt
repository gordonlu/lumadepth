// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.util.HdrSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用例 20：不支持 HDR 显示设备的降级状态判断。
 *
 * Display.HDR_TYPE_* 常量值（android.view.Display）：
 * HDR_TYPE_DOLBY_VISION = 1, HDR_TYPE_HDR10 = 2, HDR_TYPE_HLG = 3, HDR_TYPE_HDR10_PLUS = 4
 */
class HdrSupportTest {

    @Test
    fun nullCapabilities_meansNoHdr() {
        assertFalse(HdrSupport.isHdrCapable(null))
    }

    @Test
    fun emptyCapabilities_meansNoHdr() {
        assertFalse(HdrSupport.isHdrCapable(IntArray(0)))
    }

    @Test
    fun hlgCapable_isHdr() {
        assertTrue(HdrSupport.isHdrCapable(intArrayOf(3)))
    }

    @Test
    fun hdr10Capable_isHdr() {
        assertTrue(HdrSupport.isHdrCapable(intArrayOf(2)))
    }

    @Test
    fun mixedTypes_isHdr() {
        assertTrue(HdrSupport.isHdrCapable(intArrayOf(1, 4)))
    }
}
