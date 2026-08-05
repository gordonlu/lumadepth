// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.tonemap

import kotlin.math.pow

/**
 * sRGB 传输函数与线性光亮度计算。
 * 所有 HDR 增益计算都必须基于线性光数据，禁止直接在 8-bit gamma 编码值上计算。
 */
object Srgb {

    /** sRGB 编码值 (0..1) → 线性光 (0..1)。 */
    fun toLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f
        else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** 线性光 (0..1) → sRGB 编码值 (0..1)。 */
    fun fromLinear(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f
        else 1.055f * c.pow(1f / 2.4f) - 0.055f

    /** Rec.709 亮度系数，作用于线性光。 */
    fun luminanceLinear(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b
}
