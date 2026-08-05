package io.github.gordonlu.lumadepth.util

/**
 * 面向用户的异常：携带用户可读的中文错误信息，技术细节保留在 cause 中。
 */
class LumaDepthException(val userMessage: String, cause: Throwable? = null) :
    Exception(userMessage, cause)
