package com.mediahub.provider.emby.mapper

import com.mediahub.model.MediaUser
import com.mediahub.provider.emby.api.EmbyUserDto

/** Emby DTO → 领域模型（领域层禁止直接使用 Emby DTO）。 */
object EmbyUserMapper {

    fun map(dto: EmbyUserDto, localServerId: String): MediaUser = MediaUser(
        serverId = localServerId,
        userId = dto.id.orEmpty(),
        displayName = dto.name.orEmpty(),
    )
}
