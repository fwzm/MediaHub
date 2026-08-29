package com.mediahub.feature.search.engine

import com.mediahub.model.ExternalIdProvider
import com.mediahub.model.ExternalIds
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索聚合器（Phase 1E 冻结场景）：
 * 别名集交集聚合 / 类型隔离 / 无 ID 单例 / 同 server 不算多来源 / 顺序稳定。
 */
class SearchAggregatorTest {

    private fun hit(
        serverId: String,
        serverName: String,
        id: String,
        type: MediaType,
        ids: ExternalIds?,
        title: String = "冰血暴",
        year: Int? = 2014,
    ) = UnifiedSearchHit(
        item = MediaItem(
            serverId = serverId, id = id, type = type, title = title,
            year = year, externalIds = ids,
        ),
        serverName = serverName,
    )

    private val tmdbAndImdb = ExternalIds(tmdb = "550", imdb = "tt0137523")
    private val imdbOnly = ExternalIds(imdb = "tt0137523")
    private val tmdbOnly = ExternalIds(tmdb = "550")

    // ---- 冻结场景 1：A(TMDb+IMDb) + B(IMDb only) → MUST aggregate ----

    @Test
    fun `shared imdb key aggregates across servers`() {
        val entries = SearchAggregator.group(
            listOf(
                hit("srv-a", "予初", "m1", MediaType.MOVIE, tmdbAndImdb),
                hit("srv-b", "墨云阁", "m2", MediaType.MOVIE, imdbOnly),
            ),
        )

        assertEquals(1, entries.size)
        val multi = entries.single() as SearchResultEntry.MultiSource
        assertEquals(2, multi.occurrences.size)
        assertEquals(2, multi.sourceCount)
        // identityKeys 是组内别名并集（TMDB+IMDb 双键）
        assertTrue(
            multi.identityKeys.containsAll(
                setOf(
                    com.mediahub.model.CanonicalKey(MediaType.MOVIE, ExternalIdProvider.IMDB, "tt0137523"),
                    com.mediahub.model.CanonicalKey(MediaType.MOVIE, ExternalIdProvider.TMDB, "550"),
                ),
            ),
        )
    }

    // ---- 冻结场景 2：A(TMDb only) + B(IMDb only) → MUST NOT aggregate ----

    @Test
    fun `disjoint alias sets stay singles`() {
        val entries = SearchAggregator.group(
            listOf(
                hit("srv-a", "予初", "m1", MediaType.MOVIE, tmdbOnly),
                hit("srv-b", "墨云阁", "m2", MediaType.MOVIE, imdbOnly),
            ),
        )
        assertEquals(2, entries.size)
        entries.forEach { assertTrue(it is SearchResultEntry.Single) }
    }

    // ---- 冻结场景 3：Movie TMDb=123 vs Series TMDb=123 → MUST NOT aggregate ----

    @Test
    fun `same tmdb value across types never aggregates`() {
        val entries = SearchAggregator.group(
            listOf(
                hit("srv-a", "予初", "mv", MediaType.MOVIE, tmdbOnly.copy(tmdb = "123")),
                hit("srv-b", "墨云阁", "tv", MediaType.SERIES, tmdbOnly.copy(tmdb = "123")),
            ),
        )
        assertEquals(2, entries.size)
        entries.forEach { assertTrue(it is SearchResultEntry.Single) }
    }

    // ---- 冻结场景 4：两个无 IDs 同名同年作品 → two singles ----

    @Test
    fun `no external ids means title and year never participate`() {
        val entries = SearchAggregator.group(
            listOf(
                hit("srv-a", "予初", "a1", MediaType.MOVIE, null, title = "冰血暴", year = 2014),
                hit("srv-b", "墨云阁", "b1", MediaType.MOVIE, null, title = "冰血暴", year = 2014),
            ),
        )
        assertEquals(2, entries.size)
        entries.forEach { assertTrue(it is SearchResultEntry.Single) }
    }

    // ---- 冻结场景 5：Episode 相同 TVDb → aggregate ----

    @Test
    fun `episodes with same tvdb id aggregate`() {
        val entries = SearchAggregator.group(
            listOf(
                hit("srv-a", "予初", "e1", MediaType.EPISODE, ExternalIds(tvdb = "ep-9")),
                hit("srv-b", "墨云阁", "e2", MediaType.EPISODE, ExternalIds(tvdb = "ep-9")),
            ),
        )
        val multi = entries.single() as SearchResultEntry.MultiSource
        assertEquals(2, multi.sourceCount)
        assertTrue(
            multi.identityKeys.contains(
                com.mediahub.model.CanonicalKey(MediaType.EPISODE, ExternalIdProvider.TVDB, "ep-9"),
            ),
        )
    }

    // ---- 冻结场景 6：Episode 只有相同 S/E、无 external ID → MUST NOT aggregate ----

    @Test
    fun `same season episode numbers without ids never aggregate`() {
        val entries = SearchAggregator.group(
            listOf(
                hit("srv-a", "予初", "e1", MediaType.EPISODE, null).let {
                    it.copy(item = it.item.copy(seasonNumber = 1, episodeNumber = 1))
                },
                hit("srv-b", "墨云阁", "e2", MediaType.EPISODE, null).let {
                    it.copy(item = it.item.copy(seasonNumber = 1, episodeNumber = 1))
                },
            ),
        )
        assertEquals(2, entries.size)
        entries.forEach { assertTrue(it is SearchResultEntry.Single) }
    }

