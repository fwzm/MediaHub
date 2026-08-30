package com.mediahub.provider.jellyfin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** /System/Info/Public 响应（连接测试 + 恢复防串服身份校验，公开端点）。键名按真实 Jellyfin 协议。 */
@Serializable
data class JellyfinSystemInfoPublic(
    @SerialName("Id") val id: String? = null,
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
)
