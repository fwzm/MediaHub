package com.mediahub.provider.jellyfin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 收藏回读 DTO（task-22 / A1）。
 *
 * 与 [com.mediahub.provider.jellyfin.api.JellyfinUserDataDto] 的差异同 Emby：
 * 回读专用字段 **可空**，`null` = 服务器未返回 `UserData.IsFavorite` = 协议破损，
 * 由 Provider 层转为 ProviderException.Parse，绝不降级为 false。
 */
@Serializable
data class JellyfinFavoriteStateDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("UserData") val userData: JellyfinFavoriteUserDataDto? = null,
)

/** 收藏回读的 UserData 子集；[isFavorite] 为 null 表示服务器未返回该字段。 */
@Serializable
data class JellyfinFavoriteUserDataDto(
    @SerialName("IsFavorite") val isFavorite: Boolean? = null,
)
