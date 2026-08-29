package com.mediahub.feature.detail.source

/**
 * Source selector 的纯投影（1F C2，ADR-038）：
 * UI 呈现条件与行标签的单一来源，脱离 Compose 可单测。
 */
data class SourceRow(
    val serverId: String,
    val itemId: String,
    val title: String,
    val label: String,
    val isActive: Boolean,
)

/** 多源 UI 仅 distinct serverId ≥ 2 时出现（继承 1E "N 个来源"口径）。 */
fun shouldShowSourceSelector(occurrences: List<CanonicalSourceOccurrence>): Boolean =
    occurrences.map { it.serverId }.distinct().size >= 2

/**
 * truncation 提示文案（评审 P2-2）：**独立于 selector 出现条件**——尚未发现
 * 第二个 server 时（恰是最可能漏源的场景）也必须提示，不得被
 * distinct serverId ≥ 2 的 gate 一起隐藏。null = 无需提示。
 */
fun truncationMessage(resolution: SourceResolution.Completed): String? =
    if (resolution.truncated) "来源发现未完成，列表可能不完整" else null

/**
 * 行标签：单副本服务器 = 服务器名；同服务器多副本按发现序标注
 * `<serverName> · 副本N`（N 从 1 起）。副本是同服独立条目，
 * **禁止使用 MediaVersion 语义**（ADR-038：MediaVersion 是单源内多文件版本）。
 */
fun sourceRows(occurrences: List<CanonicalSourceOccurrence>): List<SourceRow> {
    val perServerCount = occurrences.groupBy { it.serverId }.mapValues { (_, v) -> v.size }
    val indexInServer = HashMap<String, Int>()
    return occurrences.map { occ ->
        val copies = perServerCount.getValue(occ.serverId)
        val idx = (indexInServer[occ.serverId] ?: 0) + 1
        indexInServer[occ.serverId] = idx
        SourceRow(
            serverId = occ.serverId,
            itemId = occ.item.id,
            title = occ.item.title,
            label = if (copies > 1) "${occ.serverName} · 副本$idx" else occ.serverName,
            isActive = occ.isActive,
        )
    }
}
