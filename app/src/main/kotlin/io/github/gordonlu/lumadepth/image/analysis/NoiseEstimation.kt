// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.analysis

import kotlin.math.abs

/**
 * 噪声感知增益抑制（自研简化公式，纯 JVM）。
 *
 * 思路：从原亮度与边缘保持平滑结果的残差估算局部噪声；
 * 亮度越低、相对残差越像随机噪声，Gain 应越接近 1，
 * 防止暗部噪点与 JPEG 压缩块被 HDR 增益放大。
 */
object NoiseEstimation {

    /**
     * @param yLinear    线性亮度
     * @param blurredY   边缘保持平滑结果（Box/Guided 滤波后的亮度）
     * @param darkStart  暗部激活下限（线性亮度）
     * @param darkEnd    暗部激活上限（高于此不再抑制）
     * @return noiseMask ∈ [0,1]，1 = 高置信噪声（暗部 + 大相对残差）
     */
    fun estimateNoiseMask(
        yLinear: FloatArray,
        blurredY: FloatArray,
        darkStart: Float = 0.05f,
        darkEnd: Float = 0.20f,
    ): FloatArray {
        val n = yLinear.size
        require(n == blurredY.size)
        val mask = FloatArray(n)
        for (i in 0 until n) {
            val y = yLinear[i]
            if (y < 0f || !y.isFinite()) continue
            // 暗部权重：亮度越低越可能是噪声
            val darkWeight = 1f - smoothstep(darkStart, darkEnd, y)
            if (darkWeight <= 0f) continue
            // 相对残差：残差 / (亮度 + eps)；平坦区噪声的相对残差大，真实边缘也会被部分计入
            val residual = abs(y - blurredY[i])
            val relResidual = residual / (y + 1e-3f)
            val noise = smoothstep(0.15f, 0.60f, relResidual)
            mask[i] = noise * darkWeight
        }
        return mask
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x > edge0) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
