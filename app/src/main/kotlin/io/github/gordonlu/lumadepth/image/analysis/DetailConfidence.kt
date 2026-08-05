// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.analysis

import io.github.gordonlu.lumadepth.image.filter.BoxFilter
import io.github.gordonlu.lumadepth.image.filter.FastGuidedFilter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 连续细节置信度（自研，纯 JVM）。
 *
 * 不使用固定幅度阈值或单一邻域差值判定细节，而是综合：
 *  - 信噪比：局部鲁棒噪声估计（残差 median/MAD）下的相对显著性；
 *  - 邻域同向支持：5×5 邻域中超过噪声水平的同向正残差占比；
 *  - 多尺度持续性：小尺度（r=2）与大尺度（r=6）残差都显著才算结构；
 *  - 色噪抑制：亮度残差低但色度波动大的像素（暗部彩点）不增强；
 *  - 暗部保护：暗部细节更谨慎。
 *
 * 双通路：
 *  - 结构细节通路：SNR × 支持 × 持续性 × 噪声保护；
 *  - 点光源通路：绝对亮度 × 局部反差 × 亮斑支持（绕过部分暗部保护）。
 * 最终取两者较高值，输出连续的 0..1 置信度，用于调制细节增益。
 */
object DetailConfidence {

    // ---- 噪声估计参数 ----
    private const val NOISE_GRID_STRIDE = 4
    private const val NOISE_WINDOW = 7

    // ---- SNR 阈值 ----
    private const val SNR_LOW = 1.5f
    private const val SNR_HIGH = 3.5f

    // ---- 邻域支持 ----
    private const val SUPPORT_RADIUS = 2
    private const val SUPPORT_K_SIGMA = 2.0f
    private const val SUPPORT_LOW = 0.05f
    private const val SUPPORT_HIGH = 0.30f

    // ---- 色噪抑制 ----
    private const val CHROMA_RATIO_LOW = 1.0f
    private const val CHROMA_RATIO_HIGH = 3.0f

    // ---- 暗部保护 ----
    private const val SHADOW_START = 0.03f
    private const val SHADOW_END = 0.20f

    // ---- 点光源通路 ----
    private const val BLOB_LUMA_START = 0.30f
    private const val BLOB_LUMA_END = 0.80f
    private const val BLOB_CONTRAST_START = 0.01f
    private const val BLOB_CONTRAST_END = 0.10f
    /** 亮斑邻域支持阈值（对比度绝对量）。 */
    private const val BLOB_SUPPORT_THRESHOLD = 0.02f
    /** 亮斑支持占比阈值（2px 亮点约 8%，小亮斑也有效）。 */
    private const val BLOB_SUPPORT_LOW = 0.04f
    private const val BLOB_SUPPORT_HIGH = 0.25f

    /** 亮斑信噪比：局部反差相对噪声估计的比值阈值（平场噪声反差≈噪声 → 0）。 */
    private const val BLOB_SNR_LOW = 3.0f
    private const val BLOB_SNR_HIGH = 5.0f
    private const val BLOB_WHITE_START = 0.90f
    private const val BLOB_WHITE_END = 0.98f

