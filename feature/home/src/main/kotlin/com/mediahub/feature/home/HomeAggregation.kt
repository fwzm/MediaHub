package com.mediahub.feature.home

import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.activeEndpoint

/** 首页各分区共用的空态文案（条目 148）。 */
const val NO_PLAYABLE_RESOURCES = "暂无可播放资源"

/** 首页数据源选项：[label] 只允许线路名称或来源名称，绝不展示地址 / ID（条目 144）。 */
data class HomeSourceOption(val serverId: String, val label: String)

/** 线路名称优先，其次来源名称；不读取 URL，也不回退到 serverId。 */
fun MediaServer.homeSourceLabel(): String =
    endpoints.activeEndpoint()?.name?.trim()?.takeIf { it.isNotEmpty() } ?: displayName

fun homeSourceOptions(servers: List<MediaServer>): List<HomeSourceOption> {
    val labels = servers.associate { it.id to it.homeSourceLabel() }
    // 不同来源线路同名时追加来源名称消歧；仍展示线路/名称，不暴露地址或 ID。
    val ambiguous = labels.values.groupBy { it }.filterValues { it.size > 1 }.keys
    return servers.map { server ->
        val base = labels.getValue(server.id)
        HomeSourceOption(
            serverId = server.id,
            label = if (base in ambiguous) "$base · ${server.displayName}" else base,
        )
    }
}

/** 默认首页：复用 [MediaServer.isDefault]；没有显式默认时取第一个来源。 */
fun defaultHomeSourceId(servers: List<MediaServer>): String? =
    servers.firstOrNull { it.isDefault }?.id ?: servers.firstOrNull()?.id

/** 继续观看按首页所选来源过滤；[serverId] 为 null 时保留聚合结果。 */
fun scopedContinueWatching(
    items: List<PlaybackProgress>,
    serverId: String?,
): List<PlaybackProgress> =
    if (serverId == null) items else items.filter { it.serverId == serverId }

/** 资源聚合空态（条目 148）：已接入来源但没有任何可播放记录。 */
fun shouldShowNoPlayableResources(
    servers: List<MediaServer>,
    items: List<PlaybackProgress>,
): Boolean = servers.isNotEmpty() && items.isEmpty()
