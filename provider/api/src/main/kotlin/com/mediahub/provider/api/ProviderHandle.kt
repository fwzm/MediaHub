package com.mediahub.provider.api

/**
 * Provider 能力组合句柄（类型安全，见 ADR-014 / ADR-022）。
 *
 * 硬性规则（ADR-022）：**字段只暴露"当前版本真正实现完成"的能力**。
 * 未实现（或仅占位）的能力一律不放入 Handle——feature 层永远不用通过异常
 * 发现"其实还没实现"。
 *
 * - 一致性约束：字段非空 ⇔ [runtimeCapabilities] 包含对应能力；
 *   且 runtimeCapabilities ⊆ descriptor.declaredCapabilities（计划 ≥ 运行时）。
 * - 上层（UI / UseCase / ViewModel）通过可空字段判断并使用能力，
 *   无需到处 `as?` 强转，也无需依赖 `type == EMBY` 之类分支。
 */
data class ProviderHandle(
    val provider: MediaProvider,
    val auth: MediaAuthProvider? = null,
    val library: MediaLibraryProvider? = null,
    val detail: MediaDetailProvider? = null,
    val browse: MediaBrowseProvider? = null,
    val playback: MediaPlaybackProvider? = null,
    val search: MediaSearchProvider? = null,
    val identityLookup: MediaIdentityLookupProvider? = null,
    val query: MediaQueryLibraryProvider? = null,
    val subtitle: MediaSubtitleProvider? = null,
    val progress: MediaProgressProvider? = null,
) {
    val serverId: String get() = provider.serverId
    val type: com.mediahub.model.ServerType get() = provider.type

    /** 当前运行时实际可用能力（由字段推导，ADR-022）。 */
    val runtimeCapabilities: Set<ProviderCapability> = buildSet {
        if (auth != null) add(ProviderCapability.AUTH)
        if (library != null) add(ProviderCapability.LIBRARY)
        if (detail != null) add(ProviderCapability.DETAIL)
        if (browse != null) add(ProviderCapability.BROWSE)
        if (playback != null) add(ProviderCapability.PLAYBACK)
        if (search != null) add(ProviderCapability.SEARCH)
        if (identityLookup != null) add(ProviderCapability.IDENTITY_LOOKUP)
        if (query != null) add(ProviderCapability.QUERY)
        if (subtitle != null) add(ProviderCapability.SUBTITLE)
        if (progress != null) add(ProviderCapability.PROGRESS)
    }

    /** 是否有任何实际可用的能力（骨架阶段为 false 时 UI 显示"未接入"）。 */
    val hasAnyCapability: Boolean
        get() = auth != null || library != null || detail != null || browse != null ||
            playback != null || search != null || identityLookup != null || query != null ||
            subtitle != null || progress != null
}
