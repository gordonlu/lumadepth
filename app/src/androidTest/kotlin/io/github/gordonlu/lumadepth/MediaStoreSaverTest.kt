package io.github.gordonlu.lumadepth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gordonlu.lumadepth.storage.MediaStoreSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 保存到系统相册的集成测试。
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreSaverTest {

    @Test
    fun saveJpeg_toGallery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val temp = File(context.cacheDir, "test_export.jpg")
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF888888.toInt())
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        try {
            val saved = MediaStoreSaver(context).save(temp)
            assertNotNull(saved.uri)
            assertTrue(saved.displayName.startsWith("LumaDepth_UltraHDR_"))
            assertTrue(saved.sizeBytes > 100)
            // 从 uri 重新读取
            val decoded = context.contentResolver.openInputStream(saved.uri)?.use {
                BitmapFactory.decodeStream(it)
            }
            assertNotNull(decoded)
            assertEquals(64, decoded!!.width)
            assertEquals(48, decoded.height)
            decoded.recycle()
        } finally {
            temp.delete()
        }
    }
}
