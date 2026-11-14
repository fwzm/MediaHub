package com.mediahub.model

/**
 * 首页组件卡片样式（条目 3）。
 *
 * 每个首页组件独立保存自己的展示样式，互不影响：
 * - [CARD]：大卡片（封面 + 文案，单列铺满）
 * - [COMPACT]：紧凑双列卡片
 * - [ROW]：横向单行卡片（左侧封面，右侧文案）
 */
enum class HomeSectionStyle { CARD, COMPACT, ROW }

/** 首页组件的稳定标识（持久化键，不得随 UI 文案变化）。 */
object HomeSectionIds {
    const val CONTINUE_WATCHING = "continue_watching"
    const val SERVERS = "servers"
}

/**
 * 首页样式偏好编码：`id=STYLE|id=STYLE`，按键排序保证同输入同输出。
 * 未知样式名与空键在解码时丢弃，坏数据不得影响其余组件。
 */
fun encodeHomeSectionStyles(styles: Map<String, HomeSectionStyle>): String =
    styles.entries
        .filter { it.key.isNotBlank() }
        .sortedBy { it.key }
        .joinToString("|") { "${it.key}=${it.value.name}" }

fun decodeHomeSectionStyles(raw: String?): Map<String, HomeSectionStyle> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split('|').mapNotNull { part ->
        val index = part.lastIndexOf('=')
        if (index <= 0) return@mapNotNull null
        val key = part.substring(0, index).trim()
        val style = HomeSectionStyle.entries.firstOrNull { it.name == part.substring(index + 1).trim() }
        if (key.isEmpty() || style == null) null else key to style
    }.toMap()
}

/**
 * 资源标签文件夹展示顺序（条目 4）：保存稳定的文件夹 id 序列。
 * 解码去重且保序；未知/已删除的 id 由消费方过滤，不在这里猜测。
 */
fun encodeLibraryOrder(ids: List<String>): String =
    ids.filter { it.isNotBlank() }.distinct().joinToString("|")

fun decodeLibraryOrder(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split('|').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/** 按用户偏好顺序稳定排序：偏好中出现的在前，其余保持原始相对顺序。 */
fun <T> applyLibraryOrder(items: List<T>, order: List<String>, idOf: (T) -> String): List<T> {
    if (order.isEmpty()) return items
    val rank = order.withIndex().associate { (index, id) -> id to index }
    return items.sortedBy { rank[idOf(it)] ?: Int.MAX_VALUE }
}
