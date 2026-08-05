// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.gainmap

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Gain Map 数学核心（纯 JVM）。
 *
 * 编码（LumaDepth 侧）：
 *  gain = (Y_hdr + epsHdr) / (Y_sdr + epsSdr)          （线性亮度比）
 *  logGain = log2(gain)
 *  normalized = clamp((logGain - log2(minBoost)) / (log2(maxBoost) - log2(minBoost)), 0, 1)
 *  gainMapValue = normalized ^ gamma
 *
 * 解码（玩家侧，与 Android Gainmap 一致）：
 *  L = mix(log(ratioMin), log(ratioMax), pow(G, gamma))
 *  gain = exp(L)
 */
object GainMapMath {
    const val EPSILON_SDR = 1f / 64f
    const val EPSILON_HDR = 1f / 64f
    const val GAMMA = 1f

    /** 线性亮度的对数增益（档）。非法输入返回 0（无增益）。 */
    fun log2Gain(sdrY: Float, hdrY: Float): Float {
        if (!sdrY.isFinite() || !hdrY.isFinite()) return 0f
        return ln((hdrY + EPSILON_HDR) / (sdrY + EPSILON_SDR)) / ln(2f)
    }

    /** 归一化 Gain Map 值（0..1）。maxBoost == minBoost 或非法输入时返回 0，避免除零。 */
    fun normalizedValue(log2Gain: Float, minBoost: Float, maxBoost: Float, gamma: Float): Float {
        if (!log2Gain.isFinite()) return 0f
        if (!minBoost.isFinite() || !maxBoost.isFinite() || minBoost <= 0f || maxBoost <= 0f) return 0f
        val logMin = ln(minBoost)
        val logMax = ln(maxBoost)
        val range = logMax - logMin
        if (range <= 0f) return 0f
        val v = ((log2Gain - logMin) / range).coerceIn(0f, 1f)
        return if (gamma.isFinite()) v.pow(gamma) else v
    }

    /** 玩家侧解码：gain = exp(mix(log(ratioMin), log(ratioMax), pow(G, gamma)))。 */
    fun decodeGain(mapValue: Float, ratioMin: Float, ratioMax: Float, gamma: Float): Float {
        if (!mapValue.isFinite() || !ratioMin.isFinite() || !ratioMax.isFinite() ||
            ratioMin <= 0f || ratioMax <= 0f
        ) {
            return 1f
        }
        val g = mapValue.coerceIn(0f, 1f).pow(if (gamma.isFinite()) gamma else 1f)
        val l = ln(ratioMin) + g * (ln(ratioMax) - ln(ratioMin))
        return exp(l)
    }

    /**
     * 单像素 Gain Map 值：给定 SDR 线性亮度与目标 HDR 增益（线性比值），
     * 返回编码后的 Gain Map 像素值（0..1）。
     */
    fun gainMapValueForPixel(sdrY: Float, toneGain: Float, minBoost: Float, maxBoost: Float, gamma: Float): Float {
        val hdrY = sdrY * toneGain
        return normalizedValue(log2Gain(sdrY, hdrY), minBoost, maxBoost, gamma)
    }
}
