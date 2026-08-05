// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.tonemap

import io.github.gordonlu.lumadepth.image.analysis.HighlightClassifier
import io.github.gordonlu.lumadepth.image.analysis.NoiseEstimation
import io.github.gordonlu.lumadepth.image.filter.BoxFilter
import io.github.gordonlu.lumadepth.image.filter.FastGuidedFilter
import io.github.gordonlu.lumadepth.image.gainmap.GainMapRenderCore
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * HDR 效果预览渲染核心（纯 JVM，ARGB_8888 IntArray ↔ IntArray）。
 * 模拟 HDR 增益在 SDR 屏幕上的效果：线性增益 → 柔和色域压缩 → 饱和度保护 → sRGB 重编码。
 * 与 [GainMapRenderCore] 保持相同的白色保护/噪声抑制/局部增强逻辑。
 */
object PreviewRenderCore {

    /**
     * @param pixels sRGB ARGB_8888 像素
     * @return 模拟 HDR 效果的 ARGB_8888 像素（用于原图/HDR 对比预览）
     */
    fun renderPixels(pixels: IntArray, width: Int, height: Int, p: ToneMapParameters): IntArray {
        val n = pixels.size
        val yLinear = FloatArray(n)
        val logY = FloatArray(n)
        val clippedMask = FloatArray(n)
        val rLinear = FloatArray(n)
        val gLinear = FloatArray(n)
        val bLinear = FloatArray(n)
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
            logY[i] = kotlin.math.ln(y + 1e-4f)
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
        val guidedLogY = if (p.localEnhancement > 0f) {
            FastGuidedFilter.filter(
                yLinear, logY, width, height,
                GainMapRenderCore.GUIDED_RADIUS, GainMapRenderCore.GUIDED_EPS,
            )
        } else {
            null
        }

        val out = IntArray(n)
        for (i in 0 until n) {
            val std = sqrt(variance[i])
            val texture = InverseTonemap.smoothstep(0.008f, 0.04f, std)

            val y = yLinear[i]
            var gain = InverseTonemap.baseGainFor(y, p)
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
            if (p.localEnhancement > 0f && guidedLogY != null) {
                val k = p.localEnhancement * 0.15f * (1f - 0.7f * texture) * (1f - noiseMask[i])
                val localLogGain = (logY[i] - guidedLogY[i]) * k
                val localGain = 2f.pow(localLogGain.coerceIn(-0.3f, 0.3f))
                gain = InverseTonemap.applyLocalEnhancement(gain, localGain)
            }

            val r = rLinear[i] * gain
            val g = gLinear[i] * gain
            val b = bLinear[i] * gain
            val maxC = max(max(r, g), b)
            val overshoot = (maxC - 1f).coerceAtLeast(0f)
            // 柔和肩部压缩，避免硬裁剪造成的色相漂移
            val rs = shoulder(r)
            val gs = shoulder(g)
            val bs = shoulder(b)
            // 饱和度保护：溢出越大越向亮度靠拢
            val luma = 0.2126f * rs + 0.7152f * gs + 0.0722f * bs
            val satScale = 1f / (1f + p.saturationProtection * 2f * overshoot)
            val ro = luma + (rs - luma) * satScale
            val go = luma + (gs - luma) * satScale
            val bo = luma + (bs - luma) * satScale

            val rout = (Srgb.fromLinear(ro.coerceIn(0f, 1f)) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gout = (Srgb.fromLinear(go.coerceIn(0f, 1f)) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bout = (Srgb.fromLinear(bo.coerceIn(0f, 1f)) * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = 0xFF000000.toInt() or (rout shl 16) or (gout shl 8) or bout
        }
        return out
    }

    /** 柔和肩部：仅在溢出（c > 1）时向 1.0 渐近压缩；无溢出时恒等，保证强度为 0 时与原始一致。 */
    private fun shoulder(c: Float): Float {
        if (c <= 1f) return c
        val t = (c - 1f) / 1.6f
        return 1f + (1f - 1f / (1f + t))
    }
}
