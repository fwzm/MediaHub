package com.mediahub.provider.jellyfin.mapper

import com.mediahub.model.ExternalIds
import com.mediahub.model.MediaType
import com.mediahub.provider.jellyfin.api.JellyfinItemDto
import com.mediahub.provider.jellyfin.api.JellyfinPersonDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JellyfinItemMapper（Phase 1G-B）：Type 映射 / ProviderIds 归一化（1E 冻结策略同款）/
 * People / RunTimeTicks / 季集编号。
 */
class JellyfinItemMapperTest {

    private val serverId = "srv-jf"

    private fun dto(
        id: String? = "m1",
        type: String? = "Movie",
        providerIds: Map<String, String>? = null,
        people: List<JellyfinPersonDto> = emptyList(),
        runTimeTicks: Long? = null,
        indexNumber: Int? = null,
        parentIndexNumber: Int? = null,
        isFolder: Boolean = false,
        mediaType: String? = null,
    ) = JellyfinItemDto(
        id = id, name = "Fargo", type = type, isFolder = isFolder, mediaType = mediaType,
        providerIds = providerIds, people = people, runTimeTicks = runTimeTicks,
        indexNumber = indexNumber, parentIndexNumber = parentIndexNumber,
    )

    // ---- Type 映射 ----

    @Test
    fun `type mapping covers jellyfin types and folder fallback`() {
        assertEquals(MediaType.MOVIE, JellyfinItemMapper.mapType(dto(type = "movie")))
        assertEquals(MediaType.SERIES, JellyfinItemMapper.mapType(dto(type = "Series")))
        assertEquals(MediaType.SEASON, JellyfinItemMapper.mapType(dto(type = "Season")))
        assertEquals(MediaType.EPISODE, JellyfinItemMapper.mapType(dto(type = "Episode")))
        assertEquals(MediaType.VIDEO, JellyfinItemMapper.mapType(dto(type = "Video")))
        // 未知类型：IsFolder → FOLDER；MediaType=video → VIDEO；最后 OTHER
        assertEquals(MediaType.FOLDER, JellyfinItemMapper.mapType(dto(type = "CollectionFolder", isFolder = true)))
        assertEquals(MediaType.VIDEO, JellyfinItemMapper.mapType(dto(type = "Whatever", mediaType = "video")))
        assertEquals(MediaType.OTHER, JellyfinItemMapper.mapType(dto(type = "Whatever")))
    }

    // ---- ProviderIds 归一化（1E 冻结策略同款，独立实现） ----

    @Test
    fun `provider ids normalization is case insensitive and trims values`() {
        val external = JellyfinItemMapper.mapProviderIds(
            mapOf(" Imdb " to " tt0137523 ", "TMDB" to " 275 ")
        )
        assertEquals(ExternalIds(tmdb = "275", imdb = "tt0137523"), external)
    }

    @Test
    fun `conflicting provider values drop the whole provider`() {
        val external = JellyfinItemMapper.mapProviderIds(
            mapOf("tmdb" to "275", "Tmdb" to "999")
        )
        assertNull("冲突 provider 整体丢弃（不做 first/last wins）", external?.tmdb)
    }

    @Test
    fun `unknown only provider ids yield null external ids`() {
        assertNull(JellyfinItemMapper.mapProviderIds(mapOf("AniDb" to "123")))
        assertNull(JellyfinItemMapper.mapProviderIds(emptyMap()))
        assertNull(JellyfinItemMapper.mapProviderIds(null))
    }

    // ---- preflight 与编号映射 ----

    @Test
    fun `blank id returns null item`() {
        assertNull(JellyfinItemMapper.map(dto(id = "  "), serverId))
        assertNull(JellyfinItemMapper.map(dto(id = null), serverId))
    }

    @Test
    fun `season and episode numbers map from index fields`() {
        val episode = JellyfinItemMapper.map(
            dto(id = "e1", type = "Episode", indexNumber = 3, parentIndexNumber = 2),
            serverId,
        )!!
        assertEquals(2, episode.seasonNumber)
        assertEquals(3, episode.episodeNumber)

        val season = JellyfinItemMapper.map(
            dto(id = "s1", type = "Season", indexNumber = 2),
            serverId,
        )!!
        assertEquals(2, season.seasonNumber)
        assertNull(season.episodeNumber)
    }

    @Test
    fun `runtime ticks convert to millis`() {
        val item = JellyfinItemMapper.map(dto(id = "m1", runTimeTicks = 10_000L * 3_600), serverId)!!
        assertEquals(3600L, item.runtimeMs)
    }

    // ---- People ----

    @Test
    fun `people map actor character and director roles`() {
        val item = JellyfinItemMapper.map(
            dto(
                id = "m1",
                people = listOf(
                    JellyfinPersonDto(name = "Billy Bob", type = "Actor", role = "Gator", id = "p1"),
                    JellyfinPersonDto(name = "Noah", type = "Director", id = "p2"),
                    JellyfinPersonDto(name = "无名"),
                ),
            ),
            serverId,
        )!!
        assertEquals(3, item.people.size)
        val actor = item.people.first { it.role == com.mediahub.model.Person.Role.ACTOR }
        assertEquals("Billy Bob", actor.name)
        assertEquals("Gator", actor.characterName)
        assertEquals("p1", actor.id)
        assertEquals(
            com.mediahub.model.Person.Role.DIRECTOR,
            item.people.first { it.name == "Noah" }.role,
        )
        // 仅 Name 无 Type：角色未知 → OTHER（不丢人名）
        val unknown = item.people.first { it.name == "无名" }
        assertEquals(com.mediahub.model.Person.Role.OTHER, unknown.role)
    }
}
