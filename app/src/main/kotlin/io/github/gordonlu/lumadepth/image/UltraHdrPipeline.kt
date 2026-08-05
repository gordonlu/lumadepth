// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import io.github.gordonlu.lumadepth.BuildConfig
import io.github.gordonlu.lumadepth.R
import io.github.gordonlu.lumadepth.image.analysis.AnalysisResult
import io.github.gordonlu.lumadepth.image.analysis.ImageAnalyzer
import io.github.gordonlu.lumadepth.image.decode.BitmapDecoder
import io.github.gordonlu.lumadepth.image.encode.GainmapMetadata
import io.github.gordonlu.lumadepth.image.encode.UltraHdrEncoder
import io.github.gordonlu.lumadepth.image.encode.UltraHdrVerifier
import io.github.gordonlu.lumadepth.image.encode.VerificationReport
import io.github.gordonlu.lumadepth.image.gainmap.GainMapRenderer
import io.github.gordonlu.lumadepth.image.tonemap.AutoParameters
import io.github.gordonlu.lumadepth.image.tonemap.PreviewRenderer
import io.github.gordonlu.lumadepth.model.EffectParameters
import io.github.gordonlu.lumadepth.model.Stage
import io.github.gordonlu.lumadepth.storage.MediaStoreSaver
import io.github.gordonlu.lumadepth.storage.SavedImageInfo
import io.github.gordonlu.lumadepth.util.LumaDepthException
import io.github.gordonlu.lumadepth.util.LumaErrorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ExportResult(
    val savedUri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val verification: VerificationReport,
)

/**
 * Ultra HDR 处理管线：
 *   SDR 照片 → 亮度分析 → 逆色调映射参数 → Gain Map 生成
 *   → Ultra HDR JPEG 编码 → 输出验证 → 保存到相册
 *
 * 线程模型：整体在 IO 线程执行（文件/编码/MediaStore），
 * 像素密集段切 Default；取消（CancellationException）会清理临时文件。
 */
