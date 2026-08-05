// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.model

import io.github.gordonlu.lumadepth.image.UltraHdrPipeline
import io.github.gordonlu.lumadepth.util.LumaDepthException

/**
 * Ultra HDR 效果：SDR 照片 → Ultra HDR JPEG（Gain Map）。
 */
class UltraHdrEffect(private val pipeline: UltraHdrPipeline) : ImageEffect {
    override val id: String = "ultra_hdr"
    override val displayName: String = "Ultra HDR"

    override suspend fun process(input: ImageInput, parameters: EffectParameters): ImageResult {
        return try {
            val export = pipeline.export(input.uri, parameters, onStage = {})
            ImageResult(
                savedUri = export.savedUri,
                displayName = export.displayName,
                width = export.width,
                height = export.height,
                fileSizeBytes = export.fileSizeBytes,
                verification = export.verification,
            )
        } catch (e: LumaDepthException) {
            ImageResult(errorMessage = e.userMessage)
        } catch (e: Exception) {
            ImageResult(errorMessage = e.message ?: "unknown error")
        }
    }
}
