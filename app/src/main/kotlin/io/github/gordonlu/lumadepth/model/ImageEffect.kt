package io.github.gordonlu.lumadepth.model

import android.net.Uri
import io.github.gordonlu.lumadepth.image.encode.VerificationReport

data class ImageInput(
    val uri: Uri,
    val mimeType: String?,
)

data class ImageResult(
    val savedUri: Uri? = null,
    val displayName: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fileSizeBytes: Long = 0L,
    val verification: VerificationReport? = null,
    val errorMessage: String? = null,
)

/**
 * 图像效果接口。第一版实现 [UltraHdrEffect]，
 * 未来可扩展 Depth Effect / Relight / Enhance 等效果。
 */
interface ImageEffect {
    val id: String
    val displayName: String

    suspend fun process(input: ImageInput, parameters: EffectParameters): ImageResult
}
