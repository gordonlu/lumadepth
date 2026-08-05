// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.model

import io.github.gordonlu.lumadepth.util.LumaErrorType

/** 导出结果（纯数据，UI 展示用）。 */
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

data class ProcessingError(
    val type: LumaErrorType,
    val message: String,
)

/**
 * 导出任务状态机（禁止用多个互相矛盾的 Boolean 拼接任务状态）。
 */
sealed interface ProcessingState {
    /** 无任务。 */
    data object Idle : ProcessingState

    /** 处理中，可取消。 */
    data class Processing(
        val stage: Stage,
        val cancellable: Boolean = true,
    ) : ProcessingState

    /** 成功。 */
    data class Success(val result: ExportResultUi) : ProcessingState

    /** 失败。 */
    data class Failed(val error: ProcessingError) : ProcessingState

    /** 用户取消（正常状态）。 */
    data object Cancelled : ProcessingState
}

/** 状态机事件。 */
sealed interface ExportEvent {
    data class Started(val stage: Stage, val cancellable: Boolean = true) : ExportEvent
    data class StageChanged(val stage: Stage) : ExportEvent
    data class Succeeded(val result: ExportResultUi) : ExportEvent
    data class Failed(val error: ProcessingError) : ExportEvent
    data object Cancelled : ExportEvent
}

/** 纯状态机转移函数（可 JVM 单元测试）。 */
object ExportStateMachine {

    fun transition(current: ProcessingState, event: ExportEvent): ProcessingState = when (event) {
        // 任何非 Processing 状态都可启动新任务（重新导出）。
        is ExportEvent.Started -> ProcessingState.Processing(event.stage, event.cancellable)
        is ExportEvent.StageChanged ->
            if (current is ProcessingState.Processing) current.copy(stage = event.stage) else current
        // 终态（成功/失败/取消）只接受来自 Processing 的迁移，迟到的旧任务事件不覆盖当前状态。
        is ExportEvent.Succeeded ->
            if (current is ProcessingState.Processing) ProcessingState.Success(event.result) else current
        is ExportEvent.Failed ->
            if (current is ProcessingState.Processing) ProcessingState.Failed(event.error) else current
        ExportEvent.Cancelled ->
            if (current is ProcessingState.Processing) ProcessingState.Cancelled else current
    }

    fun apply(current: ProcessingState, events: List<ExportEvent>): ProcessingState =
        events.fold(current) { acc, event -> transition(acc, event) }
}
