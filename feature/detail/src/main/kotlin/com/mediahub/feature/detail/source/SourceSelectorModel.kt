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
 * 行标签（条目 144）：优先生效线路名称 `<endpointName>`；无线路名时回退服务器名。
 * 绝不再回退到地址或 serverId。同服务器多副本按发现序标注 `· 副本N`（N 从 1 起）；
 * 不同服务器线路名相同时追加 `· <serverName>` 消歧。副本是同服独立条目，
 * **禁止使用 MediaVersion 语义**（ADR-038：MediaVersion 是单源内多文件版本）。
 */
fun sourceRows(occurrences: List<CanonicalSourceOccurrence>): List<SourceRow> {
    val perServerCount = occurrences.groupBy { it.serverId }.mapValues { (_, v) -> v.size }
    val indexInServer = HashMap<String, Int>()
    val ambiguousLabels = occurrences
        .groupBy { it.baseLabel() }
        .filterValues { group -> group.map { it.serverId }.distinct().size > 1 }
        .keys
    return occurrences.map { occ ->
        val copies = perServerCount.getValue(occ.serverId)
        val idx = (indexInServer[occ.serverId] ?: 0) + 1
        indexInServer[occ.serverId] = idx
        val base = occ.baseLabel()
        val displayBase = if (base in ambiguousLabels) "$base · ${occ.serverName}" else base
        SourceRow(
            serverId = occ.serverId,
            itemId = occ.item.id,
            title = occ.item.title,
            label = if (copies > 1) "$displayBase · 副本$idx" else displayBase,
            isActive = occ.isActive,
        )
    }
}

/** 选择器标签的唯一来源：线路名优先，其次服务器名；不读取地址 / ID。 */
private fun CanonicalSourceOccurrence.baseLabel(): String =
    endpointName?.trim()?.takeIf { it.isNotEmpty() } ?: serverName
