// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.filter

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Local Laplacian Filter（自研实现，纯 JVM，灰度图）。
 *
 * 原理：构建拉普拉斯金字塔，对每个尺度用该尺度像素值作为局部强度参考
 * 做边缘感知的重新映射（小幅度差异放大、大幅度差异压缩），
 * 按强度域距离加权混合系数后重建。结果在保边的同时增强或压缩局部细节。
 *
 * 仅借鉴算法原理独立实现，不复制任何第三方源码。
 * 使用场景：高质量模式的细节增强（低分辨率 Gain Map 层，不处理全尺寸 RGB）。
 */
object LocalLaplacianFilter {

    /**
     * @param input   灰度图像（0..1），任意尺寸
     * @param sigmaR  强度域平滑参数（差异大于 sigmaR 视为边缘，不混合 remap）
     * @param alpha   细节增强幅度：<1 增强细节（越小越强），=1 恒等，>1 压缩
     * @return 滤波结果（尺寸与输入一致）
     */
    fun filter(
        input: FloatArray,
        width: Int,
        height: Int,
        sigmaR: Float,
        alpha: Float,
    ): FloatArray {
        require(input.size == width * height)
        require(width > 0 && height > 0)
        require(sigmaR > 0f)
        // NaN/Inf 输入替换为有限均值，保证输出有限。
        val cleaned = if (input.all { it.isFinite() }) {
            input
        } else {
            var sum = 0.0
            var count = 0
            for (v in input) {
                if (v.isFinite()) {
                    sum += v
                    count++
                }
            }
            val mean = if (count > 0) (sum / count).toFloat() else 0.5f
            FloatArray(input.size) { i -> if (input[i].isFinite()) input[i] else mean }
        }
        val levels = pyramidLevels(width, height)
        val srcPyramid = buildGaussianPyramid(cleaned, width, height, levels)
        // 逐层拉普拉斯系数：newCoeff[l] = w * coeff(R_l)[l] + (1-w) * srcCoeff[l]
        val newLaplacian = ArrayList<FloatArray>(levels)
        val srcLaplacian = ArrayList<FloatArray>(levels)
        for (l in 0 until levels) {
            srcLaplacian.add(laplacianLevel(srcPyramid, l, width, height))
        }
        for (l in 0 until levels) {
            val lw = levelWidth(width, l)
            val lh = levelHeight(height, l)
            // 参考强度 r_l：该层高斯上采样到全分辨率
            val ref = upsampleToFull(srcPyramid[l], lw, lh, width, height)
            // 重新映射：R_l(p) = f(r_l(p), input(p))（全分辨率）
            val remapped = FloatArray(cleaned.size)
            for (p in cleaned.indices) {
                remapped[p] = remap(ref[p], cleaned[p], sigmaR, alpha)
            }
            // R_l 的拉普拉斯第 l 层（层分辨率）
            val remappedPyramid = buildGaussianPyramid(remapped, width, height, minOf(l + 2, levels))
            val rLaplacian = laplacianLevel(remappedPyramid, l, width, height)
            // 权重混合（在层分辨率）：层像素对应全分辨率位置采样强度域距离，
            // 距离小（同参考强度）→ 用 remap 系数；大（跨边缘）→ 保留源系数
            val scale = width / lw
            val out = FloatArray(lw * lh)
            for (y in 0 until lh) {
                val fy = y * scale
                for (x in 0 until lw) {
                    val fx = x * scale
                    val fp = fy * width + fx
                    val d = (cleaned[fp] - ref[fp]) / sigmaR
                    val w = exp(-d * d)
                    val pi = y * lw + x
                    out[pi] = w * rLaplacian[pi] + (1f - w) * srcLaplacian[l][pi]
                }
            }
            newLaplacian.add(out)
        }
        return collapse(newLaplacian, width, height, levels)
    }

    /**
     * 细节增强 remap：围绕参考强度 r 拉伸小幅差异；
     * 大幅差异（边缘）线性保持；过小的差异（数值噪声）保持不动。
     */
    private fun remap(r: Float, x: Float, sigmaR: Float, alpha: Float): Float {
        if (!r.isFinite() || !x.isFinite()) return if (r.isFinite()) r else 0f
        val diff = x - r
        if (abs(diff) < 1e-6f) return x
        // 过小差异：幂拉伸会对 diff→0 发散放大，按噪声处理保持不动。
        val minDiff = sigmaR * MIN_DIFF_RATIO
        if (abs(diff) < minDiff || abs(diff) > sigmaR) return x
        val stretched = abs(diff).powF(alpha) * sigmaR.powF(1f - alpha) * sign(diff)
        return r + stretched
    }

