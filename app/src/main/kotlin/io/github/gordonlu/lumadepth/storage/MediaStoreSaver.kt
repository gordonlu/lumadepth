package io.github.gordonlu.lumadepth.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import io.github.gordonlu.lumadepth.R
import io.github.gordonlu.lumadepth.util.LumaDepthException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedImageInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
)

/**
 * 通过 MediaStore 保存到系统相册（Pictures/LumaDepth），无需任何权限。
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
            ?: throw LumaDepthException(context.getString(R.string.error_save_failed))
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw LumaDepthException(context.getString(R.string.error_save_failed))
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedImageInfo(uri, displayName, file.length())
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
                // 忽略清理失败
            }
            if (e is LumaDepthException) throw e
            throw LumaDepthException(context.getString(R.string.error_save_failed), e)
        }
    }

    private fun buildFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "LumaDepth_UltraHDR_$stamp.jpg"
    }

    companion object {
        const val RELATIVE_PATH = "Pictures/LumaDepth"
    }
}
