package com.mediahub.provider.api

import com.mediahub.model.ServerType

/** 数据源大类。 */
enum class ProviderCategory { MEDIA_SERVER, CLOUD_STORAGE, CLOUD_DRIVE }

/** 认证方式（决定 UI 表单与凭据生命周期策略，见 ADR-016）。 */
enum class AuthMethod {
    NONE,
    USERNAME_PASSWORD,
    BASIC,
    BEARER_TOKEN,
    API_KEY,
    OAUTH2,
    DEVICE_CODE,
    COOKIE_SESSION,
    QR,
}

/** 数据源成熟度。 */
enum class ProviderStatus { STABLE, BETA, EXPERIMENTAL }

/**
 * 数据源类型描述（由 Factory 自己声明，见 ADR-015）。
 *
 * - [id]：稳定标识（"emby"、"webdav"…），未来持久化迁移的键（见 ADR-015 迁移路径）。
 * - [capabilities]：该类型**最终支持**的能力集合（供展示/路由）；
 *   当前实例是否可用，以 [ProviderHandle] 的可空字段为准（运行时权威）。
 */
data class ProviderDescriptor(
    val id: String,
    val serverType: ServerType,
    val displayName: String,
    val category: ProviderCategory,
    val capabilities: Set<ProviderCapability>,
    val authMethod: AuthMethod,
    val status: ProviderStatus,
    val description: String = "",
)
