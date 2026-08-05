// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.tonemap

import io.github.gordonlu.lumadepth.image.gainmap.GainComputation
import kotlin.math.ln
import kotlin.math.max

/**
 * HDR 效果预览渲染核心（纯 JVM，ARGB_8888 IntArray ↔ IntArray）。
 * 模拟 HDR 增益在 SDR 屏幕上的效果。
 * 增益场与导出完全一致（[GainComputation]），
 * 输出阶段先做色度压缩（保持色相）再做亮度肩部（控制溢出）。
 */
object PreviewRenderCore {

    /**
     * @param pixels sRGB ARGB_8888 像素
     * @return 模拟 HDR 效果的 ARGB_8888 像素（用于原图/HDR 对比预览）
     */
    fun renderPixels(pixels: IntArray, width: Int, height: Int, p: ToneMapParameters): IntArray {
        val n = pixels.size
        val gain = GainComputation.computeGain(pixels, width, height, p)
        val out = IntArray(n)
        for (i in 0 until n) {
            val argb = pixels[i]
            val r = Srgb.toLinear(((argb shr 16) and 0xFF) / 255f) * gain[i]
            val g = Srgb.toLinear(((argb shr 8) and 0xFF) / 255f) * gain[i]
            val b = Srgb.toLinear((argb and 0xFF) / 255f) * gain[i]
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
                out[i] = encodePixel(shoulder(ro), shoulder(go), shoulder(bo))
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