class UltraHdrPipeline(
    private val context: Context,
    private val decoder: BitmapDecoder,
    private val analyzer: ImageAnalyzer,
    private val previewRenderer: PreviewRenderer,
    private val gainMapRenderer: GainMapRenderer,
    private val encoder: UltraHdrEncoder,
    private val saver: MediaStoreSaver,
) {

    /** 解码预览图（SDR）。 */
    suspend fun decodePreview(uri: Uri): Bitmap = decoder.decodePreview(uri)

    /** 亮度分析。 */
    suspend fun analyze(uri: Uri, onStage: (Stage) -> Unit = {}): AnalysisResult {
        onStage(Stage.ANALYZING)
        return analyzer.analyze(uri)
    }

    /** 渲染 HDR 效果预览（SDR 模拟，不写盘）。 */
    suspend fun renderPreview(sdr: Bitmap, parameters: EffectParameters, analysis: AnalysisResult): Bitmap =
        withContext(Dispatchers.Default) {
            val params = AutoParameters.forAnalysis(
                analysis,
                parameters.hdrIntensity01,
                parameters.localEnhancement01,
                parameters.autoOptimize,
            )
            previewRenderer.render(sdr, params)
        }

    /**
     * 导出 Ultra HDR JPEG：
     * 1. 内存预算 → 解码主图（带 OOM 降级）
     * 2. 生成 1/4 尺寸 Gain Map
     * 3. 编码为 Ultra HDR JPEG（临时文件）
     * 4. 重新读取验证（hasGainmap + 元数据）
     * 5. 保存到 MediaStore（IS_PENDING 原子流程）后再次验证
     *
     * 取消时：抛 CancellationException，临时文件在 finally 中删除。
     */
    suspend fun export(
        uri: Uri,
        parameters: EffectParameters,
        analysis: AnalysisResult? = null,
        onStage: (Stage) -> Unit = {},
    ): ExportResult = withContext(Dispatchers.IO) {
        val stageTimer = StageTimer()
        fun stage(s: Stage) {
            stageTimer.mark(s)
            onStage(s)
        }
        stage(Stage.READING)
        val base = decoder.decodeForExport(uri)
        try {
            val analysisResult = analysis ?: run {
                stage(Stage.ANALYZING)
                analyzer.analyze(uri)
            }
            stage(Stage.TONE_MAPPING)
            val params = AutoParameters.forAnalysis(
                analysisResult,
                parameters.hdrIntensity01,
                parameters.localEnhancement01,
                parameters.autoOptimize,
            )

            stage(Stage.GAIN_MAP)
            val gainMapSource = withContext(Dispatchers.IO) {
                val gw = (base.width / 4).coerceAtLeast(1)
                val gh = (base.height / 4).coerceAtLeast(1)
                Bitmap.createScaledBitmap(base, gw, gh, true)
            }
            var gainMap: Bitmap? = null
            try {
                gainMap = withContext(Dispatchers.Default) {
                    gainMapRenderer.render(gainMapSource, params)
                }

                stage(Stage.ENCODING)
                val tempFile = createTempFile()
                try {
                    val encoded = tempFile.outputStream().use { out ->
                        encoder.encode(
                            base,
                            gainMap!!,
                            GainmapMetadata.fromBoost(params.minBoost, params.maxBoost),
                            JPEG_QUALITY,
                            out,
                        )
                    }
                    if (!encoded) {
                        throw LumaDepthException(
                            LumaErrorType.ENCODE_FAILED,
                            context.getString(R.string.error_encode_failed),
                        )
                    }

                    stage(Stage.VERIFYING)
                    val report = tempFile.inputStream().use { UltraHdrVerifier.verify(it, tempFile.length()) }
                    if (!report.ok) {
                        throw LumaDepthException(
                            LumaErrorType.GAIN_MAP_VALIDATION_FAILED,
                            context.getString(R.string.error_verify_failed, report.details),
                        )
                    }

                    stage(Stage.SAVING)
                    val saved: SavedImageInfo = saver.save(tempFile)
                    // 对相册中的文件再次验证，确保保存过程没有损坏。
                    val savedReport = context.contentResolver.openInputStream(saved.uri)?.use {
                        UltraHdrVerifier.verify(it, saved.sizeBytes)
                    } ?: throw LumaDepthException(
                        LumaErrorType.GAIN_MAP_VALIDATION_FAILED,
                        context.getString(R.string.error_verify_failed, "无法重新读取"),
                    )

                    if (!savedReport.ok) {
                        throw LumaDepthException(
                            LumaErrorType.GAIN_MAP_VALIDATION_FAILED,
                            context.getString(R.string.error_verify_failed, savedReport.details),
                        )
                    }
                    stageTimer.logStages()
                    ExportResult(
                        savedUri = saved.uri,
                        displayName = saved.displayName,
                        width = base.width,
                        height = base.height,
                        fileSizeBytes = saved.sizeBytes,
                        verification = savedReport,
                    )
                } finally {
                    tempFile.delete()
                }
            } finally {
                gainMap?.recycle()
                gainMapSource.recycle()
            }
        } finally {
            base.recycle()
        }
    }

    private fun createTempFile(): File {
        val dir = File(context.cacheDir, "lumadepth").apply { mkdirs() }
        return File(dir, "export_${System.currentTimeMillis()}.jpg")
    }

    companion object {
        const val JPEG_QUALITY = 95
        private const val TAG = "LumaDepthPipeline"
    }

    /** Debug 构建记录每个阶段的耗时。 */
    private inner class StageTimer {
        private var lastTime = System.currentTimeMillis()
        private val marks = StringBuilder()

        fun mark(s: Stage) {
            val now = System.currentTimeMillis()
            val delta = now - lastTime
            lastTime = now
            if (BuildConfig.DEBUG) {
                marks.append("$s:${delta}ms ")
            }
        }

        fun logStages() {
            if (!BuildConfig.DEBUG) return
            val mem = Runtime.getRuntime()
            val usedMb = (mem.totalMemory() - mem.freeMemory()) / (1024 * 1024)
            val maxMb = mem.maxMemory() / (1024 * 1024)
            Log.d(TAG, "stages: $marks heap ${usedMb}MB/$maxMb MB")
        }
    }
}
