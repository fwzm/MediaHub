package com.mediahub.core.logging

/**
 * 统一日志接口。所有实现必须对内容调用 [Redactor.redact]，
 * 保证 Token / Cookie / 密码 永远不会进入日志（含 logcat 与诊断导出）。
 */
interface Logger {
    fun d(tag: LogTag, message: String)
    fun i(tag: LogTag, message: String)
    fun w(tag: LogTag, message: String, throwable: Throwable? = null)
    fun e(tag: LogTag, message: String, throwable: Throwable? = null)
}

/** 将多条日志汇聚为一条（logcat + 内存缓冲）。 */
class CompositeLogger(private val delegates: List<Logger>) : Logger {
    override fun d(tag: LogTag, message: String) = delegates.forEach { it.d(tag, message) }
    override fun i(tag: LogTag, message: String) = delegates.forEach { it.i(tag, message) }
    override fun w(tag: LogTag, message: String, throwable: Throwable?) = delegates.forEach { it.w(tag, message, throwable) }
    override fun e(tag: LogTag, message: String, throwable: Throwable?) = delegates.forEach { it.e(tag, message, throwable) }
}

/** 测试 / JVM 环境用的简单实现。 */
class StdoutLogger : Logger {
    override fun d(tag: LogTag, message: String) = println("[D][${tag.name}] ${Redactor.redact(message)}")
    override fun i(tag: LogTag, message: String) = println("[I][${tag.name}] ${Redactor.redact(message)}")
    override fun w(tag: LogTag, message: String, throwable: Throwable?) = println("[W][${tag.name}] ${Redactor.redact(message)}")
    override fun e(tag: LogTag, message: String, throwable: Throwable?) = println("[E][${tag.name}] ${Redactor.redact(message)}")
}
