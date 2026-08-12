package com.mediahub.provider.emby.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** /System/Info/Public 响应（连接测试用，公开端点）。键名按真实 Emby 协议（Id/ServerName/Version）。 */
@Serializable
data class SystemInfoPublic(
    @SerialName("Id") val id: String? = null,
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
)
