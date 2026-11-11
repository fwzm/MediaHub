package com.mediahub.provider.api

/**
 * 登录凭据（仅内存使用，绝不落库、绝不进日志）。
 * 各数据源在 [com.mediahub.provider.api.MediaAuthProvider.authenticate] 中按需消费。
 */
sealed interface Credentials {
    /** Emby / Jellyfin / 部分 NAS 的用户名密码登录 */
    data class UsernamePassword(val username: String, val password: String) : Credentials {
        override fun toString(): String = "UsernamePassword(REDACTED)"
    }

    /** WebDAV / SMB 用户名密码 */
    data class WebDav(val username: String, val password: String) : Credentials {
        override fun toString(): String = "WebDav(REDACTED)"
    }

    /** 已持有 Token 的登录（如 Plex） */
    data class BearerToken(val token: String) : Credentials {
        override fun toString(): String = "BearerToken(REDACTED)"
    }

    /** API Key（如云盘开放平台） */
    data class ApiKey(val apiKey: String) : Credentials {
        override fun toString(): String = "ApiKey(REDACTED)"
    }
}
