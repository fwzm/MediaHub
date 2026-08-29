package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨源身份（Phase 1E）contract 测试：
 * multi-alias 候选集合 / 类型维度隔离 / 空白跳过 / MediaItem 默认 null。
 *
 * 冻结规则：同 MediaType + 共享至少一个相同 (provider, value) 键 = 可聚合；
 * title/year 永不参与判定；无任何共享键 = 绝不聚合。
 */
class ExternalIdentityTest {

    private val ids = ExternalIds(tmdb = "550", imdb = "tt0137523")

    // ---- 候选集合：别名集合而非单一主键 ----

    @Test
    fun `keys returns full alias set not single primary`() {
        val keys = CanonicalKeyPolicy.keys(MediaType.MOVIE, ids)
        assertEquals(
            setOf(
                CanonicalKey(MediaType.MOVIE, ExternalIdProvider.TMDB, "550"),
                CanonicalKey(MediaType.MOVIE, ExternalIdProvider.IMDB, "tt0137523"),
            ),
            keys,
        )
    }

    @Test
    fun `alias sets with shared key aggregate - the P1 scenario`() {
        // 评审 P1 核心场景：A 有 TMDb+IMDb，B 只有 IMDb——单主键策略会聚合失败
        val keysA = CanonicalKeyPolicy.keys(MediaType.MOVIE, ids)
        val keysB = CanonicalKeyPolicy.keys(MediaType.MOVIE, ExternalIds(imdb = "tt0137523"))
        assertTrue("共享 IMDb 键 → 必须可聚合", keysA.intersect(keysB).isNotEmpty())
    }

    @Test
    fun `disjoint alias sets never aggregate`() {
        val keysA = CanonicalKeyPolicy.keys(MediaType.MOVIE, ExternalIds(tmdb = "550"))
        val keysB = CanonicalKeyPolicy.keys(MediaType.MOVIE, ExternalIds(imdb = "tt0137523"))
        assertTrue("无共享键 → 绝不聚合", keysA.intersect(keysB).isEmpty())
    }

    // ---- 类型维度隔离 ----

    @Test
    fun `same tmdb value across types is isolated`() {
        val movie = CanonicalKey(MediaType.MOVIE, ExternalIdProvider.TMDB, "123")
        val series = CanonicalKey(MediaType.SERIES, ExternalIdProvider.TMDB, "123")
        assertNotEquals(movie, series)
    }

    @Test
    fun `series tmdb key differs from movie tmdb key for same value`() {
        val keys = CanonicalKeyPolicy.keys(MediaType.SERIES, ExternalIds(tmdb = "123"))
        assertEquals(setOf(CanonicalKey(MediaType.SERIES, ExternalIdProvider.TMDB, "123")), keys)
        assertNotEquals(CanonicalKey(MediaType.MOVIE, ExternalIdProvider.TMDB, "123"), keys.first())
    }

    // ---- 空白 / 缺失处理 ----

    @Test
    fun `blank values are skipped`() {
        val keys = CanonicalKeyPolicy.keys(
            MediaType.MOVIE,
            ExternalIds(tmdb = "  ", imdb = "tt1", tvdb = ""),
        )
        assertEquals(setOf(CanonicalKey(MediaType.MOVIE, ExternalIdProvider.IMDB, "tt1")), keys)
    }

    @Test
    fun `null or empty external ids yield empty key set`() {
        assertTrue(CanonicalKeyPolicy.keys(MediaType.MOVIE, null).isEmpty())
        assertTrue(CanonicalKeyPolicy.keys(MediaType.MOVIE, ExternalIds()).isEmpty())
    }

    @Test
    fun `tvdb participates for series`() {
        val keys = CanonicalKeyPolicy.keys(MediaType.SERIES, ExternalIds(tvdb = "tvdb-1"))
        assertEquals(setOf(CanonicalKey(MediaType.SERIES, ExternalIdProvider.TVDB, "tvdb-1")), keys)
    }

    // ---- MediaItem 默认 ----

    @Test
    fun `media item external ids default to null`() {
        val item = MediaItem(serverId = "srv", id = "m1", type = MediaType.MOVIE, title = "Fargo")
        assertNull(item.externalIds)
    }
}
