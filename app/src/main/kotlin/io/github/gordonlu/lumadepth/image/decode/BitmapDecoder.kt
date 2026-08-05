package io.github.gordonlu.lumadepth.image.decode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import io.github.gordonlu.lumadepth.R
import io.github.gordonlu.lumadepth.util.LumaDepthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片解码：统一使用 ImageDecoder（自动处理 EXIF 旋转与色彩空间），
 * 按用途限制分辨率，OOM 时逐级降级。
 */
class BitmapDecoder(private val context: Context) {

    /** 预览图：最长边约 1400。 */
    suspend fun decodePreview(uri: Uri): Bitmap =
        decodeCapped(uri, listOf(PREVIEW_EDGE))

    /** 分析图：最长边约 480。 */
    suspend fun decodeAnalysis(uri: Uri): Bitmap =
        decodeCapped(uri, listOf(ANALYSIS_EDGE))

    /** 导出主图：从 3840 起，OOM 时降级到更小尺寸。 */
    suspend fun decodeForExport(uri: Uri): Bitmap =
        decodeCapped(uri, EXPORT_EDGE_CANDIDATES)

    private suspend fun decodeCapped(uri: Uri, edges: List<Int>): Bitmap =
        withContext(Dispatchers.IO) {
            var lastCause: Throwable? = null
            for (edge in edges) {
                try {
                    return@withContext decodeOnce(uri, edge)
                } catch (e: OutOfMemoryError) {
                    lastCause = e
                } catch (e: LumaDepthException) {
                    throw e
                } catch (e: Exception) {
                    throw LumaDepthException(context.getString(R.string.error_decode_failed), e)
                }
            }
            throw LumaDepthException(context.getString(R.string.error_out_of_memory), lastCause)
        }

    private fun decodeOnce(uri: Uri, longestEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val scale = minOf(
                1f,
                longestEdge.toFloat() / maxOf(info.size.width, info.size.height)
            )
            if (scale < 1f) {
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        return toSrgb(bitmap)
    }

    /** 统一转换到 sRGB 色彩空间（编码域），保证像素计算一致。 */
    private fun toSrgb(bitmap: Bitmap): Bitmap {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val cs = bitmap.colorSpace ?: return bitmap
        if (cs == srgb || cs.model != ColorSpace.Model.RGB) return bitmap
        return bitmap.convert(srgb)
    }

    companion object {
        const val PREVIEW_EDGE = 1400
        const val ANALYSIS_EDGE = 480
        val EXPORT_EDGE_CANDIDATES = listOf(3840, 2560, 1792)
    }
}
