package com.mediahub.feature.home

import com.mediahub.model.MediaServer
import com.mediahub.model.activeEndpoint

/**
 * 继续观看卡片的线路名：服务器当前 active endpoint 的显示名。
 * 未找到服务器、没有可用 endpoint 或名称为空白时返回 null（卡片不显示线路行）。
 */
fun continueWatchingLineName(servers: List<MediaServer>, serverId: String): String? =
    servers.firstOrNull { it.id == serverId }
        ?.endpoints
        ?.activeEndpoint()
        ?.name
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
