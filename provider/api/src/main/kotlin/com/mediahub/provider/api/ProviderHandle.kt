package com.mediahub.provider.api

/**
 * 一个 Provider 实例及其可选能力的类型安全组合。
 * Descriptor 与实际能力在构造时校验，杜绝“声明不支持却被接口强迫实现”。
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
    init {
        val declared = provider.descriptor.capabilities
        requireCapability(declared, ProviderCapability.AUTH, auth)
        requireCapability(declared, ProviderCapability.LIBRARY, library)
        requireCapability(declared, ProviderCapability.BROWSE, browse)
        requireCapability(declared, ProviderCapability.PLAYBACK, playback)
        requireCapability(declared, ProviderCapability.SEARCH, search)
        requireCapability(declared, ProviderCapability.SUBTITLE, subtitle)
        requireCapability(declared, ProviderCapability.PROGRESS, progress)
    }

    val descriptor: ProviderDescriptor get() = provider.descriptor

    private fun requireCapability(
        declared: Set<ProviderCapability>,
        capability: ProviderCapability,
        implementation: Any?,
    ) {
        require((capability in declared) == (implementation != null)) {
            "${descriptor.providerId} 的 $capability 声明与实现不一致"
        }
    }
}
