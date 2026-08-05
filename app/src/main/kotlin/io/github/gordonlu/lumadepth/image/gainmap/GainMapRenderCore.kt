package io.github.gordonlu.lumadepth.image.gainmap

import io.github.gordonlu.lumadepth.image.tonemap.InverseTonemap
import io.github.gordonlu.lumadepth.image.tonemap.Srgb
import io.github.gordonlu.lumadepth.image.tonemap.ToneMapParameters
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Gain Map 渲染核心（纯 JVM，ARGB_8888 IntArray ↔ IntArray）。
 * 输出为单通道灰度 Gain Map（RGB 相同，alpha=255）。
 *
 * 包含：
 *  - 高光扩展 + 阴影保护（线性空间）
 *  - 白色保护：大面积、低纹理、已剪裁的纯白区域限制增益
 *  - 局部增强：log 亮度弱 unsharp，边缘处自动减弱（防光晕）
 */
object GainMapRenderCore {

    /**
     * @param pixels sRGB ARGB_8888 像素（必须已按 EXIF 旋转，色彩空间为 sRGB）
     * @return 灰度 Gain Map 像素（0..255，值 = 归一化 Gain Map 值 * 255）
     */
    fun renderPixels(pixels: IntArray, width: Int, height: Int, p: ToneMapParameters): IntArray {
        val n = pixels.size
        val yLinear = FloatArray(n)
        val logY = FloatArray(n)
        val clippedMask = FloatArray(n)
        for (i in 0 until n) {
            val argb = pixels[i]
            val r = Srgb.toLinear(((argb shr 16) and 0xFF) / 255f)
            val g = Srgb.toLinear(((argb shr 8) and 0xFF) / 255f)
            val b = Srgb.toLinear((argb and 0xFF) / 255f)
            val y = Srgb.luminanceLinear(r, g, b)
            yLinear[i] = y
            logY[i] = ln(y + 1e-4f)
            // RGB 同时接近剪裁（线性 0.85~0.92 平滑过渡到 1）
            val minCh = minOf(r, g, b)
            clippedMask[i] = when {
                minCh >= 0.92f -> 1f
                minCh >= 0.85f -> (minCh - 0.85f) / 0.07f
                else -> 0f
            }
        }
        // 局部纹理（亮度方差）与 log 亮度模糊
        val blurY = boxBlur(yLinear, width, height, 1)
        val blurY2 = boxBlurSquared(yLinear, width, height, 1)
        val blurLogY = boxBlur(logY, width, height, 1)

        val out = IntArray(n)
        for (i in 0 until n) {
            val meanY = blurY[i]
            val meanY2 = blurY2[i]
            var variance = meanY2 - meanY * meanY
            if (variance < 0f) variance = 0f
            val std = sqrt(variance)
            // 纹理高 → texture 接近 1（细节/边缘）；平坦 → 0
            val texture = InverseTonemap.smoothstep(0.008f, 0.04f, std)

            val y = yLinear[i]
            var gain = InverseTonemap.baseGainFor(y, p)
            // 白色保护：低纹理 + 剪裁 → 限制增益
            val whiteMask = (1f - texture) * clippedMask[i]
            gain = InverseTonemap.applyWhiteProtection(gain, whiteMask, p.whiteProtectionStrength)

            // 局部增强（弱）：log 亮度 unsharp，边缘减弱
            if (p.localEnhancement > 0f) {
                val k = p.localEnhancement * 0.15f * (1f - 0.7f * texture)
                val localLogGain = (logY[i] - blurLogY[i]) * k
                val localGain = 2f.pow(localLogGain.coerceIn(-0.3f, 0.3f))
                gain = InverseTonemap.applyLocalEnhancement(gain, localGain)
            }

            val v = GainMapMath.gainMapValueForPixel(y, gain, p.minBoost, p.maxBoost, GainMapMath.GAMMA)
            val byte = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[i] = 0xFF000000.toInt() or (byte shl 16) or (byte shl 8) or byte
        }
        return out
    }

    private fun boxBlur(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val n = src.size
        val tmp = FloatArray(n)
        val out = FloatArray(n)
        // 水平
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
                tmp[row + x] = sum / count
            }
        }
        // 垂直
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy in 0 until height) {
                        sum += tmp[yy * width + x]
                        count++
                    }
                }
                out[row + x] = sum / count
            }
        }
        return out
    }

    private fun boxBlurSquared(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val n = src.size
        val sq = FloatArray(n)
        for (i in 0 until n) sq[i] = src[i] * src[i]
        return boxBlur(sq, width, height, radius)
    }
}
