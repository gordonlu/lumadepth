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
            val sr = Srgb.toLinear(((argb shr 16) and 0xFF) / 255f)
            val sg = Srgb.toLinear(((argb shr 8) and 0xFF) / 255f)
            val sb = Srgb.toLinear((argb and 0xFF) / 255f)
            // 预览专用：只对增益引入的高光增量做收敛（原图高光不动，强度 0 恒等）。
            // SDR 屏幕无法显示超过白点的亮度，若不收敛，增益后的高光会被硬压成纯白，
            // 造成"预览过曝、导出正常"的观感。
            val sdrLuma = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb
            val excessRoll = 1f - EXCESS_ROLLOFF * smoothstep(0.70f, 0.95f, sdrLuma)
            val r = sr + (sr * gain[i] - sr) * excessRoll
            val g = sg + (sg * gain[i] - sg) * excessRoll
            val b = sb + (sb * gain[i] - sb) * excessRoll
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

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x > edge0) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
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

    /** 预览高光增量收敛强度：0.5 = 高光端增益引入的增量减半。 */
    private const val EXCESS_ROLLOFF = 0.5f
}
