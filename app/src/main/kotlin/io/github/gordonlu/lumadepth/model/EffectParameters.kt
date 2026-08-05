// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.model

/**
 * 用户可调节的效果参数（0..1 归一化）。
 */
data class EffectParameters(
    val hdrIntensity01: Float,
    val localEnhancement01: Float,
    val autoOptimize: Boolean,
) {
    companion object {
        const val DEFAULT_INTENSITY = 0.5f
        const val DEFAULT_LOCAL_ENHANCEMENT = 0.2f
        fun defaults() = EffectParameters(
            hdrIntensity01 = DEFAULT_INTENSITY,
            localEnhancement01 = DEFAULT_LOCAL_ENHANCEMENT,
            autoOptimize = true,
        )
    }
}
