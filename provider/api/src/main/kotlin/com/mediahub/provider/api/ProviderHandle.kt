package com.mediahub.provider.api

/**
 * Provider 能力组合句柄（类型安全，见 ADR-014）。
 *
 * 上层（UI / UseCase / ViewModel）通过可空字段判断并使用能力，
 * 无需到处 `as?` 强转，也无需依赖 `type == EMBY` 之类分支。
 *
 * 一致性约束：字段非空 ⇔ descriptor.capabilities 声明该能力；
 * 由 [MediaProviderFactory.create] 负责保证。
 */
data class ProviderHandle(
    val provider: MediaProvider,
    val auth: MediaAuthProvider? = null,
    val library: MediaLibraryProvider? = null,
    val detail: MediaDetailProvider? = null,
    val browse: MediaBrowseProvider? = null,
    val playback: MediaPlaybackProvider? = null,
    val search: MediaSearchProvider? = null,
    val subtitle: MediaSubtitleProvider? = null,
    val progress: MediaProgressProvider? = null,
) {
    val serverId: String get() = provider.serverId
    val type: com.mediahub.model.ServerType get() = provider.type

    /** 是否有任何实际可用的能力（骨架阶段为 false 时 UI 显示"未接入"）。 */
    val hasAnyCapability: Boolean
        get() = auth != null || library != null || detail != null || browse != null ||
            playback != null || search != null || subtitle != null || progress != null
}
