// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import io.github.gordonlu.lumadepth.R
import io.github.gordonlu.lumadepth.util.LumaDepthException
import io.github.gordonlu.lumadepth.util.LumaErrorType
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedImageInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
)

/**
 * 通过 MediaStore 原子保存到系统相册（Pictures/LumaDepth），无需任何权限。
 *
 * 流程：IS_PENDING=1 → 写入 → 关闭流 → IS_PENDING=0。
 * 任何失败都会删除未完成的记录，不在相册留下半成品。
 */
class MediaStoreSaver(private val context: Context) {

    fun save(file: File): SavedImageInfo {
        val displayName = buildFileName()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw LumaDepthException(LumaErrorType.SAVE_FAILED, errorSave())
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw LumaDepthException(LumaErrorType.SAVE_FAILED, errorSave())
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedImageInfo(uri, displayName, file.length())
        } catch (e: LumaDepthException) {
            deletePending(uri)
            throw e
        } catch (e: IOException) {
            deletePending(uri)
            val isFull = e.message?.contains("No space left") == true
            throw LumaDepthException(
                if (isFull) LumaErrorType.STORAGE_FULL else LumaErrorType.SAVE_FAILED,
                if (isFull) context.getString(R.string.error_storage_full) else errorSave(),
                e,
            )
        } catch (e: Exception) {
            deletePending(uri)
            throw LumaDepthException(LumaErrorType.SAVE_FAILED, errorSave(), e)
        }
    }

    private fun deletePending(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
            // 忽略清理失败
        }
    }

    private fun buildFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "LumaDepth_UltraHDR_$stamp.jpg"
    }

    private fun errorSave(): String = context.getString(R.string.error_save_failed)

    companion object {
        const val RELATIVE_PATH = "Pictures/LumaDepth"
    }
}
