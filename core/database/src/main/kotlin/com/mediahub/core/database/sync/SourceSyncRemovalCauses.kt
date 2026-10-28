package com.mediahub.core.database.sync

/**
 * W6-98：删除链失败时，异常里那句人能读的话怎么进回执。
 *
 * 权威行为在 Windows [SourceSyncImportRound] 的写入器回调：写入器抛出的异常消息会被折进
 * Refused(异常 message)，结果页逐条显示。Android 侧此前只报步骤码（local-source-remove-failed:`step`），
 * 「卡在清凭据这一步」有了、「那一步为什么失败」没有；补上这一句之后两端口径一致。
 *
 * 为什么净化放在写码侧而不是界面侧：同文件里的 [SourceSyncRemovalCodes] 是步骤码的唯一权威，
 * 这句话与它同层，写码方（feature:server 的删除链）与翻码方（feature:settings 的结果页）都能用；
 * 两处都净化迟早会分叉。
 *
 * 净化规则（不编造）：
 * - 异常没有消息、或消息全是空白 ⇒ null —— 界面只显示步骤，不补「未知原因」这种废话；
 * - 换行、制表符等空白折叠成单个空格（底层异常常带多行文本，直接进单行文案会破版）；
 * - 超过 [MAXIMUM_MESSAGE_CHARS] 截断并加省略号（凭据柜等底层异常可能带整段 SQL 或 URL）。
 */
object SourceSyncRemovalCauses {
    /** 单行原因句的最大字符数（截断时另加一个省略号）。 */
    const val MAXIMUM_MESSAGE_CHARS: Int = 160

    /** 异常消息 → 单行原因句；没有可用的消息返回 null。 */
    fun describe(cause: Throwable?): String? {
        val raw = cause?.message?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val single = raw.replace(WHITESPACE, " ").trim()
        if (single.isEmpty()) return null
        return if (single.length <= MAXIMUM_MESSAGE_CHARS) {
            single
        } else {
            cutAt(single, MAXIMUM_MESSAGE_CHARS) + "…"
        }
    }

    /** 截断不切在代理对中间：切掉半个 emoji 会变成替换字符方块，比多留一个字符更糟。 */
    private fun cutAt(text: String, limit: Int): String {
        var end = limit.coerceAtMost(text.length)
        if (end in 1 until text.length && text[end - 1].isHighSurrogate()) end -= 1
        return text.substring(0, end)
    }

    private val WHITESPACE = Regex("\\s+")
}
