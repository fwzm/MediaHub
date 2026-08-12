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
 * - [declaredCapabilities]：该类型**最终计划支持**的能力集合（展示/路由用）；
 *   **当前实例实际可用**的能力以 [ProviderHandle] 的字段（runtimeCapabilities）为准（ADR-022）。
 */
data class ProviderDescriptor(
    val id: String,
    val serverType: ServerType,
    val displayName: String,
    val category: ProviderCategory,
    val declaredCapabilities: Set<ProviderCapability>,
    val authMethod: AuthMethod,
    val status: ProviderStatus,
    val description: String = "",
)
