package com.mediahub.provider.base

import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderStatus

/**
 * 尚未有代码模块的产品路线占位。已注册工厂会按 providerId 自动覆盖这里的描述，
 * 因而新增真实 Provider 无需修改 UI。
 */
internal object PlannedProviderCatalog {
    val descriptors = listOf(
        planned("plex", "Plex", "媒体服务器", ProviderCategory.MEDIA_SERVER, AuthMethod.BEARER_TOKEN, 40),
        planned("fnnas", "FnNas", "飞牛 NAS", ProviderCategory.MEDIA_SERVER, AuthMethod.USERNAME_PASSWORD, 50),
        planned("smb", "SMB", "局域网文件共享", ProviderCategory.NETWORK_STORAGE, AuthMethod.USERNAME_PASSWORD, 60),
        planned("aliyundrive", "阿里云盘", "云盘", ProviderCategory.CLOUD_DRIVE, AuthMethod.OAUTH2, 100),
        planned("baidudrive", "百度网盘", "云盘", ProviderCategory.CLOUD_DRIVE, AuthMethod.OAUTH2, 110),
        planned("quarkdrive", "夸克网盘", "云盘", ProviderCategory.CLOUD_DRIVE, AuthMethod.COOKIE_SESSION, 120),
        planned("chinamobilecloud", "中国移动云盘", "云盘", ProviderCategory.CLOUD_DRIVE, AuthMethod.OAUTH2, 130),
        planned("tianyicloud", "中国电信天翼云盘", "云盘", ProviderCategory.CLOUD_DRIVE, AuthMethod.OAUTH2, 140),
    )

    private fun planned(
        id: String,
        name: String,
        description: String,
        category: ProviderCategory,
        authMethod: AuthMethod,
        sortOrder: Int,
    ) = ProviderDescriptor(
        providerId = id,
        displayName = name,
        description = description,
        category = category,
        capabilities = setOf(ProviderCapability.BROWSE, ProviderCapability.PLAYBACK),
        authMethod = authMethod,
        status = ProviderStatus.COMING_SOON,
        sortOrder = sortOrder,
    )
}
