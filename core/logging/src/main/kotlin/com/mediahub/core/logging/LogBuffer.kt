package com.mediahub.core.logging

/**
 * 内存日志环形缓冲，供"诊断报告导出"使用。
 * 内容在写入前必须已脱敏（由 Logger 实现保证）。
 *
 * 双重上界：条数（[capacity]）与字符数（[maxChars]）。
 * 仅按条数淘汰时，单条超长异常堆栈（[Redactor.MAX_OUTPUT_LENGTH] 允许单条 20000 字符）
 * 即可占满整个缓冲，诊断导出会因此撑爆内存；此处按"总字符预算 + 逐条截断"共同兜底：
 * - 单条超过预算时截断并显式追加 [TRUNCATION_SUFFIX]（保留开头，异常首行最有诊断价值）；
 * - 追加后若超预算，按最旧优先淘汰，直到重新落入预算。
 */
class LogBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) {

    private val buffer = ArrayDeque<String>()
    private var totalChars = 0

    @Synchronized
    fun append(line: String) {
        val entry = clip(line)
        while (buffer.isNotEmpty() && (buffer.size >= capacity || totalChars + entry.length > maxChars)) {
            totalChars -= buffer.removeFirst().length
        }
        buffer.addLast(entry)
        totalChars += entry.length
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun clear() {
        buffer.clear()
        totalChars = 0
    }

    /** 当前占用字符数（含截断后缀），供诊断与测试断言。 */
    @Synchronized
    fun chars(): Int = totalChars

    private fun clip(line: String): String {
        if (line.length <= maxChars) return line
        if (maxChars <= TRUNCATION_SUFFIX.length) return TRUNCATION_SUFFIX.take(maxChars)
        return line.take(maxChars - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX
    }

    companion object {
        const val DEFAULT_CAPACITY = 512
        const val DEFAULT_MAX_CHARS = 64_000
        const val TRUNCATION_SUFFIX = "…[truncated]"
    }
}

/** 将日志写入 [LogBuffer] 的 Logger 实现。 */
class MemoryLogger(private val buffer: LogBuffer) : Logger {

    private fun format(level: Char, tag: LogTag, message: String): String =
        "[$level][${tag.name}] ${Redactor.redact(message)}"

    override fun d(tag: LogTag, message: String) = buffer.append(format('D', tag, message))
    override fun i(tag: LogTag, message: String) = buffer.append(format('I', tag, message))
    override fun w(tag: LogTag, message: String, throwable: Throwable?) =
        buffer.append(format('W', tag, message) + throwable?.let { " | ${it.javaClass.simpleName}: ${Redactor.redact(it.message)}" }.orEmpty())

    override fun e(tag: LogTag, message: String, throwable: Throwable?) =
        buffer.append(format('E', tag, message) + throwable?.let { " | ${it.javaClass.simpleName}: ${Redactor.redact(it.message)}" }.orEmpty())
}
