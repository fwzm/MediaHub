package com.mediahub.core.logging

import kotlin.coroutines.cancellation.CancellationException

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

/**
 * 将多条日志汇聚为一条（logcat + 内存缓冲）。
 *
 * 故障隔离（日志链红线）：单个 sink 抛异常不得让其余 sink 丢掉这条日志，
 * 也不得把异常抛回业务调用方——日志永远不是业务失败的原因。
 * 唯一例外是 [kotlin.coroutines.cancellation.CancellationException]：取消必须原样透传，
 * 否则被取消的协程会看起来像"日志写完了"继续往下跑。
 */
class CompositeLogger(private val delegates: List<Logger>) : Logger {
    override fun d(tag: LogTag, message: String) = dispatch { it.d(tag, message) }
    override fun i(tag: LogTag, message: String) = dispatch { it.i(tag, message) }
    override fun w(tag: LogTag, message: String, throwable: Throwable?) = dispatch { it.w(tag, message, throwable) }
    override fun e(tag: LogTag, message: String, throwable: Throwable?) = dispatch { it.e(tag, message, throwable) }

    private inline fun dispatch(write: (Logger) -> Unit) {
        delegates.forEach { delegate ->
            try {
                write(delegate)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // 单个 sink 故障不得中断其余 sink，也不得冒泡到业务调用方。
            }
        }
    }
}

/** 测试 / JVM 环境用的简单实现。 */
class StdoutLogger : Logger {
    override fun d(tag: LogTag, message: String) = println("[D][${tag.name}] ${Redactor.redact(message)}")
    override fun i(tag: LogTag, message: String) = println("[I][${tag.name}] ${Redactor.redact(message)}")
    override fun w(tag: LogTag, message: String, throwable: Throwable?) = println("[W][${tag.name}] ${Redactor.redact(message)}")
    override fun e(tag: LogTag, message: String, throwable: Throwable?) = println("[E][${tag.name}] ${Redactor.redact(message)}")
}
