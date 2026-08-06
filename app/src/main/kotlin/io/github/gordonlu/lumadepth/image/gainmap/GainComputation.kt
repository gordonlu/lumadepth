// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.gainmap

import io.github.gordonlu.lumadepth.image.analysis.DetailConfidence
import io.github.gordonlu.lumadepth.image.analysis.HighlightClassifier
import io.github.gordonlu.lumadepth.image.analysis.NoiseEstimation
import io.github.gordonlu.lumadepth.image.analysis.SkinProtection
import io.github.gordonlu.lumadepth.image.filter.BoxFilter
import io.github.gordonlu.lumadepth.image.filter.FastGuidedFilter
import io.github.gordonlu.lumadepth.image.filter.LocalLaplacianFilter
import io.github.gordonlu.lumadepth.image.tonemap.InverseTonemap
import io.github.gordonlu.lumadepth.image.tonemap.Srgb
import io.github.gordonlu.lumadepth.image.tonemap.ToneMapParameters
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 增益场计算（两渲染器共用，纯 JVM）。
 *
 * 输出：逐像素线性增益（≥1，已做孤立峰值清理），供 Gain Map 编码
 * 与预览渲染共用，保证两者一致。
 *
 * 细节增强使用连续细节置信度（SNR / 邻域同向支持 / 多尺度持续性 /
 * 色噪抑制 / 暗部保护 / 点光源双通路），不使用固定幅度阈值。
 */
object GainComputation {

    /** 区域尺度：大半径边缘保持滤波（log 域），阈值对应线性亮度约 0.30 / 0.70。 */
    internal const val REGION_RADIUS = 6
    internal const val REGION_EPS = 0.02f
    internal const val REGION_LOG_START = -1.2f
    internal const val REGION_LOG_END = -0.35f

    /** 高质量模式：保边滤波的强度域平滑参数。 */
    const val LLF_SIGMA_R = 0.05f

    /** 细节增益在高光端的收敛强度（0.75~0.95 区间递减）。 */
    const val DETAIL_HIGHLIGHT_ROLLOFF = 0.7f

