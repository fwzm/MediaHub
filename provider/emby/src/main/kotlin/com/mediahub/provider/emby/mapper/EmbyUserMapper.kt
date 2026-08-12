package com.mediahub.provider.emby.mapper

import com.mediahub.model.MediaUser
import com.mediahub.provider.emby.api.EmbyUserDto

object EmbyUserMapper {
    fun map(dto: EmbyUserDto, localServerId: String): MediaUser = MediaUser(
        serverId = localServerId,
        userId = dto.id.orEmpty(),
        displayName = dto.name.orEmpty(),
    )
}
