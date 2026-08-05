// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.gainmap

import io.github.gordonlu.lumadepth.image.analysis.HighlightClassifier
import io.github.gordonlu.lumadepth.image.analysis.NoiseEstimation
import io.github.gordonlu.lumadepth.image.analysis.SkinProtection
import io.github.gordonlu.lumadepth.image.filter.BoxFilter
import io.github.gordonlu.lumadepth.image.filter.FastGuidedFilter
import io.github.gordonlu.lumadepth.image.tonemap.InverseTonemap
import io.github.gordonlu.lumadepth.image.tonemap.Srgb
import io.github.gordonlu.lumadepth.image.tonemap.ToneMapParameters
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Gain Map 渲染核心（纯 JVM，ARGB_8888 IntArray ↔ IntArray）。
 * 输出为单通道灰度 Gain Map（RGB 相同，alpha=255）。
 *
 * v0.12 包含：
 *  - 高光扩展 + 阴影保护（线性空间）
 *  - 多尺度增益：全局 headroom + 区域尺度（大范围亮度）+ 细节尺度（小型亮点），
 *    在 log 空间组合并统一限幅
 *  - 高光区域分类（连通域 → highlightConfidence）：无细节剪裁完全保护，
 *    真光源/反光保留增强
 *  - 噪声感知增益抑制：暗部噪点/JPEG 块不被放大
 *  - 非 AI 肤色保护：弱约束，避免肤色区域高光油亮
 */
object GainMapRenderCore {

    const val GUIDED_RADIUS = 3
    const val GUIDED_EPS = 0.01f

    /** 区域尺度：大半径边缘保持滤波（log 域），阈值对应线性亮度约 0.30 / 0.70。 */
    internal const val REGION_RADIUS = 6
    internal const val REGION_EPS = 0.02f
    internal const val REGION_LOG_START = -1.2f
    internal const val REGION_LOG_END = -0.35f

    /** 细节尺度：亮细节（log 亮度差）阈值。 */
    internal const val DETAIL_START = 0.3f
    internal const val DETAIL_END = 0.8f

    /**
     * @param pixels sRGB ARGB_8888 像素（必须已按 EXIF 旋转，色彩空间为 sRGB）
     * @return 灰度 Gain Map 像素（0..255，值 = 归一化 Gain Map 值 * 255）
     */
    fun renderPixels(pixels: IntArray, width: Int, height: Int, p: ToneMapParameters): IntArray {
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
            // RGB 同时接近剪裁（线性 0.85~0.92 平滑过渡到 1）
            val minCh = minOf(r, g, b)
            clippedMask[i] = when {
                minCh >= 0.92f -> 1f
                minCh >= 0.85f -> (minCh - 0.85f) / 0.07f
                else -> 0f
            }
        }
        // 局部纹理（亮度方差）与噪声估计
        val blurY = BoxFilter.blur(yLinear, width, height, 1)
        val blurY2 = BoxFilter.blurSquared(yLinear, width, height, 1)
        val variance = FloatArray(n)
        for (i in 0 until n) {
            var v = blurY2[i] - blurY[i] * blurY[i]
            if (v < 0f) v = 0f
            variance[i] = v
        }
        // 高光区域分类 → 连续 confidence
        val highlightConfidence = HighlightClassifier.computeConfidence(clippedMask, variance, width, height)
        // 噪声感知抑制
        val noiseMask = NoiseEstimation.estimateNoiseMask(yLinear, blurY)
        // 非 AI 肤色保护
        val skinConfidence = SkinProtection.computeConfidence(rLinear, gLinear, bLinear)
        // 多尺度：区域层（边缘保持）与细节层
        val multiScale = p.regionGainEv > 0f || p.detailGainEv > 0f
        val regionLogY = if (multiScale) {
            FastGuidedFilter.filter(yLinear, logY, width, height, REGION_RADIUS, REGION_EPS)
        } else {
            null
        }

        val maxLogGain = if (p.maxBoost > 1f) ln(p.maxBoost) / ln(2f) else 0f
        val out = IntArray(n)
        for (i in 0 until n) {
            val std = sqrt(variance[i])
            val texture = InverseTonemap.smoothstep(0.008f, 0.04f, std)

            val y = yLinear[i]
            // 阴影保护：暗部增益趋近 1.0（shadowAllow 在暗部为 0）
            val shadowAllow = InverseTonemap.smoothstep(p.shadowStart, p.shadowEnd, y)

            // 多尺度 log 增益组合
            val highlightMask = InverseTonemap.smoothstep(p.highlightStart, p.highlightEnd, y)
            var logGain = p.maxGainEv * highlightMask * shadowAllow
            if (multiScale && regionLogY != null) {
                val regionMask = InverseTonemap.smoothstep(REGION_LOG_START, REGION_LOG_END, regionLogY[i])
                logGain += p.regionGainEv * regionMask * shadowAllow
                val detailLevel = logY[i] - regionLogY[i]
                val detailMask = InverseTonemap.smoothstep(DETAIL_START, DETAIL_END, detailLevel) *
                    (1f - noiseMask[i])
                logGain += p.detailGainEv * detailMask
            }
            // 统一限幅
            logGain = logGain.coerceIn(0f, maxLogGain)
            var gain = 2f.pow(logGain)

            // 白色保护：高光分类置信度感知
            val confidence = highlightConfidence[i]
            val flatWhite = clippedMask[i] > 0.9f && texture < 0.3f
            val protection = when {
                flatWhite && p.whiteProtectionStrength > 0f && confidence < 0.2f -> 1f
                else -> p.whiteProtectionStrength * (1f - 0.5f * confidence)
            }
            val whiteMask = (1f - texture) * clippedMask[i]
            gain = InverseTonemap.applyWhiteProtection(gain, whiteMask, protection)

            // 噪声感知增益抑制
            if (p.noiseSuppression > 0f) {
                gain = 1f + (gain - 1f) * (1f - noiseMask[i] * p.noiseSuppression)
            }

            // 肤色保护（弱约束）
            if (p.skinProtection > 0f) {
                gain = SkinProtection.applyProtection(gain, skinConfidence[i], p.skinProtection)
            }

            val v = GainMapMath.gainMapValueForPixel(y, gain, p.minBoost, p.maxBoost, GainMapMath.GAMMA)
            val byte = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = 0xFF000000.toInt() or (byte shl 16) or (byte shl 8) or byte
        }
        return out
    }
}