    /**
     * @param yLinear        线性亮度
     * @param rLinear        R 线性通道（色噪抑制）
     * @param gLinear        G 线性通道
     * @param bLinear        B 线性通道
     * @param detailResidual 自定义细节残差（高质量模式），null 时用 Y - guided(Y, r=2)
     * @return 逐像素细节置信度 ∈ [0,1]
     */
    fun compute(
        yLinear: FloatArray,
        rLinear: FloatArray,
        gLinear: FloatArray,
        bLinear: FloatArray,
        width: Int,
        height: Int,
        detailResidual: FloatArray? = null,
    ): FloatArray {
        val n = yLinear.size
        require(n == rLinear.size && n == gLinear.size && n == bLinear.size)
        val eps = 1e-4f
        // NaN/Inf 输入替换为有限均值
        val yc = cleanInput(yLinear)
        val rc = cleanInput(rLinear)
        val gc = cleanInput(gLinear)
        val bc = cleanInput(bLinear)

        // 多尺度残差（边缘保持基础层）
        val guidedSmall = FastGuidedFilter.filter(yc, yc, width, height, 2, 0.02f)
        val guidedLarge = FastGuidedFilter.filter(yc, yc, width, height, 6, 0.02f)
        val residualSmall = FloatArray(n)
        val residualLarge = FloatArray(n)
        val boxMean = BoxFilter.blur(yc, width, height, 4)
        for (i in 0 until n) {
            residualSmall[i] = yc[i] - guidedSmall[i]
            residualLarge[i] = yc[i] - guidedLarge[i]
        }
        val residual = detailResidual ?: residualSmall

        // 局部鲁棒噪声估计（粗网格 median/MAD，双线性上采样）
        val sigma = estimateNoiseSigma(residual, width, height, eps)

        // 信噪比
        val snrConfidence = FloatArray(n)
        val snrLargeConfidence = FloatArray(n)
        for (i in 0 until n) {
            val snr = max(residual[i], 0f) / (sigma[i] + eps)
            snrConfidence[i] = smoothstep(SNR_LOW, SNR_HIGH, snr)
            val snrLarge = max(residualLarge[i], 0f) / (sigma[i] + eps)
            snrLargeConfidence[i] = smoothstep(SNR_LOW, SNR_HIGH, snrLarge)
        }

        // 邻域同向支持：residual > k×sigma 的像素占比
        val supportConfidence = computeSupportConfidence(residual, sigma, width, height, eps)

        // 多尺度持续性：两个尺度都显著时加成；小尺度单独显著（高频纹理、
        // 小型亮点）仍保留 70% 权重（纯噪声在两个尺度都不显著）。
        val persistence = FloatArray(n)
        for (i in 0 until n) {
            persistence[i] = maxOf(0.7f * snrConfidence[i], minOf(snrConfidence[i], snrLargeConfidence[i]))
        }

        // 色噪抑制：色度局部波动 / 亮度残差
        val chromaNoise = computeChromaNoise(
            yc, rc, gc, bc, residual, width, height, eps,
        )

        // 点光源通路：跨边缘的局部反差（像素 vs 大半径邻域均值），
        // 亮斑邻域支持（对比度同向），绕过部分暗部保护。
        val contrast = FloatArray(n)
        for (i in 0 until n) {
            contrast[i] = max(yc[i] - boxMean[i], 0f)
        }
        val blobSupportConfidence = computeSupportConfidence(
            contrast, FloatArray(n) { BLOB_SUPPORT_THRESHOLD }, width, height, 0f,
            k = 1f, supportLow = BLOB_SUPPORT_LOW, supportHigh = BLOB_SUPPORT_HIGH,
        )

        // 暗部保护
        val confidence = FloatArray(n)
        for (i in 0 until n) {
            val shadowConfidence = smoothstep(SHADOW_START, SHADOW_END, yc[i])
            val noiseProtection = shadowConfidence * (1f - chromaNoise[i])

            // 结构细节通路
            val structure = supportConfidence[i] * persistence[i]
            val normalDetail = snrConfidence[i] * structure * noiseProtection

            // 点光源通路：绝对亮度高 + 局部反差（相对噪声显著）+ 亮斑支持 + 高光保护
            val blobLuma = smoothstep(BLOB_LUMA_START, BLOB_LUMA_END, yc[i])
            val localContrast = smoothstep(BLOB_CONTRAST_START, BLOB_CONTRAST_END, contrast[i])
            val blobSnr = smoothstep(BLOB_SNR_LOW, BLOB_SNR_HIGH, contrast[i] / (sigma[i] + eps))
            val highlightProtection = 1f - smoothstep(BLOB_WHITE_START, BLOB_WHITE_END, yc[i])
            val blob = blobLuma * localContrast * blobSupportConfidence[i] *
                highlightProtection * blobSnr

            confidence[i] = max(normalDetail, blob).coerceIn(0f, 1f)
        }
        return confidence
    }

    /** NaN/Inf 替换为有限均值。 */
    private fun cleanInput(input: FloatArray): FloatArray {
        if (input.all { it.isFinite() }) return input
        var sum = 0.0
        var count = 0
        for (v in input) {
            if (v.isFinite()) {
                sum += v
                count++
            }
        }
        val mean = if (count > 0) (sum / count).toFloat() else 0f
        return FloatArray(input.size) { i -> if (input[i].isFinite()) input[i] else mean }
    }

