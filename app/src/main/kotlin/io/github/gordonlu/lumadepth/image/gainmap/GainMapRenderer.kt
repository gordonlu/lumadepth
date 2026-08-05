package io.github.gordonlu.lumadepth.image.gainmap

import android.graphics.Bitmap
import io.github.gordonlu.lumadepth.image.tonemap.ToneMapParameters

/**
 * Gain Map 位图渲染（Android 包装）：Bitmap → 单通道灰度 Bitmap。
 */
class GainMapRenderer {

    fun render(source: Bitmap, params: ToneMapParameters): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = GainMapRenderCore.renderPixels(pixels, w, h, params)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, w, 0, 0, w, h)
        return bitmap
    }
}
