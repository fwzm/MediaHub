package com.mediahub.provider.emby.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 收藏回读 DTO（task-22 / A1）。
 *
 * 与 [com.mediahub.provider.emby.api.EmbyUserDataDto] 的关键差异：**全部字段可空**。
 * 列表/详情 DTO 的 `IsFavorite: Boolean = false` 默认值会把"服务器没返回该字段"
 * 与"明确未收藏"混为一谈——回读场景下这种混淆等于谎报成功，因此回读专用 DTO
 * 用 `Boolean?`，缺失即视为协议破损（Provider 层抛 ProviderException.Parse）。
 */
@Serializable
data class EmbyFavoriteStateDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("UserData") val userData: EmbyFavoriteUserDataDto? = null,
)

/** 收藏回读的 UserData 子集；[isFavorite] 为 null 表示服务器未返回该字段。 */
@Serializable
data class EmbyFavoriteUserDataDto(
    @SerialName("IsFavorite") val isFavorite: Boolean? = null,
)
