package com.mediahub.model

/** 与某个 [MediaServer] 关联的账号信息（非敏感）。敏感凭据走 core:security。 */
data class MediaAccount(
    val id: String,
    val serverId: String,
    val userId: String? = null,
    val displayName: String,
    val authState: AuthState,
    val authenticatedAtEpochMs: Long? = null,
)

/** 已认证用户的基础信息（来自服务端）。 */
data class MediaUser(
    val serverId: String,
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isAdministrator: Boolean = false,
)
