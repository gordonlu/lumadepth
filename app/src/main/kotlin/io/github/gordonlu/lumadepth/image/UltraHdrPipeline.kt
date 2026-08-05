package io.github.gordonlu.lumadepth.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.github.gordonlu.lumadepth.image.analysis.Analysis
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
import io.github.gordonlu.lumadepth.image.tonemap.Srgb
import io.github.gordonlu.lumadepth.model.EffectParameters
import io.github.gordonlu.lumadepth.model.Stage
import io.github.gordonlu.lumadepth.storage.MediaStoreSaver
import io.github.gordonlu.lumadepth.storage.SavedImageInfo
import io.github.gordonlu.lumadepth.util.LumaDepthException
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
     * 1. 解码全尺寸主图（带 OOM 降级）
     * 2. 生成 1/4 尺寸 Gain Map
     * 3. 编码为 Ultra HDR JPEG（临时文件）
     * 4. 重新读取验证（hasGainmap + 元数据）
     * 5. 保存到 MediaStore 后再次验证
     */
    suspend fun export(
        uri: Uri,
        parameters: EffectParameters,
        analysis: AnalysisResult? = null,
        onStage: (Stage) -> Unit = {},
    ): ExportResult {
        onStage(Stage.READING)
        val base = decoder.decodeForExport(uri)
        try {
            val analysisResult = analysis ?: run {
                onStage(Stage.ANALYZING)
                analyzer.analyze(uri)
            }
            onStage(Stage.TONE_MAPPING)
            val params = AutoParameters.forAnalysis(
                analysisResult,
                parameters.hdrIntensity01,
                parameters.localEnhancement01,
                parameters.autoOptimize,
            )

            onStage(Stage.GAIN_MAP)
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

                onStage(Stage.ENCODING)
                val tempFile = createTempFile()
                try {
                    val encoded = withContext(Dispatchers.IO) {
                        tempFile.outputStream().use { out ->
                            encoder.encode(
                                base,
                                gainMap!!,
                                GainmapMetadata.fromBoost(params.minBoost, params.maxBoost),
                                JPEG_QUALITY,
                                out,
                            )
                        }
                    }
                    if (!encoded) throw LumaDepthException("Ultra HDR 编码失败")

                    onStage(Stage.VERIFYING)
                    val report = withContext(Dispatchers.IO) {
                        tempFile.inputStream().use { UltraHdrVerifier.verify(it, tempFile.length()) }
                    }
                    if (!report.ok) {
                        throw LumaDepthException("输出文件验证失败：${report.details}")
                    }

                    onStage(Stage.SAVING)
                    val saved: SavedImageInfo = saver.save(tempFile)
                    // 对相册中的文件再次验证，确保保存过程没有损坏。
                    val savedReport = context.contentResolver.openInputStream(saved.uri)?.use {
                        UltraHdrVerifier.verify(it, saved.sizeBytes)
                    } ?: throw LumaDepthException("输出文件验证失败：无法重新读取")

                    if (!savedReport.ok) {
                        throw LumaDepthException("输出文件验证失败：${savedReport.details}")
                    }
                    return ExportResult(
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
    }
}
