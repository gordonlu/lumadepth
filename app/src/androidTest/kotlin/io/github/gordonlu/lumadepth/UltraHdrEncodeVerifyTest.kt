// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gordonlu.lumadepth.image.analysis.Analysis
import io.github.gordonlu.lumadepth.image.encode.GainmapMetadata
import io.github.gordonlu.lumadepth.image.encode.UltraHdrEncoder
import io.github.gordonlu.lumadepth.image.encode.UltraHdrVerifier
import io.github.gordonlu.lumadepth.image.gainmap.GainMapRenderer
import io.github.gordonlu.lumadepth.image.tonemap.AutoParameters
import io.github.gordonlu.lumadepth.image.tonemap.Srgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 真机/模拟器验证：SDR 位图 + LumaDepth 生成的 Gain Map + 元数据
 * → Ultra HDR JPEG → 重新解码确认 Gain Map 存在。
 */
@RunWith(AndroidJUnit4::class)
class UltraHdrEncodeVerifyTest {

    @Test
    fun encodeAndVerify_ultraHdrJpeg() {
        val width = 256
        val height = 192
        val base = createSyntheticSdr(width, height)
        try {
            // 分析（合成图：从暗到亮的渐变 + 高光区）
            val pixels = IntArray(width * height)
            base.getPixels(pixels, 0, width, 0, 0, width, height)
            val luma = FloatArray(pixels.size) { i ->
                val a = pixels[i]
                Srgb.luminanceLinear(
                    Srgb.toLinear(((a shr 16) and 0xFF) / 255f),
                    Srgb.toLinear(((a shr 8) and 0xFF) / 255f),
                    Srgb.toLinear((a and 0xFF) / 255f),
                )
            }
            val analysis = Analysis.analyze(luma)
            val params = AutoParameters.forAnalysis(analysis, 0.5f, 0.1f, true)

            // Gain Map（1/4 尺寸）
            val gainMapSource = Bitmap.createScaledBitmap(base, width / 4, height / 4, true)
            val gainMap = GainMapRenderer().render(gainMapSource, params)
            try {
                assertEquals(width / 4, gainMap.width)
                assertEquals(height / 4, gainMap.height)

                // 编码
                val out = ByteArrayOutputStream()
                val ok = UltraHdrEncoder().encode(
                    base = base,
                    gainMapBitmap = gainMap,
                    metadata = GainmapMetadata.fromBoost(params.minBoost, params.maxBoost),
                    quality = 95,
                    output = out,
                )
                assertTrue("Ultra HDR 编码失败", ok)
                val bytes = out.toByteArray()
                assertTrue("输出文件过小", bytes.size > 1000)

                // 验证
                val report = UltraHdrVerifier.verify(ByteArrayInputStream(bytes), bytes.size.toLong())
                assertTrue("验证失败：${report.details}", report.ok)
                assertTrue(report.hasGainmap)
                assertEquals(width, report.baseWidth)
                assertEquals(height, report.baseHeight)
                assertEquals(width / 4, report.gainMapWidth)
                assertEquals(height / 4, report.gainMapHeight)
                assertTrue(report.ratioMax >= report.ratioMin)
                assertTrue(report.ratioMin > 0f)
                assertTrue(report.gamma > 0f)

                // 独立重新解码：确认 hasGainmap
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull(decoded)
                assertTrue("重新解码后 hasGainmap 应为 true", decoded!!.hasGainmap())
                assertEquals(width, decoded.width)
                assertEquals(height, decoded.height)
                val gainmap = decoded.gainmap
                assertNotNull(gainmap)
                decoded.recycle()
            } finally {
                gainMapSource.recycle()
                gainMap.recycle()
            }
        } finally {
            base.recycle()
        }
    }

    private fun createSyntheticSdr(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = ((x + y) * 255 / (width + height)).coerceIn(0, 255)
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }
}
