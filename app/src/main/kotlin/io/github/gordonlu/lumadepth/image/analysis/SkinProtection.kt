// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.analysis

import kotlin.math.max

/**
 * 非 AI 肤色保护（自研保守工程策略，纯 JVM）。
 *
 * 在 RGB 域用暖色度、饱和度与亮度区间生成保守的肤色置信度，
 * 仅作为弱约束：限制肤色区域的最大 Gain 与局部对比，
 * 低置信度区域完全不受影响；不使用人脸模型、不使用过窄的固定肤色范围。
 */
object SkinProtection {

    /**
     * @return 逐像素肤色置信度 ∈ [0,1]（保守：只有暖色、中等饱和度、
     *         中等亮度的区域置信度高；纯红/纯绿/蓝天等不误伤）
     */
    fun computeConfidence(
        rLinear: FloatArray,
        gLinear: FloatArray,
        bLinear: FloatArray,
    ): FloatArray {
        val n = rLinear.size
        require(n == gLinear.size && n == bLinear.size)
        val skin = FloatArray(n)
        for (i in 0 until n) {
            val r = rLinear[i]
            val g = gLinear[i]
            val b = bLinear[i]
            if (!r.isFinite() || !g.isFinite() || !b.isFinite()) continue
            val maxC = max(max(r, g), b)
            val minC = minOf(r, g, b)
            if (maxC <= 0f) continue
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            // 暖色度：红显著高于蓝（肤色偏暖）
            val warm = (r - b) / (maxC + 1e-4f)
            // 绿度比例：肤色中绿介于红蓝之间（排除纯红 r/g 极端）
            val greenRatio = (g - b) / (r - b + 1e-4f)
            // 饱和度：有一定颜色但不过度（排除极端霓虹）
            val chroma = (maxC - minC) / (maxC + 1e-4f)
            val confidence =
                smoothstep(0.25f, 0.45f, warm) *
                    smoothstep(0.05f, 0.35f, greenRatio) *
                    smoothstep(0.08f, 0.40f, chroma) *
                    (1f - smoothstep(0.55f, 0.75f, luma)) *
                    smoothstep(0.02f, 0.08f, luma)
            skin[i] = confidence.coerceIn(0f, 1f)
        }
        return skin
    }

    /**
     * 肤色区域的增益限制（弱约束）：
     * gain = 1 + (gain - 1) * (1 - confidence * strength)
     */
    fun applyProtection(gain: Float, confidence: Float, strength: Float): Float {
        if (strength <= 0f) return gain
        return 1f + (gain - 1f) * (1f - confidence.coerceIn(0f, 1f) * strength)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x > edge0) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
