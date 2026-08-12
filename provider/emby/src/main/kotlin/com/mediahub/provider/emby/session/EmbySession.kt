package com.mediahub.provider.emby.session

import kotlinx.serialization.Serializable

/**
 * Emby 会话元数据（不敏感，非 Token）。
 *
 * 关键区分（ADR-026）：
 * - [localServerId]：MediaHub 本地 MediaServer.id（TokenStore 的键）。
 * - [remoteServerId]：Emby AuthenticationResult 返回的远程服务器 id，
 *   恢复会话时用于确认当前服务器身份未变（防止 URL 改到别的服务器后错发旧 Token）。
 */
@Serializable
data class EmbySession(
    val localServerId: String,
    val remoteServerId: String,
    val userId: String,
    val userName: String,
)