    /** 粗网格残差 median / MAD 噪声估计，双线性上采样到全分辨率。 */
    private fun estimateNoiseSigma(
        residual: FloatArray,
        width: Int,
        height: Int,
        eps: Float,
    ): FloatArray {
        val stride = NOISE_GRID_STRIDE
        val gw = (width + stride - 1) / stride
        val gh = (height + stride - 1) / stride
        val window = FloatArray(NOISE_WINDOW * NOISE_WINDOW)
        val grid = FloatArray(gw * gh)
        val half = NOISE_WINDOW / 2
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val cx = (gx * stride).coerceAtMost(width - 1)
                val cy = (gy * stride).coerceAtMost(height - 1)
                var count = 0
                for (dy in -half..half) {
                    val yy = cy + dy
                    if (yy !in 0 until height) continue
                    for (dx in -half..half) {
                        val xx = cx + dx
                        if (xx !in 0 until width) continue
                        window[count++] = residual[yy * width + xx]
                    }
                }
                val sigma = robustSigma(window, count)
                grid[gy * gw + gx] = if (sigma > 0f) sigma else eps
            }
        }
        // 双线性上采样
        val out = FloatArray(residual.size)
        for (y in 0 until height) {
            val fy = (y / stride.toFloat()).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, gh - 1)
            val wy = fy - y0
            for (x in 0 until width) {
                val fx = (x / stride.toFloat()).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, gw - 1)
                val wx = fx - x0
                val v00 = grid[y0 * gw + x0]
                val v01 = grid[y0 * gw + x1]
                val v10 = grid[y1 * gw + x0]
                val v11 = grid[y1 * gw + x1]
                val top = v00 + (v01 - v00) * wx
                val bottom = v10 + (v11 - v10) * wx
                out[y * width + x] = top + (bottom - top) * wy
            }
        }
        return out
    }

    /** 1.4826 × MAD（残差窗口内相对中值的绝对偏差中位数）。 */
    private fun robustSigma(values: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        val sorted = values.copyOfRange(0, count)
        sorted.sort()
        val median = sorted[count / 2]
        for (i in 0 until count) {
            sorted[i] = abs(sorted[i] - median)
        }
        sorted.sort()
        return 1.4826f * sorted[count / 2]
    }

    /** 邻域同向支持置信度。 */
    private fun computeSupportConfidence(
        residual: FloatArray,
        sigma: FloatArray,
        width: Int,
        height: Int,
        eps: Float,
        k: Float = SUPPORT_K_SIGMA,
        supportLow: Float = SUPPORT_LOW,
        supportHigh: Float = SUPPORT_HIGH,
    ): FloatArray {
        val n = residual.size
        val out = FloatArray(n)
        val r = SUPPORT_RADIUS
        val window = (2 * r + 1) * (2 * r + 1)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val thresh = k * sigma[i] + eps
                var count = 0
                for (dy in -r..r) {
                    val yy = y + dy
                    if (yy !in 0 until height) continue
                    for (dx in -r..r) {
                        val xx = x + dx
                        if (xx !in 0 until width) continue
                        if (residual[yy * width + xx] > thresh) count++
                    }
                }
                out[i] = smoothstep(supportLow, supportHigh, count.toFloat() / window)
            }
        }
        return out
    }

    /** 色噪置信度：色度局部波动相对亮度残差的比值。 */
    private fun computeChromaNoise(
        yLinear: FloatArray,
        rLinear: FloatArray,
        gLinear: FloatArray,
        bLinear: FloatArray,
        residual: FloatArray,
        width: Int,
        height: Int,
        eps: Float,
    ): FloatArray {
        val n = yLinear.size
        // 近似色差（无偏移 YCbCr 等价于色度偏离灰轴）
        val cr = FloatArray(n)
        val cb = FloatArray(n)
        for (i in 0 until n) {
            cr[i] = rLinear[i] - yLinear[i]
            cb[i] = bLinear[i] - yLinear[i]
        }
        val blurCr = BoxFilter.blur(cr, width, height, 1)
        val blurCb = BoxFilter.blur(cb, width, height, 1)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val chromaResidual = max(abs(cr[i] - blurCr[i]), abs(cb[i] - blurCb[i]))
            val ratio = chromaResidual / (abs(residual[i]) + eps)
            out[i] = smoothstep(CHROMA_RATIO_LOW, CHROMA_RATIO_HIGH, ratio)
        }
        return out
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x > edge0) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
