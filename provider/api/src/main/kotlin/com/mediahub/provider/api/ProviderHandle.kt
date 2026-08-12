package com.mediahub.provider.api

/**
 * 一个 Provider 实例及其可选能力的类型安全组合。
 * Descriptor 描述该 Provider 的计划能力；字段只暴露当前版本真正可用的运行时能力。
 */
data class ProviderHandle(
    val provider: MediaProvider,
    val auth: MediaAuthProvider? = null,
    val library: MediaLibraryProvider? = null,
    val browse: MediaBrowseProvider? = null,
    val playback: MediaPlaybackProvider? = null,
    val search: MediaSearchProvider? = null,
    val subtitle: MediaSubtitleProvider? = null,
    val progress: MediaProgressProvider? = null,
) {
    /** 由实际装配字段推导，业务层以此为准，不能把计划能力当作已经实现。 */
    val runtimeCapabilities: Set<ProviderCapability> = buildSet {
        if (auth != null) add(ProviderCapability.AUTH)
        if (library != null) add(ProviderCapability.LIBRARY)
        if (browse != null) add(ProviderCapability.BROWSE)
        if (playback != null) add(ProviderCapability.PLAYBACK)
        if (search != null) add(ProviderCapability.SEARCH)
        if (subtitle != null) add(ProviderCapability.SUBTITLE)
        if (progress != null) add(ProviderCapability.PROGRESS)
    }

    init {
        require(provider.descriptor.capabilities.containsAll(runtimeCapabilities)) {
            "${provider.descriptor.providerId} 的运行时能力必须先在 Descriptor 中声明：" +
                (runtimeCapabilities - provider.descriptor.capabilities).joinToString()
        }
    }

    val descriptor: ProviderDescriptor get() = provider.descriptor
    val hasAnyCapability: Boolean get() = runtimeCapabilities.isNotEmpty()
}
