package com.mediahub.core.logging

import android.util.Log

/** Android logcat 实现。 */
class LogcatLogger : Logger {
    override fun d(tag: LogTag, message: String) {
        Log.d(TAG_PREFIX + tag.name, Redactor.redact(message))
    }

    override fun i(tag: LogTag, message: String) {
        Log.i(TAG_PREFIX + tag.name, Redactor.redact(message))
    }

    override fun w(tag: LogTag, message: String, throwable: Throwable?) {
        Log.w(TAG_PREFIX + tag.name, Redactor.redact(message) + safeLogText(throwable))
    }

    override fun e(tag: LogTag, message: String, throwable: Throwable?) {
        Log.e(TAG_PREFIX + tag.name, Redactor.redact(message) + safeLogText(throwable))
    }

    private companion object {
        const val TAG_PREFIX = "MediaHub/"
    }
}

/**
 * throwable 的日志安全文本（A2-4 第 3 项）：脱敏后的 stackTraceToString、
 * 截断到 ~3000 字符。原始 throwable 不再交给 Log 的 throwable overload——
 * 系统打印的 "Caused by: <message>" 链路不经 [Redactor]，会带出敏感文本。
 */
internal fun safeLogText(throwable: Throwable?): String {
    throwable ?: return ""
    val sanitized = Redactor.redact(throwable.stackTraceToString())
    return " | " + if (sanitized.length > MAX_THROWABLE_LOG_CHARS) {
        sanitized.take(MAX_THROWABLE_LOG_CHARS) + "…[truncated]"
    } else {
        sanitized
    }
}

internal const val MAX_THROWABLE_LOG_CHARS = 3_000
