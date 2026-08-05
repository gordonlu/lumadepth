package io.github.gordonlu.lumadepth.image.encode

import android.graphics.BitmapFactory
import java.io.InputStream
import kotlin.math.abs

/**
 * 输出文件验证报告。
 */
data class VerificationReport(
    val ok: Boolean,
    val fileSizeBytes: Long = 0L,
    val baseWidth: Int = 0,
    val baseHeight: Int = 0,
    val hasGainmap: Boolean = false,
    val gainMapWidth: Int = 0,
    val gainMapHeight: Int = 0,
    val ratioMin: Float = 0f,
    val ratioMax: Float = 0f,
    val gamma: Float = 0f,
    val epsilonSdr: Float = 0f,
    val epsilonHdr: Float = 0f,
    val minDisplayRatio: Float = 0f,
    val displayRatioForFullHdr: Float = 0f,
    val details: String = "",
) {
    companion object {
        const val MIN_FILE_SIZE_BYTES = 1000L
    }
}

/**
 * Ultra HDR 输出验证：重新解码文件，确认包含 Gain Map 且元数据合法。
 * 验证内容：文件存在、大小下限、可解码、SDR 主图正常、Gain Map 存在、
 * Ratio Min/Max 合理、Gamma 合理、无 NaN、Gain Map 尺寸有效。
 */
object UltraHdrVerifier {

    fun verify(input: InputStream, fileSizeBytes: Long = 0L): VerificationReport {
        val bitmap = try {
            BitmapFactory.decodeStream(input)
        } catch (e: Throwable) {
            return VerificationReport(ok = false, fileSizeBytes = fileSizeBytes, details = "解码异常：${e.message}")
        }
        if (bitmap == null) {
            return VerificationReport(ok = false, fileSizeBytes = fileSizeBytes, details = "无法解码为位图")
        }
        return try {
            verifyBitmap(bitmap, fileSizeBytes)
        } finally {
            bitmap.recycle()
        }
    }

    private fun verifyBitmap(bitmap: android.graphics.Bitmap, fileSizeBytes: Long): VerificationReport {
        val baseW = bitmap.width
        val baseH = bitmap.height
        val report = VerificationReport(
            ok = false,
            fileSizeBytes = fileSizeBytes,
            baseWidth = baseW,
            baseHeight = baseH,
        )

        if (baseW <= 0 || baseH <= 0) return report.copy(details = "SDR 主图尺寸无效")
        if (fileSizeBytes > 0L && fileSizeBytes < VerificationReport.MIN_FILE_SIZE_BYTES) {
            return report.copy(details = "文件过小（${fileSizeBytes} 字节）")
        }
        if (!bitmap.hasGainmap()) return report.copy(details = "重新解码后未检测到 Gain Map")

        val gainmap = bitmap.getGainmap() ?: return report.copy(details = "Gain Map 元数据缺失")
        val contents = gainmap.gainmapContents
        val ratioMin = gainmap.ratioMin
        val ratioMax = gainmap.ratioMax
        val gamma = gainmap.gamma
        val epsilonSdr = gainmap.epsilonSdr
        val epsilonHdr = gainmap.epsilonHdr

        val gmW = contents.width
        val gmH = contents.height
        val min = ratioMin[0]
        val max = ratioMax[0]
        val g = gamma[0]

        // 合法性检查
        if (gmW <= 0 || gmH <= 0) return report.copy(details = "Gain Map 尺寸无效")
        val scaleX = gmW.toFloat() / baseW
        val scaleY = gmH.toFloat() / baseH
        if (scaleX > 1f || scaleY > 1f) return report.copy(details = "Gain Map 尺寸超过主图")
        if (abs(scaleX - scaleY) / maxOf(scaleX, scaleY, 1e-3f) > 0.2f) {
            return report.copy(details = "Gain Map 宽高比例与主图不一致")
        }
        if (!min.isFinite() || !max.isFinite() || !g.isFinite()) {
            return report.copy(details = "Gain Map 元数据包含非法数值")
        }
        if (min <= 0f || max < min || max <= 1f) return report.copy(details = "Ratio Min/Max 不合理")
        if (g <= 0f) return report.copy(details = "Gamma 不合理")
        if (epsilonSdr[0].isNaN() || epsilonHdr[0].isNaN()) return report.copy(details = "Epsilon 非法")

        return report.copy(
            ok = true,
            hasGainmap = true,
            gainMapWidth = gmW,
            gainMapHeight = gmH,
            ratioMin = min,
            ratioMax = max,
            gamma = g,
            epsilonSdr = epsilonSdr[0],
            epsilonHdr = epsilonHdr[0],
            minDisplayRatio = gainmap.minDisplayRatioForHdrTransition,
            displayRatioForFullHdr = gainmap.displayRatioForFullHdr,
            details = "OK",
        )
    }
}
