package com.mediahub.provider.emby.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** /Users/AuthenticateByName 请求体（官方要求 JSON：Username / Pw）。 */
@Serializable
data class EmbyLoginRequestDto(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val pw: String,
)

/** /Users/AuthenticateByName 成功响应（AuthenticationResult）。 */
@Serializable
data class EmbyAuthenticationResultDto(
    @SerialName("User") val user: EmbyUserDto? = null,
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
)

/** Emby 用户（Phase 1A 只取认证需要的最小字段，见 ADR-026）。 */
@Serializable
data class EmbyUserDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
)
