// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.util

import android.content.Context

/**
 * HDR 显示能力检测。
 */
object HdrSupport {

    /** 设备（当前默认 display）是否具备 HDR 显示能力。 */
    fun isHdrDisplayAvailable(context: Context): Boolean {
        val display = context.display ?: return false
        return isHdrCapable(display.hdrCapabilities?.supportedHdrTypes)
    }

    /** 纯函数：按 display 支持的 HDR 类型数组判断是否支持 HDR 显示。 */
    fun isHdrCapable(supportedHdrTypes: IntArray?): Boolean =
        supportedHdrTypes != null && supportedHdrTypes.size > 0
}
