// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth

import io.github.gordonlu.lumadepth.model.ExportEvent
import io.github.gordonlu.lumadepth.model.ExportResultUi
import io.github.gordonlu.lumadepth.model.ExportStateMachine
import io.github.gordonlu.lumadepth.model.ProcessingError
import io.github.gordonlu.lumadepth.model.ProcessingState
import io.github.gordonlu.lumadepth.model.Stage
import io.github.gordonlu.lumadepth.util.LumaErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出任务状态机测试：验证状态迁移正确、不会出现矛盾状态。
 */
class ExportStateMachineTest {

    private val result = ExportResultUi(
        fileName = "test.jpg", width = 100, height = 80, fileSizeBytes = 1024,
        gainMapWidth = 25, gainMapHeight = 20, hasGainmap = true, location = "Pictures/LumaDepth",
    )

    @Test
    fun idle_toProcessing_onStart() {
        val state = ExportStateMachine.transition(ProcessingState.Idle, ExportEvent.Started(Stage.READING))
        assertTrue(state is ProcessingState.Processing)
        assertEquals(Stage.READING, (state as ProcessingState.Processing).stage)
        assertTrue(state.cancellable)
    }

    @Test
    fun stageChanges_whileProcessing() {
        val started = ExportStateMachine.transition(ProcessingState.Idle, ExportEvent.Started(Stage.READING))
        val changed = ExportStateMachine.transition(started, ExportEvent.StageChanged(Stage.ENCODING))
        assertTrue(changed is ProcessingState.Processing)
        assertEquals(Stage.ENCODING, (changed as ProcessingState.Processing).stage)
    }

    @Test
    fun stageChanges_ignoredOutsideProcessing() {
        // 已成功后再收到阶段事件，状态不应被破坏
        val success = ExportStateMachine.transition(ProcessingState.Idle, ExportEvent.Succeeded(result))
        val after = ExportStateMachine.transition(success, ExportEvent.StageChanged(Stage.ENCODING))
        assertEquals(success, after)
    }

    @Test
    fun processing_toSuccess() {
        val started = ExportStateMachine.transition(ProcessingState.Idle, ExportEvent.Started(Stage.READING))
        val done = ExportStateMachine.transition(started, ExportEvent.Succeeded(result))
        assertTrue(done is ProcessingState.Success)
        assertEquals(result, (done as ProcessingState.Success).result)
    }

    @Test
    fun processing_toFailed() {
        val started = ExportStateMachine.transition(ProcessingState.Idle, ExportEvent.Started(Stage.READING))
        val error = ProcessingError(LumaErrorType.ENCODE_FAILED, "编码失败")
        val failed = ExportStateMachine.transition(started, ExportEvent.Failed(error))
        assertTrue(failed is ProcessingState.Failed)
        assertEquals(LumaErrorType.ENCODE_FAILED, (failed as ProcessingState.Failed).error.type)
    }

    @Test
    fun processing_toCancelled() {
        val started = ExportStateMachine.transition(ProcessingState.Idle, ExportEvent.Started(Stage.READING))
        val cancelled = ExportStateMachine.transition(started, ExportEvent.Cancelled)
        assertEquals(ProcessingState.Cancelled, cancelled)
    }

    @Test
    fun cancelled_isNormalState_notError() {
        // 取消后的状态不是 Failed，UI 不得显示错误。
        val started = ExportStateMachine.transition(ProcessingState.Idle, ExportEvent.Started(Stage.READING))
        val cancelled = ExportStateMachine.transition(started, ExportEvent.Cancelled)
        assertTrue(cancelled !is ProcessingState.Failed)
        assertEquals(ProcessingState.Cancelled, cancelled)
    }

