// Copyright (c) 2026 Gordon Lu
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.github.gordonlu.lumadepth.util

/**
 * 面向用户的错误类型。UI 据此显示可理解的中文错误信息，
 * 不得把所有异常统一显示为"未知错误"。
 */
enum class LumaErrorType {
    /** 输入 URI 无效或文件在选择后被删除。 */
    INPUT_UNAVAILABLE,

    /** 不支持的图片格式。 */
    UNSUPPORTED_IMAGE,

    /** 图片解码失败（损坏、EXIF 异常等）。 */
    DECODE_FAILED,

    /** 图片过大，超过安全处理上限。 */
    IMAGE_TOO_LARGE,

    /** 设备可用内存不足。 */
    INSUFFICIENT_MEMORY,

    /** 像素处理阶段失败。 */
    PROCESSING_FAILED,

    /** Ultra HDR JPEG 编码失败。 */
    ENCODE_FAILED,

    /** 输出文件验证失败（无 Gain Map / 元数据非法）。 */
    GAIN_MAP_VALIDATION_FAILED,

    /** 存储空间不足。 */
    STORAGE_FULL,

    /** MediaStore 保存失败。 */
    SAVE_FAILED,

    /** 用户取消任务（正常状态，不是错误）。 */
    CANCELLED,
}
