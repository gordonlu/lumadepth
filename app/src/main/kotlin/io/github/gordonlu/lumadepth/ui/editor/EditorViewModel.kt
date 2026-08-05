package io.github.gordonlu.lumadepth.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.gordonlu.lumadepth.LumaDepthApplication
import io.github.gordonlu.lumadepth.image.UltraHdrPipeline
import io.github.gordonlu.lumadepth.image.analysis.AnalysisResult
import io.github.gordonlu.lumadepth.model.EffectParameters
import io.github.gordonlu.lumadepth.model.Stage
import io.github.gordonlu.lumadepth.util.HdrSupport
import io.github.gordonlu.lumadepth.util.LumaDepthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
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
    val loading: Boolean = false,
    val stage: Stage? = null,
    val exporting: Boolean = false,
    val errorMessage: String? = null,
    val exportResult: ExportResultUi? = null,
)

data class ExportResultUi(
    val fileName: String,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val gainMapWidth: Int,
    val gainMapHeight: Int,
    val hasGainmap: Boolean,
    val location: String,
)

@OptIn(FlowPreview::class)
class EditorViewModel(
    application: Application,
    private val pipeline: UltraHdrPipeline,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var uri: Uri? = null
    private var hdrDisplayAvailable: Boolean = HdrSupport.isHdrDisplayAvailable(application)

    /** 预览参数流：防抖后触发预览渲染，拖动滑块不会产生并发任务。 */
    private val previewParams = MutableStateFlow(Pair(0f, 0f))
    private var previewJob: kotlinx.coroutines.Job? = null

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
        this.uri = uri
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, errorMessage = null) }
            try {
                val sdr = pipeline.decodePreview(uri)
                val analysis = pipeline.analyze(uri) { stage ->
                    _uiState.update { it.copy(stage = stage) }
                }
                _uiState.update { it.copy(loading = false, stage = null, sdrPreview = sdr, analysis = analysis) }
                triggerPreview()
            } catch (e: LumaDepthException) {
                _uiState.update { it.copy(loading = false, errorMessage = e.userMessage) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, errorMessage = e.message ?: "未知错误") }
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

    fun export() {
        val current = _uiState.value
        val currentUri = uri ?: return
        if (current.exporting || current.sdrPreview == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(exporting = true, stage = null, errorMessage = null, exportResult = null) }
            try {
                val parameters = EffectParameters(current.intensity01, current.local01, current.autoOptimize)
                val result = pipeline.export(currentUri, parameters, current.analysis) { stage ->
                    _uiState.update { it.copy(stage = stage) }
                }
                _uiState.update {
                    it.copy(
                        exporting = false,
                        stage = null,
                        exportResult = ExportResultUi(
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
                }
            } catch (e: LumaDepthException) {
                _uiState.update { it.copy(exporting = false, stage = null, errorMessage = e.userMessage) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(exporting = false, stage = null, errorMessage = e.message ?: "未知错误") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun isHdrDisplayAvailable(): Boolean = hdrDisplayAvailable

    private fun triggerPreview() {
        val state = _uiState.value
        previewParams.value = Pair(state.intensity01, state.local01)
    }

    private suspend fun renderPreview() {
        val state = _uiState.value
        val sdr = state.sdrPreview ?: return
        val analysis = state.analysis ?: return
        val parameters = EffectParameters(state.intensity01, state.local01, state.autoOptimize)
        val hdr = pipeline.renderPreview(sdr, parameters, analysis)
        _uiState.update { it.copy(hdrPreview = hdr) }
    }

    override fun onCleared() {
        previewJob?.cancel()
        _uiState.value.sdrPreview?.recycle()
        _uiState.value.hdrPreview?.recycle()
        super.onCleared()
    }

    companion object {
        @androidx.compose.runtime.Composable
        fun factory() = androidx.lifecycle.viewmodel.compose.viewModel(
            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as LumaDepthApplication
                    return EditorViewModel(app, app.container.pipeline) as T
                }
            },
        )
    }
}
