// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.filter

import kotlin.math.min

/**
 * Fast Guided Filter（He & Sun 2015）的自研实现，纯 JVM。
 *
 * 思路：输出在局部窗口内是引导图的线性变换 q = a·I + b；
 * 先在小尺度计算线性系数（a, b），再平滑系数得到边缘保持的滤波结果。
 * 本项目用于 Gain Map / 局部增强的平滑：平坦区域被平滑（去除块状与伪纹理），
 * 强边缘被保留（不产生白边、黑边与光晕）。
 *
 * 仅借鉴论文原理，独立实现，无第三方源码。
 */
object FastGuidedFilter {

    /**
     * @param guide 引导图（如线性亮度）
     * @param input 待滤波信号（如 log2(gain)）
     * @param eps   正则化系数：越大越平滑
     */
    fun filter(
        guide: FloatArray,
        input: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        eps: Float,
    ): FloatArray {
        require(guide.size == width * height && input.size == width * height)
        require(radius >= 0 && eps > 0f)

        val meanI = BoxFilter.blur(guide, width, height, radius)
        val meanP = BoxFilter.blur(input, width, height, radius)
        val n = guide.size

        // cov(I,p) = mean(I*p) - mean(I)*mean(p)
        val ip = FloatArray(n)
        for (i in 0 until n) ip[i] = guide[i] * input[i]
        val meanIp = BoxFilter.blur(ip, width, height, radius)

        // var(I) = mean(I^2) - mean(I)^2
        val meanI2 = BoxFilter.blurSquared(guide, width, height, radius)

        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val covIp = meanIp[i] - meanI[i] * meanP[i]
            val varI = meanI2[i] - meanI[i] * meanI[i]
            a[i] = covIp / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }

        // 平滑系数后重建：q = mean(a)·I + mean(b)
        val meanA = BoxFilter.blur(a, width, height, radius)
        val meanB = BoxFilter.blur(b, width, height, radius)
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = meanA[i] * guide[i] + meanB[i]
        }
        return out
    }

    /**
     * 双尺度快速版：先在低分辨率计算系数再上采样（He & Sun 的降采样加速思路）。
     * 本项目当前直接在小尺寸（Gain Map 1/4 分辨率）上执行单尺度版本，
     * O(N) 已足够；此方法保留为未来对更大分辨率优化的入口。
     */
    fun filterDownsampled(
        guide: FloatArray,
        input: FloatArray,
        width: Int,
        height: Int,
        scale: Int,
        radius: Int,
        eps: Float,
    ): FloatArray {
        if (scale <= 1) {
            return filter(guide, input, width, height, radius, eps)
        }
        val sw = (width + scale - 1) / scale
        val sh = (height + scale - 1) / scale
        val smallI = FloatArray(sw * sh)
        val smallP = FloatArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                smallI[y * sw + x] = guide[min(y * scale, height - 1) * width + min(x * scale, width - 1)]
                smallP[y * sw + x] = input[min(y * scale, height - 1) * width + min(x * scale, width - 1)]
            }
        }
        val smallOut = filter(smallI, smallP, sw, sh, radius, eps)
        // 最近邻上采样
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = smallOut[(y / scale) * sw + (x / scale)]
            }
        }
        return out
    }
}
