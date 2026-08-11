package com.mediahub.core.logging

/**
 * 内存日志环形缓冲，供"诊断报告导出"使用。
 * 内容在写入前必须已脱敏（由 Logger 实现保证）。
 */
class LogBuffer(private val capacity: Int = 512) {

    private val buffer = ArrayDeque<String>()

    @Synchronized
    fun append(line: String) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(line)
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()
}

/** 将日志写入 [LogBuffer] 的 Logger 实现。 */
class MemoryLogger(private val buffer: LogBuffer) : Logger {

    private fun format(level: Char, tag: LogTag, message: String): String =
        "[$level][${tag.name}] ${Redactor.redact(message)}"

    override fun d(tag: LogTag, message: String) = buffer.append(format('D', tag, message))
    override fun i(tag: LogTag, message: String) = buffer.append(format('I', tag, message))
    override fun w(tag: LogTag, message: String, throwable: Throwable?) =
        buffer.append(format('W', tag, message) + throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty())

    override fun e(tag: LogTag, message: String, throwable: Throwable?) =
        buffer.append(format('E', tag, message) + throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty())
}
