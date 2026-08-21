package com.mediahub.model

/**
 * 用户添加的媒体源（服务器 / 云盘 / 本地）配置。
 * 注意：这里只保存"资源标识"（如服务器地址、账号名），
 * 绝不保存 Token / Cookie / 密码——那些进入加密存储（见 core:security）。
 *
 * Phase 1B-2.5：地址由单一 baseUrl 迁移为多条 [ServerEndpoint] 线路；
 * [baseUrl] 保留为计算属性（返回当前生效线路 URL），读取方无需改动。
 */
data class MediaServer(
    val id: String,
    val name: String,
    val type: ServerType,
    val username: String? = null,
    val note: String? = null,
    val icon: String? = null,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtEpochMs: Long,
    val lastConnectedAtEpochMs: Long? = null,
    val lastError: String? = null,
    val endpoints: List<ServerEndpoint> = emptyList(),
) {
    val displayName: String get() = name.ifBlank { type.label }

    /** 当前生效线路 URL；向后兼容旧 baseUrl 语义。 */
    val baseUrl: String get() = endpoints.activeEndpoint()?.url.orEmpty()

    /** 向后兼容旧签名（baseUrl → 单条主线路），供历史调用/测试使用。 */
    constructor(
        id: String,
        name: String,
        type: ServerType,
        baseUrl: String,
        username: String? = null,
        isDefault: Boolean = false,
        sortOrder: Int = 0,
        createdAtEpochMs: Long,
        lastConnectedAtEpochMs: Long? = null,
        lastError: String? = null,
    ) : this(
        id = id,
        name = name,
        type = type,
        username = username,
        isDefault = isDefault,
        sortOrder = sortOrder,
        createdAtEpochMs = createdAtEpochMs,
        lastConnectedAtEpochMs = lastConnectedAtEpochMs,
        lastError = lastError,
        endpoints = if (baseUrl.isBlank()) emptyList() else listOf(
            ServerEndpoint(
                id = id + "_ep0",
                serverId = id,
                name = "默认线路",
                url = baseUrl,
                isPrimary = true,
                enabled = true,
                sortOrder = 0,
            )
        ),
    )
}
