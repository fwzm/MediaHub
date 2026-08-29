package com.mediahub.provider.jellyfin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Jellyfin 认证相关 DTO（Phase 1G-A）。独立实现，禁止 import provider:emby DTO
 * （ADR-039：JSON shape 相似也不建立 "Jellyfin == Emby" 依赖）。
 * 字段按真实 Jellyfin 协议命名；缺失字段不得导致解析失败（ignoreUnknownKeys）。
 */

/** POST /Users/AuthenticateByName 请求体。密码只出现在 body，绝不持久化（ADR-016）。 */
@Serializable
data class JellyfinLoginRequestDto(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val pw: String,
)

/** 认证响应（AuthenticationResult）：AccessToken + 服务器身份 + 用户。 */
@Serializable
data class JellyfinAuthenticationResultDto(
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("ServerInfo") val serverInfo: JellyfinServerInfoDto? = null,
    @SerialName("User") val user: JellyfinUserDto? = null,
) {
    /** 远程服务器身份：优先顶层 ServerId，回退 ServerInfo.Id（跨版本兼容）。 */
    val resolvedServerId: String?
        get() = serverId ?: serverInfo?.id
}

@Serializable
data class JellyfinServerInfoDto(
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class JellyfinUserDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
)
