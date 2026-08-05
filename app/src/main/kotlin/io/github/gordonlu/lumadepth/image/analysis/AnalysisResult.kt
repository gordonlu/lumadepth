package io.github.gordonlu.lumadepth.image.analysis

import kotlin.math.log2
import kotlin.math.max

/**
 * 亮度分析结果，全部基于线性光亮度。
 * 带宽定义（线性亮度 Y）：
 *  - 黑：Y < 0.015
 *  - 阴影：0.015 ~ 0.10
 *  - 中间调：0.10 ~ 0.50
 *  - 高光：0.50 ~ 0.95
 *  - 白：Y >= 0.95
 */
data class AnalysisResult(
    val pixelCount: Int,
    val p1: Float,
    val p5: Float,
    val p20: Float,
    val p50: Float,
    val p90: Float,
    val p95: Float,
    val p99: Float,
    val blackFraction: Float,
    val shadowFraction: Float,
    val midFraction: Float,
    val highlightFraction: Float,
    val whiteFraction: Float,
    /** RGB 三通道同时接近剪裁的像素占比（由调用方用 RGB 数据计算）。 */
    val clippedFraction: Float,
    /** 动态范围（档）：log2(p99 / max(p1, eps))。 */
    val dynamicRangeStops: Float,
) {
    companion object {
        const val BLACK_END = 0.015f
        const val SHADOW_END = 0.10f
        const val MID_END = 0.50f
        const val HIGHLIGHT_END = 0.95f
        const val EPS_LUMA = 0.0001f
    }
}

object Analysis {

    /** @param clippedFraction RGB 同时剪裁比例，由调用方统计。 */
    fun analyze(luma: FloatArray, clippedFraction: Float = 0f): AnalysisResult {
        val h = Histogram.of(luma)
        val p1 = h.percentile(0.01f)
        val p99 = h.percentile(0.99f)
        return AnalysisResult(
            pixelCount = luma.size,
            p1 = p1,
            p5 = h.percentile(0.05f),
            p20 = h.percentile(0.20f),
            p50 = h.percentile(0.50f),
            p90 = h.percentile(0.90f),
            p95 = h.percentile(0.95f),
            p99 = p99,
            blackFraction = h.fractionIn(0f, AnalysisResult.BLACK_END),
            shadowFraction = h.fractionIn(AnalysisResult.BLACK_END, AnalysisResult.SHADOW_END),
            midFraction = h.fractionIn(AnalysisResult.SHADOW_END, AnalysisResult.MID_END),
            highlightFraction = h.fractionIn(AnalysisResult.MID_END, AnalysisResult.HIGHLIGHT_END),
            whiteFraction = h.fractionIn(AnalysisResult.HIGHLIGHT_END, 1f),
            clippedFraction = clippedFraction.coerceIn(0f, 1f),
            dynamicRangeStops = dynamicRange(p1, p99),
        )
    }

    private fun dynamicRange(p1: Float, p99: Float): Float {
        val low = max(p1, AnalysisResult.EPS_LUMA)
        if (p99 <= low) return 0f
        return log2(p99 / low)
    }
}