    /**
     * @param pixels sRGB ARGB_8888 像素（已按 EXIF 旋转，sRGB 色彩空间）
     * @return 逐像素线性增益（≥1，有限，已做孤立峰值清理）
     */
    fun computeGain(pixels: IntArray, width: Int, height: Int, p: ToneMapParameters): FloatArray {
        val n = pixels.size
        val yLinear = FloatArray(n)
        val rLinear = FloatArray(n)
        val gLinear = FloatArray(n)
        val bLinear = FloatArray(n)
        val logY = FloatArray(n)
        val clippedMask = FloatArray(n)
        for (i in 0 until n) {
            val argb = pixels[i]
            val r = Srgb.toLinear(((argb shr 16) and 0xFF) / 255f)
            val g = Srgb.toLinear(((argb shr 8) and 0xFF) / 255f)
            val b = Srgb.toLinear((argb and 0xFF) / 255f)
            rLinear[i] = r
            gLinear[i] = g
            bLinear[i] = b
            val y = Srgb.luminanceLinear(r, g, b)
            yLinear[i] = y
            logY[i] = ln(y + 1e-4f)
            val minCh = minOf(r, g, b)
            clippedMask[i] = when {
                minCh >= 0.92f -> 1f
                minCh >= 0.85f -> (minCh - 0.85f) / 0.07f
                else -> 0f
            }
        }
        // 局部纹理与噪声估计
        val blurY = BoxFilter.blur(yLinear, width, height, 1)
        val blurY2 = BoxFilter.blurSquared(yLinear, width, height, 1)
        val variance = FloatArray(n)
        for (i in 0 until n) {
            var v = blurY2[i] - blurY[i] * blurY[i]
            if (v < 0f) v = 0f
            variance[i] = v
        }
        val highlightConfidence = HighlightClassifier.computeConfidence(clippedMask, variance, width, height)
        val noiseMask = NoiseEstimation.estimateNoiseMask(yLinear, blurY)
        val skinConfidence = SkinProtection.computeConfidence(rLinear, gLinear, bLinear)

        // 多尺度：区域层 + 细节置信度
        val multiScale = p.regionGainEv > 0f || p.detailGainEv > 0f
        val regionLogY = if (multiScale) {
            FastGuidedFilter.filter(yLinear, logY, width, height, REGION_RADIUS, REGION_EPS)
        } else {
            null
        }
        val detailConfidence = if (p.detailGainEv > 0f) {
            val residualOverride = if (p.highQuality) {
                val alpha = (1f - 0.5f * p.localEnhancement).coerceIn(0.3f, 1f)
                val enhanced = LocalLaplacianFilter.filter(yLinear, width, height, LLF_SIGMA_R, alpha)
                FloatArray(n) { i -> enhanced[i] - yLinear[i] }
            } else {
                null
            }
            DetailConfidence.compute(yLinear, rLinear, gLinear, bLinear, width, height, residualOverride)
        } else {
            null
        }

        val maxLogGain = if (p.maxBoost > 1f) ln(p.maxBoost) / ln(2f) else 0f
        val gain = FloatArray(n)
        for (i in 0 until n) {
            val std = sqrt(variance[i])
            val texture = InverseTonemap.smoothstep(0.008f, 0.04f, std)
            val y = yLinear[i]
            // 阴影保护：暗部增益趋近 1.0
            val shadowAllow = InverseTonemap.smoothstep(p.shadowStart, p.shadowEnd, y)
            val highlightMask = InverseTonemap.smoothstep(p.highlightStart, p.highlightEnd, y)
            var logGain = p.maxGainEv * highlightMask * shadowAllow
            if (multiScale && regionLogY != null) {
                val regionMask = InverseTonemap.smoothstep(REGION_LOG_START, REGION_LOG_END, regionLogY[i])
                logGain += p.regionGainEv * regionMask * shadowAllow
            }
            // 细节尺度：连续置信度调制（硬上限由 detailGainEv ≤ 0.7 保证）；
            // 接近白色时细节增强收敛，避免局部增强在高光处叠加过曝。
            if (p.detailGainEv > 0f && detailConfidence != null) {
                val detailRoll = 1f - DETAIL_HIGHLIGHT_ROLLOFF * InverseTonemap.smoothstep(0.75f, 0.95f, y)
                logGain += p.detailGainEv * detailConfidence[i] * detailRoll
            }
            // 高光增益收敛：接近白色时整体增益递减，避免高光过曝
            if (p.highlightRolloff > 0f) {
                val rolloff = 1f - p.highlightRolloff * InverseTonemap.smoothstep(0.75f, 0.95f, y)
                logGain *= rolloff
            }
            logGain = logGain.coerceIn(0f, maxLogGain)
            var g = 2f.pow(logGain)

            // 白色保护（高光分类置信度感知）
            val confidence = highlightConfidence[i]
            val flatWhite = clippedMask[i] > 0.9f && texture < 0.3f
            val protection = when {
                flatWhite && p.whiteProtectionStrength > 0f && confidence < 0.2f -> 1f
                else -> p.whiteProtectionStrength * (1f - 0.5f * confidence)
            }
            val whiteMask = (1f - texture) * clippedMask[i]
            g = InverseTonemap.applyWhiteProtection(g, whiteMask, protection)

            // 噪声感知增益抑制
            if (p.noiseSuppression > 0f) {
                g = 1f + (g - 1f) * (1f - noiseMask[i] * p.noiseSuppression)
            }
            // 肤色保护（弱约束）
            if (p.skinProtection > 0f) {
                g = SkinProtection.applyProtection(g, skinConfidence[i], p.skinProtection)
            }
            gain[i] = g
        }
        // 第二道安全网：孤立增益峰值清理
        return IsolatedPeakSuppression.suppress(gain, width, height)
    }
}
