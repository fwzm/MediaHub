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
        Log.w(TAG_PREFIX + tag.name, safeLogText(message, throwable))
    }

    override fun e(tag: LogTag, message: String, throwable: Throwable?) {
        Log.e(TAG_PREFIX + tag.name, safeLogText(message, throwable))
    }

    /** Android 的 throwable overload 会绕过 message redaction；先转成文本再整体脱敏。 */
    private fun safeLogText(message: String, throwable: Throwable?): String = buildString {
        append(Redactor.redact(message))
        throwable?.let {
            append('\n')
            append(Redactor.redact(it.stackTraceToString()))
        }
    }

    private companion object {
        const val TAG_PREFIX = "MediaHub/"
    }
}