    /** 视为数值噪声的最小差异比例（相对 sigmaR）。 */
    private const val MIN_DIFF_RATIO = 0.1f

    // ---------- 金字塔 ----------

    private fun pyramidLevels(w: Int, h: Int): Int {
        var levels = 1
        var minDim = minOf(w, h)
        while (minDim >= 8 && levels < 6) {
            minDim /= 2
            levels++
        }
        return levels
    }

    private fun levelWidth(w: Int, l: Int): Int = maxOf(1, w shr l)
    private fun levelHeight(h: Int, l: Int): Int = maxOf(1, h shr l)

    /** 高斯金字塔：2x2 平均下采样。 */
    private fun buildGaussianPyramid(
        input: FloatArray,
        width: Int,
        height: Int,
        levels: Int,
    ): List<FloatArray> {
        val pyramid = ArrayList<FloatArray>(levels)
        pyramid.add(input)
        var w = width
        var h = height
        for (l in 1 until levels) {
            val nw = maxOf(1, w / 2)
            val nh = maxOf(1, h / 2)
            val down = FloatArray(nw * nh)
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    var sum = 0f
                    var count = 0
                    for (dy in 0..1) {
                        for (dx in 0..1) {
                            val yy = y * 2 + dy
                            val xx = x * 2 + dx
                            if (yy < h && xx < w) {
                                sum += pyramid[l - 1][yy * w + xx]
                                count++
                            }
                        }
                    }
                    down[y * nw + x] = sum / count
                }
            }
            pyramid.add(down)
            w = nw
            h = nh
        }
        return pyramid
    }

    /** 拉普拉斯第 l 层：G_l - up(G_{l+1})。最高层拉普拉斯即其自身（低频）。 */
    private fun laplacianLevel(
        pyramid: List<FloatArray>,
        l: Int,
        width: Int,
        height: Int,
    ): FloatArray {
        val w = levelWidth(width, l)
        val h = levelHeight(height, l)
        val cur = pyramid[l]
        if (l == pyramid.size - 1) {
            // 最高层（残余低频）直接作为系数
            return cur
        }
        val next = pyramid[l + 1]
        val nw = maxOf(1, w / 2)
        val nh = maxOf(1, h / 2)
        val up = upsample(next, nw, nh, w, h)
        val out = FloatArray(w * h)
        for (i in 0 until w * h) {
            out[i] = cur[i] - up[i]
        }
        return out
    }

    /** 双线性上采样（含 2x 扩展）。 */
    private fun upsample(small: FloatArray, sw: Int, sh: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val sy = (y * sh) / h.toFloat()
            val y0 = sy.toInt().coerceIn(0, sh - 1)
            val y1 = minOf(y0 + 1, sh - 1)
            val fy = sy - y0
            for (x in 0 until w) {
                val sx = (x * sw) / w.toFloat()
                val x0 = sx.toInt().coerceIn(0, sw - 1)
                val x1 = minOf(x0 + 1, sw - 1)
                val fx = sx - x0
                val v00 = small[y0 * sw + x0]
                val v01 = small[y0 * sw + x1]
                val v10 = small[y1 * sw + x0]
                val v11 = small[y1 * sw + x1]
                val top = v00 + (v01 - v00) * fx
                val bottom = v10 + (v11 - v10) * fx
                out[y * w + x] = top + (bottom - top) * fy
            }
        }
        return out
    }

    private fun upsampleToFull(small: FloatArray, sw: Int, sh: Int, w: Int, h: Int): FloatArray {
        if (sw == w && sh == h) return small.copyOf()
        return upsample(small, sw, sh, w, h)
    }

    /** 金字塔合成：从最高层向上累加各层拉普拉斯。 */
    private fun collapse(
        laplacian: List<FloatArray>,
        width: Int,
        height: Int,
        levels: Int,
    ): FloatArray {
        var w = levelWidth(width, levels - 1)
        var h = levelHeight(height, levels - 1)
        var acc = laplacian[levels - 1].copyOf()
        for (l in levels - 2 downTo 0) {
            val pw = levelWidth(width, l)
            val ph = levelHeight(height, l)
            val up = upsample(acc, w, h, pw, ph)
            acc = FloatArray(pw * ph)
            for (i in 0 until pw * ph) {
                acc[i] = up[i] + laplacian[l][i]
            }
            w = pw
            h = ph
        }
        return acc
    }

    private fun Float.powF(e: Float): Float =
        if (this == 0f) 0f else java.lang.Math.pow(this.toDouble(), e.toDouble()).toFloat()
}
