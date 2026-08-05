package io.github.gordonlu.lumadepth.image.tonemap

import kotlin.math.pow

/**
 * 轻量 SDR-to-HDR 逆色调映射。
 *
 * 核心逻辑（线性空间）：
 *  highlightMask = smoothstep(highlightStart, highlightEnd, Y)
 *  gainEv = maxGainEv * highlightMask
 *  Y_hdr = Y_sdr * 2^gainEv
 *
 * 阴影保护：暗部增益逐渐趋近 1.0，避免黑色发灰、阴影浮起、噪点变亮。
 */
object InverseTonemap {

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (!edge0.isFinite() || !edge1.isFinite() || edge1 <= edge0) return if (x > edge0) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 基础增益：高光扩展 + 阴影保护。暗部趋近 1.0。非法参数时返回 1.0。 */
    fun baseGainFor(yLinear: Float, p: ToneMapParameters): Float {
        if (!yLinear.isFinite() || !p.maxGainEv.isFinite()) return 1f
        val highlightMask = smoothstep(p.highlightStart, p.highlightEnd, yLinear)
        val gainEv = p.maxGainEv * highlightMask
        val boost = 2f.pow(gainEv)
        val shadowProtection = 1f - smoothstep(p.shadowStart, p.shadowEnd, yLinear)
        return 1f + (boost - 1f) * (1f - shadowProtection)
    }

    /** 对大面积、低纹理、已剪裁的纯白区域限制增益（whiteMask ∈ [0,1]，1 = 完全保护）。 */
    fun applyWhiteProtection(gain: Float, whiteMask: Float, strength: Float): Float {
        if (strength <= 0f) return gain
        val mask = (whiteMask.coerceIn(0f, 1f)) * strength
        return 1f + (gain - 1f) * (1f - mask)
    }

    /** 局部增强：log 亮度上的受控 unsharp（弱效果），localGain 围绕 1.0。 */
    fun applyLocalEnhancement(gain: Float, localGain: Float): Float = gain * localGain
}
