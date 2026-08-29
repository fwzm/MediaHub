package com.mediahub.provider.jellyfin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Jellyfin 认证相关 DTO（Phase 1G-A）。独立实现，禁止 import provider:emby DTO
 * （ADR-039：JSON shape 相似也不建立 "Jellyfin == Emby" 依赖）。
 * 字段按真实 Jellyfin v10.9.0 协议命名（协议证据先行）；缺失字段不得导致解析失败
 * （ignoreUnknownKeys）。
 */

/** POST /Users/AuthenticateByName 请求体。密码只出现在 body，绝不持久化（ADR-016）。 */
@Serializable
data class JellyfinLoginRequestDto(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val pw: String,
)

/**
 * 认证响应（AuthenticationResult）。正式字段 = User / AccessToken / **顶层 ServerId**
 * （v10.9.0 源模型核对）；不得凭猜测添加未证明的 fallback 字段（协议证据先行，
 * ADR-039 review 修正：删除无证据的 ServerInfo.Id 回退）。
 */
@Serializable
data class JellyfinAuthenticationResultDto(
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("User") val user: JellyfinUserDto? = null,
)

@Serializable
data class JellyfinUserDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
)
