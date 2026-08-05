package io.github.gordonlu.lumadepth.model

/** 处理阶段，UI 据此显示明确的进度文字。 */
enum class Stage {
    READING,
    ANALYZING,
    TONE_MAPPING,
    GAIN_MAP,
    ENCODING,
    VERIFYING,
    SAVING,
}
