// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.decode

/**
 * 导出内存预算估算（纯 JVM，可单元测试）。
 *
 * 处理一张 edge×edge 的图片（最坏情况按正方形估算，实际面积通常更小）：
 *  - 主图 ARGB_8888：4 B/px
 *  - Gain Map 源图（1/4 尺寸）：/16
 *  - Gain Map 输出位图（1/4 尺寸）：/16
 *  - 渲染中间 FloatArray（亮度/对数/剪裁/3×模糊）：6 × 4 B/px ÷ 16
 *  - 像素 IntArray（1/4 尺寸）：4 B/px ÷ 16
 *  - 解码器内部缓冲：约 1.5× 主图
 */
object MemoryBudget {

    /** 安全比例：进程堆中为系统、Compose 与文件系统缓存保留的比例。 */
    const val SAFE_RATIO = 0.6f

    fun estimateExportMemory(edge: Int): Long {
        if (edge <= 0) return 0L
        val pixels = edge.toLong() * edge
        val base = pixels * 4L
        val quarter = base / 16L
        val renderArrays = 6L * 4L * (pixels / 16L)
        val pixelArray = 4L * (pixels / 16L)
        val decoderOverhead = base * 3L / 2L
        return base + quarter + quarter + renderArrays + pixelArray + decoderOverhead
    }

    /**
     * 从候选边列表中选出第一个预算内可用的边长。
     * @return null 表示所有候选都超出安全预算。
     */
    fun pickEdge(candidates: List<Int>, maxMemoryBytes: Long, safeRatio: Float = SAFE_RATIO): Int? {
        if (maxMemoryBytes <= 0L) return null
        val budget = (maxMemoryBytes * safeRatio).toLong()
        for (edge in candidates) {
            if (estimateExportMemory(edge) <= budget) return edge
        }
        return null
    }
}
