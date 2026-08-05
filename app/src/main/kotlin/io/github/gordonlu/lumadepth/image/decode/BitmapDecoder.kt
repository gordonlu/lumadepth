// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image.decode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import io.github.gordonlu.lumadepth.R
import io.github.gordonlu.lumadepth.util.LumaDepthException
import io.github.gordonlu.lumadepth.util.LumaErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片解码：统一使用 ImageDecoder（自动处理 EXIF 旋转与色彩空间），
 * 按用途限制分辨率；导出前执行内存预算，OOM 时逐级降级。
 */
class BitmapDecoder(private val context: Context) {

    /** 预览图：最长边约 1400。 */
    suspend fun decodePreview(uri: Uri): Bitmap =
        decodeCapped(uri, listOf(PREVIEW_EDGE))

    /** 分析图：最长边约 480。 */
    suspend fun decodeAnalysis(uri: Uri): Bitmap =
        decodeCapped(uri, listOf(ANALYSIS_EDGE))

    /**
     * 导出主图：先按内存预算选择可用尺寸（从 3840 起），OOM 时继续降级。
     * 所有候选都超出预算时抛出 IMAGE_TOO_LARGE / INSUFFICIENT_MEMORY。
     */
    suspend fun decodeForExport(uri: Uri): Bitmap {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val firstEdge = MemoryBudget.pickEdge(EXPORT_EDGE_CANDIDATES, maxMemory)
            ?: throw LumaDepthException(
                LumaErrorType.IMAGE_TOO_LARGE,
                context.getString(R.string.error_image_too_large),
            )
        val fromFirst = EXPORT_EDGE_CANDIDATES.dropWhile { it > firstEdge }
        return decodeCapped(uri, fromFirst)
    }

    private suspend fun decodeCapped(uri: Uri, edges: List<Int>): Bitmap =
        withContext(Dispatchers.IO) {
            var lastOom: Throwable? = null
            for (edge in edges) {
                try {
                    return@withContext decodeOnce(uri, edge)
                } catch (e: OutOfMemoryError) {
                    lastOom = e
                } catch (e: LumaDepthException) {
                    throw e
                } catch (e: Exception) {
                    throw LumaDepthException(LumaErrorType.DECODE_FAILED, errorDecode(), e)
                }
            }
            if (lastOom != null) {
                throw LumaDepthException(LumaErrorType.INSUFFICIENT_MEMORY, errorMemory(), lastOom)
            }
            throw LumaDepthException(LumaErrorType.DECODE_FAILED, errorDecode(), lastOom)
        }

    private fun decodeOnce(uri: Uri, longestEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
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
        return toSrgb(decoded)
    }

    /** 统一转换到 sRGB 色彩空间（编码域）。需要转换时回收原图。 */
    private fun toSrgb(bitmap: Bitmap): Bitmap {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val cs = bitmap.colorSpace ?: return bitmap
        if (cs == srgb || cs.model != ColorSpace.Model.RGB) return bitmap
        val out = try {
            // Canvas 绘制时自动把源位图色彩转换到目标 sRGB 色彩空间。
            Bitmap.createBitmap(
                bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888, false, srgb
            ).also { target ->
                Canvas(target).drawBitmap(bitmap, 0f, 0f, null)
            }
        } finally {
            bitmap.recycle()
        }
        return out
    }

    private fun errorDecode(): String = context.getString(R.string.error_decode_failed)
    private fun errorMemory(): String = context.getString(R.string.error_insufficient_memory)

    companion object {
        const val PREVIEW_EDGE = 1400
        const val ANALYSIS_EDGE = 480
        val EXPORT_EDGE_CANDIDATES = listOf(3840, 2560, 1792)
    }
}
