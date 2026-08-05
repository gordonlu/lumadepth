// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.tonemap

import io.github.gordonlu.lumadepth.image.analysis.AnalysisResult
import kotlin.math.min
import kotlin.math.pow

/**
 * 逆色调映射参数。
 * 说明：minBoost 固定为 1.0（Gain Map 下限），maxBoost = 2^maxGainEv。
 */
data class ToneMapParameters(
    val highlightStart: Float,
    val highlightEnd: Float,
    val maxGainEv: Float,
    val shadowStart: Float,
    val shadowEnd: Float,
    /** 大面积无纹理纯白区域的增益限制强度 (0..1)。 */
    val whiteProtectionStrength: Float,
    /** 饱和度保护强度 (0..1)，用于预览渲染的柔和色域压缩。 */
    val saturationProtection: Float,
    /** 局部增强幅度 (0..1)，默认值应较低。 */
    val localEnhancement: Float,
    val minBoost: Float,
    val maxBoost: Float,
)

object AutoParameters {
    const val MIN_MAX_GAIN_EV = 0.5f
    const val MAX_MAX_GAIN_EV = 2.5f
    const val DEFAULT_MAX_GAIN_EV = 1.1f
    const val DEFAULT_HIGHLIGHT_START = 0.45f
    const val DEFAULT_HIGHLIGHT_END = 0.90f
    const val DEFAULT_SHADOW_START = 0.02f
    const val DEFAULT_SHADOW_END = 0.15f
    const val MAX_BOOST_CAP = 6.0f

    /**
     * 根据图像分析与用户参数生成 ToneMapParameters。
     *
     * 强度语义：
     *  - 0.0  → 接近原始 SDR 表现（maxGainEv = 0）
     *  - 0.5  → 自然默认效果
     *  - 1.0  → 明显但仍受保护的 HDR 效果
     *
     * 自动模式根据直方图动态选择高光阈值、最大增益、阴影保护与白色保护强度。
     * 所有输入均被夹取，极端图片不会产生 NaN/无穷大。
     */
    fun forAnalysis(
        analysis: AnalysisResult,
        intensity01: Float,
        local01: Float,
        autoOptimize: Boolean,
    ): ToneMapParameters {
        // NaN/Inf 等非法输入按 0 处理，保证输出总是有限值。
        val intensity = if (intensity01.isFinite()) intensity01.coerceIn(0f, 1f) else 0f
        val local = if (local01.isFinite()) local01.coerceIn(0f, 1f) else 0f
        val baseMaxGainEv = if (autoOptimize) autoBaseMaxGainEv(analysis) else DEFAULT_MAX_GAIN_EV
        val maxGainEv = (baseMaxGainEv * intensity * 2f).coerceIn(0f, MAX_MAX_GAIN_EV)
        val maxBoost = 2f.pow(maxGainEv).coerceIn(1f, MAX_BOOST_CAP)
        val (highlightStart, highlightEnd) =
            if (autoOptimize) autoHighlightRange(analysis)
            else DEFAULT_HIGHLIGHT_START to DEFAULT_HIGHLIGHT_END
        val isDark = analysis.p50 < 0.08f
        val whiteProtection = if (autoOptimize) {
            (0.75f + 0.15f * min(1f, analysis.whiteFraction / 0.25f)).coerceIn(0f, 1f)
        } else {
            0.75f
        }
        // 自动模式下局部增强减半，保持"较弱效果"的特性。
        val localEnhancement = if (autoOptimize) local * 0.5f else local
        return ToneMapParameters(
            highlightStart = highlightStart,
            highlightEnd = highlightEnd,
            maxGainEv = maxGainEv,
            shadowStart = DEFAULT_SHADOW_START,
            shadowEnd = if (isDark) 0.20f else DEFAULT_SHADOW_END,
            whiteProtectionStrength = whiteProtection,
            saturationProtection = 0.5f,
            localEnhancement = localEnhancement,
            minBoost = 1f,
            maxBoost = maxBoost,
        )
    }

    private fun autoBaseMaxGainEv(a: AnalysisResult): Float {
        var base = DEFAULT_MAX_GAIN_EV
        // 大面积白色 → 降低最大增益，避免白墙/天空发白。
        base *= 1f - 0.45f * min(1f, a.whiteFraction / 0.25f)
        // 剪裁比例高 → 保守处理。
        base *= 1f - 0.30f * min(1f, a.clippedFraction / 0.15f)
        // 整体偏暗 → 保守，避免噪声与脏灰。
        if (a.p50 < 0.08f) base *= 0.85f
        // 整体偏亮但高光占比低 → 轻微上调。
        if (a.p50 > 0.45f && a.whiteFraction < 0.05f) base *= 1.05f
        return base.coerceIn(MIN_MAX_GAIN_EV, 1.5f)
    }

    private fun autoHighlightRange(a: AnalysisResult): Pair<Float, Float> {
        val start = (a.p95 - 0.08f).coerceIn(0.30f, 0.60f)
        val end = min(1f, start + 0.30f)
        return start to end
    }
}
