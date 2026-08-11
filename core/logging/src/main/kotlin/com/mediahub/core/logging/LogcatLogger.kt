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
        Log.w(TAG_PREFIX + tag.name, Redactor.redact(message), throwable)
    }

    override fun e(tag: LogTag, message: String, throwable: Throwable?) {
        Log.e(TAG_PREFIX + tag.name, Redactor.redact(message), throwable)
    }

    private companion object {
        const val TAG_PREFIX = "MediaHub/"
    }
}