    @Test
    fun fullSequence_endToEnd() {
        val events = listOf(
            ExportEvent.Started(Stage.READING),
            ExportEvent.StageChanged(Stage.ANALYZING),
            ExportEvent.StageChanged(Stage.TONE_MAPPING),
            ExportEvent.StageChanged(Stage.GAIN_MAP),
            ExportEvent.StageChanged(Stage.ENCODING),
            ExportEvent.StageChanged(Stage.VERIFYING),
            ExportEvent.StageChanged(Stage.SAVING),
            ExportEvent.Succeeded(result),
        )
        val final = ExportStateMachine.apply(ProcessingState.Idle, events)
        assertTrue(final is ProcessingState.Success)
        assertEquals(result, (final as ProcessingState.Success).result)
    }

    @Test
    fun lateSuccess_afterCancel_staysCancelled() {
        // 已取消后迟到的事件（旧协程清理）不应覆盖取消状态
        val started = ExportStateMachine.transition(ProcessingState.Idle, ExportEvent.Started(Stage.READING))
        val cancelled = ExportStateMachine.transition(started, ExportEvent.Cancelled)
        val late = ExportStateMachine.transition(cancelled, ExportEvent.Succeeded(result))
        assertEquals(ProcessingState.Cancelled, late)
    }

    @Test
    fun lateEvents_afterFailure_doNotOverride() {
        val error = ProcessingError(LumaErrorType.SAVE_FAILED, "x")
        val started = ExportStateMachine.transition(ProcessingState.Idle, ExportEvent.Started(Stage.READING))
        val failed = ExportStateMachine.transition(started, ExportEvent.Failed(error))
        // 迟到的事件（旧任务清理）不得覆盖失败状态
        assertEquals(failed, ExportStateMachine.transition(failed, ExportEvent.Succeeded(result)))
        assertEquals(failed, ExportStateMachine.transition(failed, ExportEvent.Cancelled))
        assertEquals(failed, ExportStateMachine.transition(failed, ExportEvent.StageChanged(Stage.ENCODING)))
    }

    @Test
    fun success_isTerminal_ignoresLaterEvents() {
        val started = ExportStateMachine.transition(ProcessingState.Idle, ExportEvent.Started(Stage.READING))
        val success = ExportStateMachine.transition(started, ExportEvent.Succeeded(result))
        // 成功后的迟到失败/取消事件不覆盖
        assertEquals(
            success,
            ExportStateMachine.transition(
                success,
                ExportEvent.Failed(ProcessingError(LumaErrorType.SAVE_FAILED, "x")),
            ),
        )
        assertEquals(success, ExportStateMachine.transition(success, ExportEvent.Cancelled))
    }

    @Test
    fun restarted_afterTerminal_isAllowed() {
        // 终态后可以重新开始（用户再次导出）
        val success = ExportStateMachine.transition(
            ProcessingState.Idle,
            ExportEvent.Succeeded(result).let { ExportEvent.Started(Stage.READING) }.let {
                ExportEvent.Succeeded(result)
            },
        )
        val restarted = ExportStateMachine.transition(success, ExportEvent.Started(Stage.READING))
        assertTrue(restarted is ProcessingState.Processing)
    }

    @Test
    fun errorEvents_onIdle_noCrash() {
        // 任意状态收到任意事件都不应崩溃（幂等/忽略）
        val states = listOf<ProcessingState>(
            ProcessingState.Idle,
            ProcessingState.Processing(Stage.ENCODING),
            ProcessingState.Success(result),
            ProcessingState.Failed(ProcessingError(LumaErrorType.SAVE_FAILED, "x")),
            ProcessingState.Cancelled,
        )
        val events = listOf<ExportEvent>(
            ExportEvent.Started(Stage.READING),
            ExportEvent.StageChanged(Stage.SAVING),
            ExportEvent.Succeeded(result),
            ExportEvent.Failed(ProcessingError(LumaErrorType.SAVE_FAILED, "x")),
            ExportEvent.Cancelled,
        )
        for (s in states) {
            for (e in events) {
                val next = ExportStateMachine.transition(s, e)
                assertTrue(next is ProcessingState)
            }
        }
    }
}
