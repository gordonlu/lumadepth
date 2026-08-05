package io.github.gordonlu.lumadepth.image.tonemap

import android.graphics.Bitmap

/**
 * HDR 预览渲染（Android 包装）：SDR Bitmap → 模拟 HDR 效果的 Bitmap。
 */
class PreviewRenderer {

    fun render(source: Bitmap, params: ToneMapParameters): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = PreviewRenderCore.renderPixels(pixels, w, h, params)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, w, 0, 0, w, h)
        return bitmap
    }
}
