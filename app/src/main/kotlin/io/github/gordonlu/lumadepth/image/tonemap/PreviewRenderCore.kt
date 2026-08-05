// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.tonemap

import io.github.gordonlu.lumadepth.image.analysis.HighlightClassifier
import io.github.gordonlu.lumadepth.image.analysis.NoiseEstimation
import io.github.gordonlu.lumadepth.image.analysis.SkinProtection
import io.github.gordonlu.lumadepth.image.filter.BoxFilter
import io.github.gordonlu.lumadepth.image.filter.FastGuidedFilter
import io.github.gordonlu.lumadepth.image.gainmap.GainMapRenderCore
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * HDR 效果预览渲染核心（纯 JVM，ARGB_8888 IntArray ↔ IntArray）。
 * 模拟 HDR 增益在 SDR 屏幕上的效果。
 * 与 [GainMapRenderCore] 保持相同的多尺度/白色保护/噪声抑制/肤色保护逻辑，
 * 输出阶段先做色度压缩（保持色相）再做亮度肩部（控制溢出）。
 */
object PreviewRenderCore {

    /**
     * @param pixels sRGB ARGB_8888 像素
     * @return 模拟 HDR 效果的 ARGB_8888 像素（用于原图/HDR 对比预览）
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
            val minCh = minOf(r, g, b)
            clippedMask[i] = when {
                minCh >= 0.92f -> 1f
                minCh >= 0.85f -> (minCh - 0.85f) / 0.07f
                else -> 0f
            }
        }
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
        val multiScale = p.regionGainEv > 0f || p.detailGainEv > 0f
        val regionLogY = if (multiScale) {
            FastGuidedFilter.filter(
                yLinear, logY, width, height,
                GainMapRenderCore.REGION_RADIUS, GainMapRenderCore.REGION_EPS,
            )
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
            val highlightMask = InverseTonemap.smoothstep(p.highlightStart, p.highlightEnd, y)
            var logGain = p.maxGainEv * highlightMask * shadowAllow
            if (multiScale && regionLogY != null) {
                val regionMask = InverseTonemap.smoothstep(
                    GainMapRenderCore.REGION_LOG_START, GainMapRenderCore.REGION_LOG_END, regionLogY[i],
                )
                logGain += p.regionGainEv * regionMask * shadowAllow
                val detailLevel = logY[i] - regionLogY[i]
                val detailMask = InverseTonemap.smoothstep(
                    GainMapRenderCore.DETAIL_START, GainMapRenderCore.DETAIL_END, detailLevel,
                ) * (1f - noiseMask[i])
                logGain += p.detailGainEv * detailMask
            }
            logGain = logGain.coerceIn(0f, maxLogGain)
            var gain = 2f.pow(logGain)

            val confidence = highlightConfidence[i]
            val flatWhite = clippedMask[i] > 0.9f && texture < 0.3f
            val protection = when {
                flatWhite && p.whiteProtectionStrength > 0f && confidence < 0.2f -> 1f
                else -> p.whiteProtectionStrength * (1f - 0.5f * confidence)
            }
            val whiteMask = (1f - texture) * clippedMask[i]
            gain = InverseTonemap.applyWhiteProtection(gain, whiteMask, protection)
            if (p.noiseSuppression > 0f) {
                gain = 1f + (gain - 1f) * (1f - noiseMask[i] * p.noiseSuppression)
            }
            if (p.skinProtection > 0f) {
                gain = SkinProtection.applyProtection(gain, skinConfidence[i], p.skinProtection)
            }

            val r = rLinear[i] * gain
            val g = gLinear[i] * gain
            val b = bLinear[i] * gain
            val maxC = max(max(r, g), b)
            val overshoot = (maxC - 1f).coerceAtLeast(0f)
            if (overshoot > 0f) {
                // 色度压缩（先）：向亮度靠拢保持色相，高饱和颜色压缩更多
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val minC = minOf(r, g, b)
                val sat0 = (maxC - minC) / (maxC + 1e-4f)
                val satScale = 1f / (1f + CHROMA_K1 * overshoot + CHROMA_K2 * overshoot * sat0)
                val ro = luma + (r - luma) * satScale
                val go = luma + (g - luma) * satScale
                val bo = luma + (b - luma) * satScale
                // 亮度肩部（后）：柔和压缩溢出
                val rout = shoulder(ro)
                val gout = shoulder(go)
                val bout = shoulder(bo)
                out[i] = encodePixel(rout, gout, bout)
            } else {
                // 无溢出：保持原始（强度为 0 时与原始一致）
                out[i] = encodePixel(r, g, b)
            }
        }
        return out
    }

    private fun encodePixel(r: Float, g: Float, b: Float): Int {
        val rout = (Srgb.fromLinear(r.coerceIn(0f, 1f)) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gout = (Srgb.fromLinear(g.coerceIn(0f, 1f)) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bout = (Srgb.fromLinear(b.coerceIn(0f, 1f)) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (rout shl 16) or (gout shl 8) or bout
    }

    /** 柔和肩部：仅在溢出（c > 1）时向 1.0 渐近压缩；无溢出时恒等。 */
    private fun shoulder(c: Float): Float {
        if (c <= 1f) return c
        val t = (c - 1f) / 1.6f
        return 1f + (1f - 1f / (1f + t))
    }

    private const val CHROMA_K1 = 1.2f
    private const val CHROMA_K2 = 2.0f
}
