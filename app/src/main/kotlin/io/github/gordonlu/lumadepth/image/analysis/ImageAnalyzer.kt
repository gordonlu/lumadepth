// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.analysis

import android.graphics.Bitmap
import io.github.gordonlu.lumadepth.image.decode.BitmapDecoder
import io.github.gordonlu.lumadepth.image.tonemap.Srgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图像亮度分析：解码低分辨率缩略图，统计线性亮度直方图与百分位数。
 */
class ImageAnalyzer(private val decoder: BitmapDecoder) {

    suspend fun analyze(uri: android.net.Uri): AnalysisResult =
        withContext(Dispatchers.IO) {
            val thumb = decoder.decodeAnalysis(uri)
            try {
                val pixels = IntArray(thumb.width * thumb.height)
                thumb.getPixels(pixels, 0, thumb.width, 0, 0, thumb.width, thumb.height)
                var clipped = 0
                val luma = FloatArray(pixels.size)
                for (i in pixels.indices) {
                    val argb = pixels[i]
                    val r = Srgb.toLinear(((argb shr 16) and 0xFF) / 255f)
                    val g = Srgb.toLinear(((argb shr 8) and 0xFF) / 255f)
                    val b = Srgb.toLinear((argb and 0xFF) / 255f)
                    luma[i] = Srgb.luminanceLinear(r, g, b)
                    if (r >= 0.95f && g >= 0.95f && b >= 0.95f) clipped++
                }
                Analysis.analyze(luma, clipped / pixels.size.toFloat())
            } finally {
                thumb.recycle()
            }
        }
}
