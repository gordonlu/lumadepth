// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.util

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ultra HDR 检测：轻量解码（极小尺寸）并检查 hasGainmap。
 * 用于帮助用户识别相册中哪些照片包含 HDR 信息。
 */
object HdrDetector {

    suspend fun isHdr(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Gain Map 是附加元数据，与像素尺寸无关，极小尺寸即可检测。
                decoder.setTargetSize(1, 1)
            }
            try {
                bitmap.hasGainmap()
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            false
        }
    }
}
