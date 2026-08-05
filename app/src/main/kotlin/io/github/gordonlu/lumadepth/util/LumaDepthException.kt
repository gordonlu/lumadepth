// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.util

/**
 * 面向用户的异常：携带错误类型与用户可读的中文错误信息，
 * 技术细节保留在 cause 中。
 */
class LumaDepthException(
    val type: LumaErrorType,
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)
