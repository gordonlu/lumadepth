// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.analysis

/**
 * 基于桶的亮度直方图，用于百分位数统计。
 * 纯 JVM 实现，可在单元测试中直接运行。
 */
class Histogram private constructor(
    private val buckets: IntArray,
    private val total: Int,
) {
    val bucketCount: Int get() = buckets.size

    /** 百分位数 p ∈ [0,1]（0.5 = 中位数）。空直方图返回 0。 */
    fun percentile(p: Float): Float {
        if (total == 0) return 0f
        val target = p.coerceIn(0f, 1f) * total
        var acc = 0L
        for (i in buckets.indices) {
            acc += buckets[i]
            if (acc >= target) return i / (bucketCount - 1f)
        }
        return 1f
    }

    /** [low, high] 区间内像素占比。 */
    fun fractionIn(low: Float, high: Float): Float {
        if (total == 0) return 0f
        val lo = (low.coerceIn(0f, 1f) * (bucketCount - 1)).toInt()
        val hi = (high.coerceIn(0f, 1f) * (bucketCount - 1)).toInt()
        var sum = 0L
        for (i in lo..hi) sum += buckets[i]
        return sum.toFloat() / total
    }

    companion object {
        const val BUCKET_COUNT = 1024

        fun of(values: FloatArray): Histogram {
            val buckets = IntArray(BUCKET_COUNT)
            for (v in values) {
                val idx = (v.coerceIn(0f, 1f) * (BUCKET_COUNT - 1)).toInt()
                buckets[idx]++
            }
            return Histogram(buckets, values.size)
        }
    }
}
