// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.gainmap

import io.github.gordonlu.lumadepth.image.tonemap.ToneMapParameters

/**
 * Gain Map 渲染（纯 JVM，ARGB_8888 IntArray ↔ IntArray）。
 * 输出为单通道灰度 Gain Map（RGB 相同，alpha=255）。
 * 增益场计算由 [GainComputation] 统一提供。
 */
object GainMapRenderCore {

    /**
     * @param pixels sRGB ARGB_8888 像素（必须已按 EXIF 旋转，色彩空间为 sRGB）
     * @return 灰度 Gain Map 像素（0..255，值 = 归一化 Gain Map 值 * 255）
     */
    fun renderPixels(pixels: IntArray, width: Int, height: Int, p: ToneMapParameters): IntArray {
        val gain = GainComputation.computeGain(pixels, width, height, p)
        val n = gain.size
        val out = IntArray(n)
        for (i in 0 until n) {
            val logGain = kotlin.math.ln(gain[i]) / kotlin.math.ln(2f)
            val v = GainMapMath.normalizedValue(logGain, p.minBoost, p.maxBoost, GainMapMath.GAMMA)
            val byte = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = 0xFF000000.toInt() or (byte shl 16) or (byte shl 8) or byte
        }
        return out
    }
}
