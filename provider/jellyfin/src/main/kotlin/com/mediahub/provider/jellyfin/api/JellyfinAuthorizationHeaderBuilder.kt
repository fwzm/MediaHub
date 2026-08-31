package com.mediahub.provider.jellyfin.api

import com.mediahub.core.common.ClientIdentity

/**
 * Jellyfin 客户端身份头构建器（Phase 1G-A，ADR-039 冻结 contract）：
 * 标准 `Authorization: MediaBrowser Client="...", Device="...", DeviceId="...", Version="..."[， Token="..."]`
 *
 * - 登录前不带 Token；登录后所有已认证请求 Token 只走此头，**绝不进 URL/query**（ADR-026 同款红线）；
 * - `X-Emby-Token` / `X-Emby-Authorization` / `X-MediaBrowser-*` 属 legacy 路径，
 *   Jellyfin 1G 一律不依赖（contract §2.2：不以 legacy header 为主协议）。
 * 集中一处构建，禁止 UI/ViewModel 自行拼 Header。
 */
class JellyfinAuthorizationHeaderBuilder(private val identity: ClientIdentity) {

    fun headerName(): String = HEADER_NAME

    fun build(token: String? = null): String = buildString {
        append("MediaBrowser ")
        append("Client=\"${identity.client}\", ")
        append("Device=\"${identity.device}\", ")
        append("DeviceId=\"${identity.deviceId}\", ")
        append("Version=\"${identity.version}\"")
        if (!token.isNullOrBlank()) append(", Token=\"$token\"")
    }

    private companion object {
        const val HEADER_NAME = "Authorization"
    }
}
