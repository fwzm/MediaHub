package com.mediahub.feature.search.engine

import com.mediahub.model.ExternalIds
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1G-C 跨 Provider canonical 聚合证明（ADR-039）：
 * Emby 与 Jellyfin 的搜索命中在**现有 SearchAggregator** 中按 canonical identity
 * 聚合为同一 MultiSource，零 provider 分支（aggregator 对来源无感知）。
 *
 * 两侧 MediaItem 的 ExternalIds 值 = 各自 mapper 单测已锁定的归一化产物
 * （Emby：EmbyProviderIdsMappingTest；Jellyfin：JellyfinItemMapperTest）——
 * 归一化策略一致（key 小写/trim/冲突丢弃），因此同值跨源必聚合。
 */
class JellyfinEmbyCanonicalAggregationTest {

    /** Emby 命中（予初）。 */
    private fun embyHit(id: String, tmdb: String?) = UnifiedSearchHit(
        item = MediaItem(
            serverId = "srv-emby", id = id, type = MediaType.MOVIE, title = "Fargo",
            year = 2014, externalIds = tmdb?.let { ExternalIds(tmdb = it) },
        ),
        serverName = "予初",
    )

    /** Jellyfin 命中（墨云阁）。 */
    private fun jellyfinHit(id: String, tmdb: String?) = UnifiedSearchHit(
        item = MediaItem(
            serverId = "srv-jf", id = id, type = MediaType.MOVIE, title = "Fargo",
            year = 2014, externalIds = tmdb?.let { ExternalIds(tmdb = it) },
        ),
        serverName = "墨云阁",
    )

    @Test
    fun `same canonical id across emby and jellyfin aggregates into one multi source`() {
        val entries = SearchAggregator.group(
            listOf(
                embyHit("e1", "550"),
                jellyfinHit("j1", "550"),
            ),
        )

        assertEquals(1, entries.size)
        val multi = entries.single() as SearchResultEntry.MultiSource
        assertEquals(2, multi.sourceCount)
        assertEquals(setOf("srv-emby", "srv-jf"), multi.occurrences.map { it.item.serverId }.toSet())
    }

    @Test
    fun `different canonical ids stay singles across providers`() {
        val entries = SearchAggregator.group(
            listOf(
                embyHit("e1", "550"),
                jellyfinHit("j1", "551"),
            ),
        )

        assertEquals(2, entries.size)
        entries.forEach { assertTrue(it is SearchResultEntry.Single) }
    }

    @Test
    fun `partial snapshots aggregate progressively across providers`() {
        val onlyEmby = listOf(embyHit("e1", "550"))
        val withJellyfin = onlyEmby + listOf(jellyfinHit("j1", "550"))

        assertTrue(SearchAggregator.group(onlyEmby).single() is SearchResultEntry.Single)

        val second = SearchAggregator.group(withJellyfin)
        val multi = second.single() as SearchResultEntry.MultiSource
        assertEquals(2, multi.sourceCount)
    }
}
