// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.filter

/**
 * 箱式滤波（Box Filter），纯 JVM。
 * 两趟一维滑动窗口，O(N)；边界按有效窗口均值处理。
 */
object BoxFilter {

    fun blur(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (width <= 0 || height <= 0 || src.size != width * height) {
            throw IllegalArgumentException("invalid input for box blur")
        }
        val n = src.size
        val tmp = FloatArray(n)
        val out = FloatArray(n)
        horizontalPass(src, tmp, width, height, radius)
        verticalPass(tmp, out, width, height, radius)
        return out
    }

    fun blurSquared(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val n = src.size
        val sq = FloatArray(n)
        for (i in 0 until n) sq[i] = src[i] * src[i]
        return blur(sq, width, height, radius)
    }

    private fun horizontalPass(src: FloatArray, out: FloatArray, width: Int, height: Int, radius: Int) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                for (dx in -radius..radius) {
                    val xx = x + dx
                    if (xx in 0 until width) {
                        sum += src[row + xx]
                        count++
                    }
                }
                out[row + x] = sum / count
            }
        }
    }

    private fun verticalPass(src: FloatArray, out: FloatArray, width: Int, height: Int, radius: Int) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy in 0 until height) {
                        sum += src[yy * width + x]
                        count++
                    }
                }
                out[row + x] = sum / count
            }
        }
    }
}
