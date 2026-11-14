package com.mediahub.feature.library

/**
 * SAF 目录栈节点（收尾项 2）：document uri + 展示名。
 */
data class SafPathNode(val uri: String, val name: String)

/**
 * 文件浏览器的独立 SAF 目录栈。
 *
 * - 根节点（用户通过系统目录选择器授权的 tree uri）永远保留，`pop` 不会把它弹掉；
 * - 栈可编码进 SavedStateHandle，进程重建/旋转后回到同一路径。
 */
object SafPath {
    private const val NODE_SEPARATOR = '\n'
    private const val FIELD_SEPARATOR = '\t'
    private const val EMPTY_LABEL = "未选择目录"

    fun push(stack: List<SafPathNode>, node: SafPathNode): List<SafPathNode> = stack + node

    /** 弹出一层；已在根目录时保持不变。 */
    fun pop(stack: List<SafPathNode>): List<SafPathNode> =
        if (stack.size <= 1) stack else stack.dropLast(1)

    fun current(stack: List<SafPathNode>): SafPathNode? = stack.lastOrNull()

    /** 面包屑：`根 / 子目录 / 孙目录`。 */
    fun breadcrumb(stack: List<SafPathNode>): String =
        if (stack.isEmpty()) EMPTY_LABEL else stack.joinToString(" / ") { it.name }

    fun encode(stack: List<SafPathNode>): String =
        stack.joinToString(NODE_SEPARATOR.toString()) {
            "${sanitizeSafField(it.uri)}$FIELD_SEPARATOR${sanitizeSafField(it.name)}"
        }

    fun decode(raw: String?): List<SafPathNode> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val index = line.indexOf(FIELD_SEPARATOR)
            if (index <= 0) return@mapNotNull null
            val uri = line.substring(0, index).trim()
            val name = line.substring(index + 1).trim()
            if (uri.isEmpty() || name.isEmpty()) null else SafPathNode(uri = uri, name = name)
        }.toList()
    }
}

private fun sanitizeSafField(value: String): String =
    value.replace('\n', ' ').replace('\t', ' ')
