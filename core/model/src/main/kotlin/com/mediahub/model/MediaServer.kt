package com.mediahub.model

/**
 * 用户添加的媒体源（服务器 / 云盘 / 本地）配置。
 * 注意：这里只保存"资源标识"（如服务器地址、账号名），
 * 绝不保存 Token / Cookie / 密码——那些进入加密存储（见 core:security）。
 */
data class MediaServer(
    val id: String,
    val name: String,
    /** 稳定、开放的 Provider 标识，例如 emby / jellyfin / webdav。 */
    val providerId: String,
    val baseUrl: String,
    val username: String? = null,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtEpochMs: Long,
    val lastConnectedAtEpochMs: Long? = null,
    val lastError: String? = null,
) {
    val displayName: String get() = name.ifBlank { providerId }
}
