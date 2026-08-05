package io.github.gordonlu.lumadepth.image.encode

import android.graphics.Bitmap
import android.graphics.Gainmap
import java.io.OutputStream

/**
 * Gain Map 元数据。
 * 解码侧（Android Gainmap 公式）：
 *  L = mix(log(ratioMin), log(ratioMax), pow(G, gamma))
 *  D = (B + epsilonSdr) * exp(L * W) - epsilonHdr
 *  W 由显示器的 HDR/SDR 比与 minDisplayRatioForHdrTransition / displayRatioForFullHdr 决定。
 */
data class GainmapMetadata(
    val ratioMin: Float,
    val ratioMax: Float,
    val gamma: Float,
    val epsilonSdr: Float,
    val epsilonHdr: Float,
    val minDisplayRatioForHdrTransition: Float,
    val displayRatioForFullHdr: Float,
) {
    companion object {
        fun fromBoost(minBoost: Float, maxBoost: Float): GainmapMetadata = GainmapMetadata(
            ratioMin = minBoost,
            ratioMax = maxBoost,
            gamma = 1f,
            epsilonSdr = 1f / 64f,
            epsilonHdr = 1f / 64f,
            minDisplayRatioForHdrTransition = 1f,
            displayRatioForFullHdr = maxBoost,
        )
    }
}

/**
 * Ultra HDR JPEG 编码：使用 Android 14+ 官方 Gainmap API。
 *
 *   SDR Bitmap + Gain Map Bitmap + Gainmap 元数据 → Ultra HDR JPEG
 *
 * 底层由平台（libultrahdr）完成 base JPEG 与 gain map 的合并，
 * 普通 JPEG 查看器仍显示 SDR 主图。
 */
class UltraHdrEncoder {

    fun encode(
        base: Bitmap,
        gainMapBitmap: Bitmap,
        metadata: GainmapMetadata,
        quality: Int,
        output: OutputStream,
    ): Boolean {
        val gainmap = Gainmap(gainMapBitmap)
        gainmap.setRatioMin(metadata.ratioMin, metadata.ratioMin, metadata.ratioMin)
        gainmap.setRatioMax(metadata.ratioMax, metadata.ratioMax, metadata.ratioMax)
        gainmap.setGamma(metadata.gamma, metadata.gamma, metadata.gamma)
        gainmap.setEpsilonSdr(metadata.epsilonSdr, metadata.epsilonSdr, metadata.epsilonSdr)
        gainmap.setEpsilonHdr(metadata.epsilonHdr, metadata.epsilonHdr, metadata.epsilonHdr)
        gainmap.setMinDisplayRatioForHdrTransition(metadata.minDisplayRatioForHdrTransition)
        gainmap.setDisplayRatioForFullHdr(metadata.displayRatioForFullHdr)
        base.setGainmap(gainmap)
        return base.compress(Bitmap.CompressFormat.JPEG, quality, output)
    }
}
