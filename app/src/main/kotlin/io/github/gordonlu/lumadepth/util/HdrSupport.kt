// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.util

import android.view.Display

/**
 * HDR 显示能力检测。
 *
 * 只接收 [Display]（来自界面组件，如 LocalView.current.display），
 * 不接收 Context：Application Context 未绑定 Display，调用
 * Context.getDisplay() 会抛 UnsupportedOperationException。
 */
object HdrSupport {

    /**
     * 判断给定 Display 是否具备 HDR 显示能力。
     * 传入 null（例如无界面上下文）时安全返回 false。
     */
    fun isHdrDisplayAvailable(display: Display?): Boolean {
        if (display == null) return false
        return isHdrCapable(display.hdrCapabilities?.supportedHdrTypes)
    }

    /** 纯函数：按 display 支持的 HDR 类型数组判断是否支持 HDR 显示。 */
    fun isHdrCapable(supportedHdrTypes: IntArray?): Boolean =
        supportedHdrTypes != null && supportedHdrTypes.size > 0
}
