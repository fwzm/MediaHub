package com.mediahub.provider.api

/** Provider 的稳定、自描述元数据。UI 只消费该模型，不维护数据源类型表。 */
data class ProviderDescriptor(
    val providerId: String,
    val displayName: String,
    val description: String,
    val category: ProviderCategory,
    val capabilities: Set<ProviderCapability>,
    val authMethod: AuthMethod,
    val status: ProviderStatus,
    val sortOrder: Int = 100,
) {
    init {
        require(PROVIDER_ID_PATTERN.matches(providerId)) {
            "providerId 只能包含小写字母、数字、点、下划线和连字符：$providerId"
        }
    }

    val isSelectable: Boolean get() = status != ProviderStatus.COMING_SOON

    private companion object {
        val PROVIDER_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")
    }
}

enum class ProviderCategory {
    MEDIA_SERVER,
    NETWORK_STORAGE,
    LOCAL_STORAGE,
    CLOUD_DRIVE,
}

enum class AuthMethod {
    NONE,
    USERNAME_PASSWORD,
    BASIC,
    BEARER_TOKEN,
    API_KEY,
    OAUTH2,
    DEVICE_CODE,
    COOKIE_SESSION,
    QR_LOGIN,
}

enum class ProviderStatus {
    AVAILABLE,
    EXPERIMENTAL,
    COMING_SOON,
}
