// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gordonlu.lumadepth.image.analysis.Analysis
import io.github.gordonlu.lumadepth.image.encode.GainmapMetadata
import io.github.gordonlu.lumadepth.image.encode.UltraHdrEncoder
import io.github.gordonlu.lumadepth.image.encode.UltraHdrVerifier
import io.github.gordonlu.lumadepth.image.gainmap.GainMapRenderer
import io.github.gordonlu.lumadepth.image.tonemap.AutoParameters
import io.github.gordonlu.lumadepth.image.tonemap.Srgb
import io.github.gordonlu.lumadepth.storage.MediaStoreSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 稳定性测试：极端输入 + 重复处理（mem.md 第 10 节）。
 */
@RunWith(AndroidJUnit4::class)
class StabilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 完整处理一次小图（含保存到相册），返回是否成功。 */
    private fun processOnce(width: Int, height: Int, color: Int = 0xFF808080.toInt()): Boolean {
        val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            base.eraseColor(color)
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
            val gainMapSource = Bitmap.createScaledBitmap(
                base,
                (width / 4).coerceAtLeast(1),
                (height / 4).coerceAtLeast(1),
                true,
            )
            val gainMap = GainMapRenderer().render(gainMapSource, params)
            try {
                val out = ByteArrayOutputStream()
                val ok = UltraHdrEncoder().encode(
                    base, gainMap,
                    GainmapMetadata.fromBoost(params.minBoost, params.maxBoost),
                    95, out,
                )
                assertTrue("编码失败", ok)
                val bytes = out.toByteArray()
                val report = UltraHdrVerifier.verify(ByteArrayInputStream(bytes), bytes.size.toLong())
                assertTrue("验证失败：${report.details}", report.ok)

                // 保存到相册（原子流程）
                val temp = File(context.cacheDir, "stability_${System.nanoTime()}.jpg")
                temp.writeBytes(bytes)
                try {
                    val saved = MediaStoreSaver(context).save(temp)
                    val decoded = context.contentResolver.openInputStream(saved.uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                    assertNotNull(decoded)
                    decoded?.recycle()
                } finally {
                    temp.delete()
                }
                return true
            } finally {
                gainMapSource.recycle()
                gainMap.recycle()
            }
        } finally {
            base.recycle()
        }
    }

    @Test
    fun extremeInput_1x1() {
        assertTrue(processOnce(1, 1))
    }

    @Test
    fun extremeInput_ultraWide() {
        assertTrue(processOnce(512, 8))
    }

    @Test
    fun extremeInput_ultraTall() {
        assertTrue(processOnce(8, 512))
    }

    @Test
    fun extremeInput_allBlack() {
        assertTrue(processOnce(128, 96, 0xFF000000.toInt()))
    }

    @Test
    fun extremeInput_allWhite() {
        assertTrue(processOnce(128, 96, 0xFFFFFFFF.toInt()))
    }

    @Test
    fun extremeInput_pngAlphaFlattened() {
        // 带 alpha 的像素：按 ARGB 处理，alpha 不参与亮度
        val base = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            base.eraseColor(0x80FF0000.toInt())
            val pixels = IntArray(64 * 64)
            base.getPixels(pixels, 0, 64, 0, 0, 64, 64)
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
            val gainMapSource = Bitmap.createScaledBitmap(base, 16, 16, true)
            val gainMap = GainMapRenderer().render(gainMapSource, params)
            try {
                val out = ByteArrayOutputStream()
                val ok = UltraHdrEncoder().encode(base, gainMap,
                    GainmapMetadata.fromBoost(params.minBoost, params.maxBoost), 95, out)
                assertTrue(ok)
                val report = UltraHdrVerifier.verify(ByteArrayInputStream(out.toByteArray()), out.toByteArray().size.toLong())
                assertTrue(report.details, report.ok)
            } finally {
                gainMapSource.recycle()
                gainMap.recycle()
            }
        } finally {
            base.recycle()
        }
    }

    @Test
    fun corruptedJpeg_failsGracefully() {
        val junk = ByteArray(4096) { 0x5A.toByte() }
        val decoded = BitmapFactory.decodeByteArray(junk, 0, junk.size)
        assertEquals(null, decoded) // 损坏 JPEG 解码失败，不崩溃
    }

    @Test
    fun unsupportedFormat_failsGracefully() {
        // 随机字节（既不是 JPEG 也不是 PNG/WebP）
        val junk = ByteArray(2048) { (it % 251).toByte() }
        val decoded = BitmapFactory.decodeByteArray(junk, 0, junk.size)
        assertEquals(null, decoded)
    }

    @Test
    fun repeatProcessing_20times_noCrashNoGrowth() {
        // mem.md：连续处理 20 次，检查内存没有持续单向增长、无崩溃。
        val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        var maxAfter = 0L
        for (i in 0 until 20) {
            assertTrue("第 ${i + 1} 次处理失败", processOnce(128, 96))
            val used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            if (used > maxAfter) maxAfter = used
        }
        // 粗略检查：20 轮后常驻内存增长不超过 64MB（GC 噪音容忍）
        val growth = maxAfter - before
        assertTrue("内存持续增长：$growth", growth < 64L * 1024 * 1024)
    }

    /**
     * 插桩验证：通过 DisplayManager 获取系统默认 Display 查询 HDR 能力不抛异常。
     * 注意：Application Context 未绑定 Display，本项目已禁止用 Context.getDisplay()。
     */
    @Test
    fun hdrSupport_withRealDisplay_noThrow() {
        val dm = context.getSystemService(android.view.DisplayManager::class.java)
        assertNotNull(dm)
        val display = dm!!.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val ok = io.github.gordonlu.lumadepth.util.HdrSupport.isHdrDisplayAvailable(display)
        println("HdrSupport default display -> $ok")
        assertTrue(true) // 不抛异常即通过
    }
}
