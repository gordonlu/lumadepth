// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.analysis

/**
 * 高光区域分类（自研工程策略，纯 JVM）。
 *
 * 对接近纯白的区域做连通域分析，结合区域面积、是否接触画面边缘、
 * 内部纹理（亮度方差）估计"有效光源/反光"还是"无细节剪裁"，
 * 输出连续的 highlightConfidence ∈ [0,1]（不输出简单二值 Mask）：
 *  - 高 confidence：小面积、不贴边、内部有纹理/渐变（灯光、太阳、反光）
 *  - 低 confidence：大面积、贴边、平坦（天空、白墙等无细节剪裁）
 */
object HighlightClassifier {

    /** 剪裁二值化阈值。 */
    private const val CLIP_THRESHOLD = 0.5f

    /** 区域面积占图比例：小于此视为"小光源"，大于此视为"大面积"。 */
    private const val AREA_SMALL_RATIO = 0.001f
    private const val AREA_LARGE_RATIO = 0.04f

    /** 区域接触画面边缘的像素比例（保留特征，评分用布尔接触）。 */
    private const val EDGE_TOUCH_LOW = 0.15f
    private const val EDGE_TOUCH_HIGH = 0.60f
    /** 贴边区域的固定边缘分（接触画面边缘扣分，但角落小光源仍保留部分）。 */
    private const val EDGE_TOUCHED_SCORE = 0.30f

    /** 区域内平均亮度方差：低 = 平坦（无细节），高 = 有纹理/渐变（云、灯光光晕）。 */
    private const val VARIANCE_LOW = 0.0002f
    private const val VARIANCE_HIGH = 0.004f

    /**
     * @param clippedMask 接近纯白程度（0..1），由 RGB 同时剪裁计算
     * @param variance    局部亮度方差（E[Y²]-E[Y]²），用于估计区域纹理
     * @return 逐像素 highlightConfidence（非剪裁区域为 0）
     */
    fun computeConfidence(
        clippedMask: FloatArray,
        variance: FloatArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val n = clippedMask.size
        require(n == variance.size && n == width * height)
        val confidence = FloatArray(n)

        // 第一遍：4 邻域两遍扫描标记（union-find 合并等价标签）。
        val label = IntArray(n) { -1 }
        val parent = IntArray(n) { -1 } // 以像素位置作为标签空间

        fun findRoot(x: Int): Int {
            var r = x
            while (parent[r] != -1) r = parent[r]
            var cur = x
            while (parent[cur] != -1) {
                val next = parent[cur]
                parent[cur] = r
                cur = next
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = findRoot(a)
            val rb = findRoot(b)
            if (ra != rb) parent[ra] = rb
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                // 非有限值按非前景处理
                if (!clippedMask[i].isFinite() || clippedMask[i] < CLIP_THRESHOLD) continue
                val left = if (x > 0 && label[i - 1] != -1) label[i - 1] else -1
                val up = if (y > 0 && label[i - width] != -1) label[i - width] else -1
                when {
                    left == -1 && up == -1 -> {
                        label[i] = i
                        parent[i] = -1
                    }
                    left != -1 && up == -1 -> label[i] = left
                    left == -1 && up != -1 -> label[i] = up
                    else -> {
                        label[i] = minOf(left, up)
                        union(left, up)
                    }
                }
            }
        }

        // 第二遍：合并等价标签 → 连续区域 id，并统计特征。
        val regionId = IntArray(n) { -1 }
        val regionCount = IntArray(n)
        val regionTouchEdge = IntArray(n)
        val regionVarianceSum = FloatArray(n)
        var regionNum = 0
        for (i in 0 until n) {
            if (label[i] == -1) continue
            val root = findRoot(label[i])
            val y = i / width
            val x = i % width
            val touchEdge = (x == 0 || x == width - 1 || y == 0 || y == height - 1)
            if (regionId[root] == -1) {
                regionId[root] = regionNum++
            }
            val id = regionId[root]
            regionCount[id]++
            if (touchEdge) regionTouchEdge[id]++
            regionVarianceSum[id] += if (variance[i].isFinite()) variance[i] else 0f
        }

        // 第三遍：逐区域评分，O(n) 直接写入。
        val regionConfidenceArr = FloatArray(regionNum)
        for (id in 0 until regionNum) {
            val area = regionCount[id]
            if (area <= 0) continue
            val areaScore = 1f - smoothstep(AREA_SMALL_RATIO * n, AREA_LARGE_RATIO * n, area.toFloat())
            // 接触画面边缘 → 固定扣分（天空/白墙常贴边，角落光源保留部分）
            val edgeScore = if (regionTouchEdge[id] > 0) EDGE_TOUCHED_SCORE else 1f
            val meanVariance = regionVarianceSum[id] / area
            val textureScore = smoothstep(VARIANCE_LOW, VARIANCE_HIGH, meanVariance)
            regionConfidenceArr[id] = (0.45f * areaScore + 0.30f * edgeScore + 0.25f * textureScore)
                .coerceIn(0f, 1f)
        }
        for (i in 0 until n) {
            if (label[i] != -1) {
                confidence[i] = regionConfidenceArr[regionId[findRoot(label[i])]]
            }
        }
        return confidence
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x > edge0) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
