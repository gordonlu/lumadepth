// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.gainmap

import io.github.gordonlu.lumadepth.image.analysis.HighlightClassifier
import io.github.gordonlu.lumadepth.image.analysis.NoiseEstimation
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
 * v0.11 包含：
 *  - 高光扩展 + 阴影保护（线性空间）
 *  - 白色保护：高光区域分类（连通域 → highlightConfidence），
 *    大面积无纹理剪裁（天空/白墙）完全保护，真光源/反光保留增强
 *  - 噪声感知增益抑制：暗部噪点/JPEG 块不被增益放大
 *  - 局部增强：Fast Guided Filter 平滑 log 亮度（边缘保持，防光晕）
 */
object GainMapRenderCore {

    const val GUIDED_RADIUS = 3
    const val GUIDED_EPS = 0.01f

    /**
     * @param pixels sRGB ARGB_8888 像素（必须已按 EXIF 旋转，色彩空间为 sRGB）
     * @return 灰度 Gain Map 像素（0..255，值 = 归一化 Gain Map 值 * 255）
     */
    fun renderPixels(pixels: IntArray, width: Int, height: Int, p: ToneMapParameters): IntArray {
        val n = pixels.size
        val yLinear = FloatArray(n)
        val logY = FloatArray(n)
        val clippedMask = FloatArray(n)
        for (i in 0 until n) {
            val argb = pixels[i]
            val r = Srgb.toLinear(((argb shr 16) and 0xFF) / 255f)
            val g = Srgb.toLinear(((argb shr 8) and 0xFF) / 255f)
            val b = Srgb.toLinear((argb and 0xFF) / 255f)
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
        // 局部增强平滑（边缘保持）
        val guidedLogY = if (p.localEnhancement > 0f) {
            FastGuidedFilter.filter(yLinear, logY, width, height, GUIDED_RADIUS, GUIDED_EPS)
        } else {
            null
        }

        val out = IntArray(n)
        for (i in 0 until n) {
            val std = sqrt(variance[i])
            // 纹理高 → texture 接近 1（细节/边缘）；平坦 → 0
            val texture = InverseTonemap.smoothstep(0.008f, 0.04f, std)

            val y = yLinear[i]
            var gain = InverseTonemap.baseGainFor(y, p)

            // 白色保护：高光分类置信度感知。
            // 大面积无纹理剪裁（天空/白墙，confidence 低）完全保护；
            // 有效光源/反光（confidence 高）仅按强度部分保护。
            val confidence = highlightConfidence[i]
            val flatWhite = clippedMask[i] > 0.9f && texture < 0.3f
            val protection = when {
                flatWhite && p.whiteProtectionStrength > 0f && confidence < 0.2f -> 1f
                else -> p.whiteProtectionStrength * (1f - 0.5f * confidence)
            }
            val whiteMask = (1f - texture) * clippedMask[i]
            gain = InverseTonemap.applyWhiteProtection(gain, whiteMask, protection)

            // 噪声感知增益抑制：暗部噪声区 Gain 趋近 1
            if (p.noiseSuppression > 0f) {
                gain = 1f + (gain - 1f) * (1f - noiseMask[i] * p.noiseSuppression)
            }

            // 局部增强（弱）：log 亮度 guided 平滑差，边缘处减弱
            if (p.localEnhancement > 0f && guidedLogY != null) {
                val k = p.localEnhancement * 0.15f * (1f - 0.7f * texture) * (1f - noiseMask[i])
                val localLogGain = (logY[i] - guidedLogY[i]) * k
                val localGain = 2f.pow(localLogGain.coerceIn(-0.3f, 0.3f))
                gain = InverseTonemap.applyLocalEnhancement(gain, localGain)
            }

            val v = GainMapMath.gainMapValueForPixel(y, gain, p.minBoost, p.maxBoost, GainMapMath.GAMMA)
            val byte = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = 0xFF000000.toInt() or (byte shl 16) or (byte shl 8) or byte
        }
        return out
    }
}
