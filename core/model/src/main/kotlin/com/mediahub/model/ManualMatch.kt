package com.mediahub.model

/**
 * 手动匹配结果（条目 129 收尾）：用户为某个条目指定的「正确条目」。
 *
 * 只记录展示与跳转所需的最小字段，持久化在 DataStore（不新建数据库表）。
 */
data class ManualMatchRecord(
    /** 匹配到的目标条目 id（同一媒体源内唯一）。 */
    val targetId: String,
    /** 目标条目标题，详情页直接展示，避免二次请求。 */
    val title: String,
    /** 目标条目海报，可为空。 */
    val posterUrl: String? = null,
)

/** 匹配键：同一 itemId 在不同媒体源下互不覆盖。 */
fun manualMatchKey(serverId: String, itemId: String): String = "$serverId::$itemId"

private const val MATCH_RECORD_SEPARATOR = '\n'
private const val MATCH_FIELD_SEPARATOR = '\t'

/** 编码：`key\ttargetId\ttitle\tposter`，按 key 排序，空记录丢弃。 */
fun encodeManualMatches(matches: Map<String, ManualMatchRecord>): String =
    matches.entries
        .filter { it.key.isNotBlank() && it.value.targetId.isNotBlank() }
        .sortedBy { it.key }
        .joinToString(MATCH_RECORD_SEPARATOR.toString()) { (key, record) ->
            listOf(
                sanitizeMatchField(key),
                sanitizeMatchField(record.targetId),
                sanitizeMatchField(record.title),
                sanitizeMatchField(record.posterUrl.orEmpty()),
            ).joinToString(MATCH_FIELD_SEPARATOR.toString())
        }

/** 解码：坏行直接丢弃，不因一条脏数据丢掉整个映射。 */
fun decodeManualMatches(raw: String?): Map<String, ManualMatchRecord> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val parts = line.split(MATCH_FIELD_SEPARATOR)
        if (parts.size < 3) return@mapNotNull null
        val key = parts[0].trim()
        val targetId = parts[1].trim()
        if (key.isEmpty() || targetId.isEmpty()) return@mapNotNull null
        val poster = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
        key to ManualMatchRecord(targetId = targetId, title = parts[2].trim(), posterUrl = poster)
    }.toMap()
}

private fun sanitizeMatchField(value: String): String =
    value.replace(MATCH_RECORD_SEPARATOR, ' ').replace(MATCH_FIELD_SEPARATOR, ' ')
