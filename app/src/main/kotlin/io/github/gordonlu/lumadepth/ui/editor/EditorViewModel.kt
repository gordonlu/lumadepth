// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.gordonlu.lumadepth.LumaDepthApplication
import io.github.gordonlu.lumadepth.R
import io.github.gordonlu.lumadepth.image.UltraHdrPipeline
import io.github.gordonlu.lumadepth.image.analysis.AnalysisResult
import io.github.gordonlu.lumadepth.model.EffectParameters
import io.github.gordonlu.lumadepth.model.ExportEvent
import io.github.gordonlu.lumadepth.model.ExportResultUi
import io.github.gordonlu.lumadepth.model.ExportStateMachine
import io.github.gordonlu.lumadepth.model.ProcessingError
import io.github.gordonlu.lumadepth.model.ProcessingState
import io.github.gordonlu.lumadepth.model.Stage
import io.github.gordonlu.lumadepth.util.HdrSupport
import io.github.gordonlu.lumadepth.util.LumaDepthException
import io.github.gordonlu.lumadepth.util.LumaErrorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    val sdrPreview: Bitmap? = null,
    val hdrPreview: Bitmap? = null,
    val analysis: AnalysisResult? = null,
    val intensity01: Float = EffectParameters.DEFAULT_INTENSITY,
    val local01: Float = EffectParameters.DEFAULT_LOCAL_ENHANCEMENT,
    val autoOptimize: Boolean = true,
    /** 高质量模式：更自然的细节增强（处理更慢）。 */
    val highQuality: Boolean = false,
    /** 选图后的解码/分析阶段（null = 无加载）。 */
    val previewStage: Stage? = null,
    /** 预览加载失败信息（可关闭）。 */
    val previewError: String? = null,
    /** 导出任务状态（状态机，单值驱动）。 */
    val processing: ProcessingState = ProcessingState.Idle,
)

