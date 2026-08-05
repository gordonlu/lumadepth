package io.github.gordonlu.lumadepth

import android.view.Display
import io.github.gordonlu.lumadepth.util.HdrSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用例 20：不支持 HDR 显示设备的降级状态判断。
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
        assertTrue(HdrSupport.isHdrCapable(intArrayOf(Display.HDR_TYPE_HLG)))
    }

    @Test
    fun hdr10Capable_isHdr() {
        assertTrue(HdrSupport.isHdrCapable(intArrayOf(Display.HDR_TYPE_HDR10)))
    }

    @Test
    fun mixedTypes_isHdr() {
        assertTrue(
            HdrSupport.isHdrCapable(
                intArrayOf(
                    Display.HDR_TYPE_DOLBY_VISION,
                    Display.HDR_TYPE_HDR10_PLUS,
                )
            )
        )
    }
}