    // ---- 冻结场景 7：同 external ID 但仅同一 server 两条 → 不得显示"2 个来源" ----

    @Test
    fun `same server duplicate identity does not become multi source`() {
        val entries = SearchAggregator.group(
            listOf(
                hit("srv-a", "予初", "dup1", MediaType.MOVIE, tmdbAndImdb),
                hit("srv-a", "予初", "dup2", MediaType.MOVIE, tmdbAndImdb),
            ),
        )
        // distinct serverId = 1 < 2：不构成多来源，按单例行（原序）渲染
        assertEquals(2, entries.size)
        entries.forEach { assertTrue(it is SearchResultEntry.Single) }
    }

    @Test
    fun `bridge items form one transitive connected component`() {
        val entries = SearchAggregator.group(
            listOf(
                hit("srv-a", "A", "a1", MediaType.MOVIE, ExternalIds(tmdb = "1", imdb = "X")),
                hit("srv-b", "B", "b1", MediaType.MOVIE, ExternalIds(imdb = "X", tvdb = "Y")),
                hit("srv-c", "C", "c1", MediaType.MOVIE, ExternalIds(tvdb = "Y")),
            ),
        )

        val multi = entries.filterIsInstance<SearchResultEntry.MultiSource>().single()
        assertEquals(3, multi.occurrences.size)
        // bridge 后组内别名并集包含全部三键（TMDB/1、IMDB/X、TVDB/Y）
        assertTrue(
            multi.identityKeys.containsAll(
                setOf(
                    com.mediahub.model.CanonicalKey(MediaType.MOVIE, ExternalIdProvider.TMDB, "1"),
                    com.mediahub.model.CanonicalKey(MediaType.MOVIE, ExternalIdProvider.IMDB, "X"),
                    com.mediahub.model.CanonicalKey(MediaType.MOVIE, ExternalIdProvider.TVDB, "Y"),
                ),
            ),
        )
        assertTrue(multi.occurrences.map { it.item.id }.containsAll(setOf("a1", "b1", "c1")))
    }

    // ---- 冻结场景 8：跨 2 server 同 identity → MultiSource, sourceCount=2 ----

    @Test
    fun `cross server identity becomes multi source with source count 2`() {
        val entries = SearchAggregator.group(
            listOf(
                hit("srv-a", "予初", "a1", MediaType.MOVIE, tmdbAndImdb),
                hit("srv-b", "墨云阁", "b1", MediaType.MOVIE, tmdbAndImdb),
            ),
        )
        val multi = entries.single() as SearchResultEntry.MultiSource
        assertEquals(2, multi.sourceCount)
        assertEquals(setOf("srv-a", "srv-b"), multi.occurrences.map { it.item.serverId }.toSet())
    }

    // ---- partial search：A 先回（Single）→ B 后回同 identity（MultiSource） ----

    @Test
    fun `partial snapshots aggregate progressively without touching engine semantics`() {
        val onlyA = listOf(hit("srv-a", "予初", "m1", MediaType.MOVIE, tmdbAndImdb))
        val withB = onlyA + listOf(hit("srv-b", "墨云阁", "b1", MediaType.MOVIE, tmdbAndImdb))

        val first = SearchAggregator.group(onlyA)
        assertTrue(first.single() is SearchResultEntry.Single)

        val second = SearchAggregator.group(withB)
        val multi = second.single() as SearchResultEntry.MultiSource
        assertEquals(2, multi.sourceCount)
    }

    // ---- 顺序稳定：多组输出按首个命中位置排序 ----

    @Test
    fun `entries ordered by first hit position across groups`() {
        // 三组互不相交的别名集：组1={MOVIE/TMDB/111}、单例={}（无 ID）、组2={MOVIE/IMDB/tt42}
        val entries = SearchAggregator.group(
            listOf(
                hit("srv-1", "s1", "y1", MediaType.MOVIE, ExternalIds(tmdb = "111")),
                hit("srv-2", "s2", "y2", MediaType.MOVIE, ExternalIds(tmdb = "111")),
                hit("srv-3", "s3", "z1", MediaType.MOVIE, null),
                hit("srv-4", "s4", "a1", MediaType.MOVIE, ExternalIds(imdb = "tt42")),
                hit("srv-5", "s5", "a2", MediaType.MOVIE, ExternalIds(imdb = "tt42")),
            ),
        )
        // 输出按各组首个命中位置稳定排序：组1(0,1) → 单例(2) → 组2(3,4)
        // （MultiSource 条目映射为首命中 id；y2/a2 藏于 occurrences）
        val ids = entries.map { entry ->
            when (entry) {
                is SearchResultEntry.Single -> entry.hit.item.id
                is SearchResultEntry.MultiSource -> entry.occurrences.first().item.id
            }
        }
        // 3 个条目：组1(MultiSource: y1+y2) → 单例 z1 → 组2(MultiSource: a1+a2)
        assertEquals(listOf("y1", "z1", "a1"), ids)
        // 组2 的 a2 在其 occurrences 内（不丢成员）
        val group2 = entries.filterIsInstance<SearchResultEntry.MultiSource>().last()
        assertTrue(group2.occurrences.any { it.item.id == "a2" })
    }
}
