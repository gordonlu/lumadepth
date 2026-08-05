// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.gainmap

/**
 * 孤立增益峰值清理（第二道安全网，纯 JVM）。
 *
 * Gain Map 生成后检测显著高于邻域中值的孤立像素（无邻域支持、
 * 只占一两个像素的随机峰值），将其向邻域中值拉回。
 * 仅处理孤立点，不做形态学开运算，不删除灯光、星星等可靠小区域。
 */
object IsolatedPeakSuppression {

    /** 超过邻域中值此幅度视为孤立峰值（增益比值域）。 */
    const val DEFAULT_THRESHOLD = 0.15f

    /** 拉回强度：1 = 完全拉回邻域中值。 */
    const val DEFAULT_SUPPRESSION = 0.7f

    /**
     * @param gainValues 归一化 Gain Map 值（0..1）
     * @return 清理后的 Gain Map 值
     */
    fun suppress(
        gainValues: FloatArray,
        width: Int,
        height: Int,
        threshold: Float = DEFAULT_THRESHOLD,
        suppression: Float = DEFAULT_SUPPRESSION,
    ): FloatArray {
        val n = gainValues.size
        require(n == width * height)
        if (threshold <= 0f || suppression <= 0f) return gainValues
        val out = gainValues.copyOf()
        val window = FloatArray(9)
        val scratch = FloatArray(9)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                var count = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in 0 until height) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx !in 0 until width) continue
                        window[count++] = gainValues[yy * width + xx]
                    }
                }
                if (count < 3) continue
                val median = quickSelectMedian(window, count, scratch)
                val v = gainValues[i]
                if (v - median > threshold) {
                    // 邻域支持检查：3x3 内（除自身位置）还有其他显著高值像素
                    //（如成对亮点）则不是孤立峰值，不拉回。
                    var others = 0
                    for (dy in -1..1) {
                        val yy = y + dy
                        if (yy !in 0 until height) continue
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val xx = x + dx
                            if (xx !in 0 until width) continue
                            if (gainValues[yy * width + xx] - median > threshold) others++
                        }
                    }
                    if (others == 0) {
                        out[i] = v + (median - v) * suppression
                    }
                }
            }
        }
        return out
    }

    private fun quickSelectMedian(values: FloatArray, count: Int, scratch: FloatArray): Float {
        System.arraycopy(values, 0, scratch, 0, count)
        scratch.sort(0, count)
        return scratch[count / 2]
    }
}
