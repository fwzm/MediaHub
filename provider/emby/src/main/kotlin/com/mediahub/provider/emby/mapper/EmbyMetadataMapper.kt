package com.mediahub.provider.emby.mapper

import com.mediahub.model.Person
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyPersonDto

/**
 * 演职人员 / 制作公司 / 标签映射（Phase 1B-3 Metadata Pipeline）。
 * 纯函数，可单测；图片 URL 由 [EmbyImageMapper.personImageUrl] 统一生成。
 */
object EmbyMetadataMapper {

    fun mapPeople(api: EmbyApiClient, people: List<EmbyPersonDto>?): List<Person> =
        people.orEmpty().mapNotNull { p ->
            val name = p.name?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val personId = p.id ?: return@mapNotNull null
            val roleType = when (p.type?.lowercase()) {
                "director" -> Person.Role.DIRECTOR
                "writer" -> Person.Role.WRITER
                "producer" -> Person.Role.PRODUCER
                "actor", "gueststar" -> Person.Role.ACTOR
                else -> Person.Role.OTHER
            }
            Person(
                id = personId,
                name = name,
                role = roleType,
                type = p.type,
                imageUrl = p.primaryImageTag?.let { tag ->
                    EmbyImageMapper.personImageUrl(api, personId, tag)
                },
            )
        }

    fun mapStudios(studios: List<com.mediahub.provider.emby.api.EmbyStudioDto>?): List<String> =
        studios.orEmpty().mapNotNull { it.name?.takeIf(String::isNotBlank) }
}