@OptIn(FlowPreview::class)
class EditorViewModel(
    application: Application,
    private val pipeline: UltraHdrPipeline,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var uri: Uri? = null
    private var exportJob: Job? = null
    private var hdrDisplayAvailable: Boolean = HdrSupport.isHdrDisplayAvailable(application)

    /** 预览参数流：防抖后触发预览渲染，拖动滑块不会产生并行任务。 */
    private val previewParams = MutableStateFlow(Triple(0f, 0f, false))
    private var previewJob: Job? = null

    init {
        previewJob = viewModelScope.launch {
            previewParams
                .debounce(150)
                .collectLatest {
                    renderPreview()
                }
        }
    }

    fun setUri(uri: Uri) {
        // 更换照片时取消旧任务并释放旧图（先清状态，延迟回收，避免与绘制竞争）。
        exportJob?.cancel()
        val oldSdr = _uiState.value.sdrPreview
        val oldHdr = _uiState.value.hdrPreview
        _uiState.update {
            it.copy(sdrPreview = null, hdrPreview = null, analysis = null)
        }
        if (oldSdr != null || oldHdr != null) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                oldSdr?.recycle()
                oldHdr?.recycle()
            }
        }
        this.uri = uri
        viewModelScope.launch {
            _uiState.update {
                it.copy(previewStage = Stage.READING, previewError = null, processing = ProcessingState.Idle)
            }
            try {
                val sdr = pipeline.decodePreview(uri)
                _uiState.update { it.copy(previewStage = Stage.ANALYZING) }
                val analysis = pipeline.analyze(uri) {}
                _uiState.update {
                    it.copy(
                        previewStage = null,
                        sdrPreview = sdr,
                        analysis = analysis,
                    )
                }
                triggerPreview()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LumaDepthException) {
                _uiState.update { it.copy(previewStage = null, previewError = e.userMessage) }
            } catch (e: OutOfMemoryError) {
                _uiState.update {
                    it.copy(previewStage = null, previewError = appString(R.string.error_insufficient_memory))
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        previewStage = null,
                        previewError = appString(R.string.error_unknown, e.message ?: "unknown"),
                    )
                }
            }
        }
    }

    fun setHdrIntensity(intensity01: Float) {
        _uiState.update { it.copy(intensity01 = intensity01.coerceIn(0f, 1f)) }
        triggerPreview()
    }

    fun setLocalEnhancement(local01: Float) {
        _uiState.update { it.copy(local01 = local01.coerceIn(0f, 1f)) }
        triggerPreview()
    }

    fun setAutoOptimize(enabled: Boolean) {
        _uiState.update { it.copy(autoOptimize = enabled) }
        triggerPreview()
    }

    fun setHighQuality(enabled: Boolean) {
        _uiState.update { it.copy(highQuality = enabled) }
        triggerPreview()
    }

    fun export() {
        val current = _uiState.value
        val currentUri = uri ?: return
        // 同一时间最多一个导出任务。
        if (current.processing is ProcessingState.Processing) return
        if (current.sdrPreview == null) return
        val job = viewModelScope.launch {
            try {
                val parameters = EffectParameters(
                    current.intensity01, current.local01, current.autoOptimize, current.highQuality,
                )
                updateState(ExportEvent.Started(Stage.READING))
                val result = pipeline.export(currentUri, parameters, current.analysis) { stage ->
                    updateState(ExportEvent.StageChanged(stage))
                }
                updateState(
                    ExportEvent.Succeeded(
                        ExportResultUi(
                            fileName = result.displayName,
                            width = result.width,
                            height = result.height,
                            fileSizeBytes = result.fileSizeBytes,
                            gainMapWidth = result.verification.gainMapWidth,
                            gainMapHeight = result.verification.gainMapHeight,
                            hasGainmap = result.verification.hasGainmap,
                            location = "Pictures/LumaDepth",
                        ),
                    )
                )
            } catch (e: CancellationException) {
                // 取消是正常状态，不显示错误。
                updateState(ExportEvent.Cancelled)
            } catch (e: LumaDepthException) {
                updateState(ExportEvent.Failed(ProcessingError(e.type, e.userMessage)))
            } catch (e: Exception) {
                updateState(
                    ExportEvent.Failed(
                        ProcessingError(
                            LumaErrorType.PROCESSING_FAILED,
                            appString(R.string.error_unknown, e.message ?: "unknown"),
                        ),
                    )
                )
            }
        }
        exportJob = job
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    fun dismissError() {
        _uiState.update { it.copy(processing = ProcessingState.Idle) }
    }

    fun dismissCancelled() {
        _uiState.update { it.copy(processing = ProcessingState.Idle) }
    }

    fun isHdrDisplayAvailable(): Boolean = hdrDisplayAvailable

    private fun updateState(event: ExportEvent) {
        _uiState.update { it.copy(processing = ExportStateMachine.transition(it.processing, event)) }
    }

    private fun triggerPreview() {
        val state = _uiState.value
        previewParams.value = Triple(state.intensity01, state.local01, state.highQuality)
    }

    private suspend fun renderPreview() {
        val state = _uiState.value
        val sdr = state.sdrPreview ?: return
        val analysis = state.analysis ?: return
        val parameters = EffectParameters(
            state.intensity01, state.local01, state.autoOptimize, state.highQuality,
        )
        try {
            val hdr = pipeline.renderPreview(sdr, parameters, analysis)
            // 旧预览交由 GC 回收（主动回收可能与 Compose 绘制竞争导致崩溃；
            // 预览图约 2MB/张，滑动期间最多累积 2~3 张，可接受）。
            _uiState.update { it.copy(hdrPreview = hdr) }
        } catch (e: OutOfMemoryError) {
            // 预览失败不崩溃：提示并保持原图显示。
            _uiState.update {
                it.copy(previewError = appString(R.string.error_insufficient_memory))
            }
        }
    }

    private fun appString(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    override fun onCleared() {
        previewJob?.cancel()
        exportJob?.cancel()
        val state = _uiState.value
        state.sdrPreview?.recycle()
        state.hdrPreview?.recycle()
        super.onCleared()
    }

    companion object {
        /** 通过 Application 容器提供 pipeline 的 ViewModel 工厂。 */
        @androidx.compose.runtime.Composable
        fun factory(): EditorViewModel {
            val app = androidx.compose.ui.platform.LocalContext.current
                .applicationContext as LumaDepthApplication
            return androidx.lifecycle.viewmodel.compose.viewModel(
                factory = EditorViewModelFactory(app),
            )
        }
    }
}

private class EditorViewModelFactory(private val app: LumaDepthApplication) :
    androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return EditorViewModel(app, app.container.pipeline) as T
    }
}
