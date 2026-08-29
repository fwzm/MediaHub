package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CanonicalIdentityGraph（ADR-037/038 冻结场景）：
 * 传递闭包 / 类型隔离 / 空 keySet 不参与 / 别名并集 / 分量顺序稳定。
 */
class CanonicalIdentityGraphTest {

    private fun key(type: MediaType, provider: ExternalIdProvider, value: String) =
        CanonicalKey(type, provider, value)

    // ---- 冻结场景：A(TMDB/1) + B(TMDB/1+IMDB/X) + C(IMDB/X) → 同一 connected component ----

    @Test
    fun `transitive closure connects a b c through shared aliases`() {
        val components = CanonicalIdentityGraph.components(
            listOf(
                setOf(key(MediaType.MOVIE, ExternalIdProvider.TMDB, "1")),
                setOf(
                    key(MediaType.MOVIE, ExternalIdProvider.TMDB, "1"),
                    key(MediaType.MOVIE, ExternalIdProvider.IMDB, "X"),
                ),
                setOf(key(MediaType.MOVIE, ExternalIdProvider.IMDB, "X")),
            ),
        )

        assertEquals(1, components.size)
        val c = components.single()
        assertEquals(listOf(0, 1, 2), c.indices)
        assertEquals(
            setOf(
                key(MediaType.MOVIE, ExternalIdProvider.TMDB, "1"),
                key(MediaType.MOVIE, ExternalIdProvider.IMDB, "X"),
            ),
            c.identityKeys,
        )
    }

    // ---- 冻结场景：互不相交的别名集 → 各自独立分量 ----

    @Test
    fun `disjoint key sets form separate components`() {
        val components = CanonicalIdentityGraph.components(
            listOf(
                setOf(key(MediaType.MOVIE, ExternalIdProvider.TMDB, "1")),
                setOf(key(MediaType.MOVIE, ExternalIdProvider.IMDB, "X")),
            ),
        )
        assertEquals(2, components.size)
        assertEquals(listOf(0), components[0].indices)
        assertEquals(listOf(1), components[1].indices)
    }

    // ---- 冻结场景：同值跨 MediaType → 绝不连通（MediaType 内嵌键内） ----

    @Test
    fun `same value across media types never connects`() {
        val components = CanonicalIdentityGraph.components(
            listOf(
                setOf(key(MediaType.MOVIE, ExternalIdProvider.TMDB, "123")),
                setOf(key(MediaType.SERIES, ExternalIdProvider.TMDB, "123")),
            ),
        )
        assertEquals(2, components.size)
    }

    // ---- 冻结场景：空 keySet（无有效外部 ID）不参与任何分量 ----

    @Test
    fun `empty key sets do not participate in any component`() {
        val components = CanonicalIdentityGraph.components(
            listOf(
                emptySet(),
                setOf(key(MediaType.MOVIE, ExternalIdProvider.TMDB, "1")),
                emptySet(),
            ),
        )
        assertEquals(1, components.size)
        assertEquals(listOf(1), components.single().indices)
    }

    // ---- 单成员分量：别名并集完整保留（供 MultiSource.identityKeys 语义） ----

    @Test
    fun `single member component carries its full alias union`() {
        val components = CanonicalIdentityGraph.components(
            listOf(
                setOf(
                    key(MediaType.MOVIE, ExternalIdProvider.TMDB, "550"),
                    key(MediaType.MOVIE, ExternalIdProvider.IMDB, "tt0137523"),
                ),
            ),
        )
        val c = components.single()
        assertEquals(listOf(0), c.indices)
        assertEquals(
            setOf(
                key(MediaType.MOVIE, ExternalIdProvider.TMDB, "550"),
                key(MediaType.MOVIE, ExternalIdProvider.IMDB, "tt0137523"),
            ),
            c.identityKeys,
        )
    }

    // ---- 分量按成员首位置升序输出（搜索聚合稳定顺序的地基） ----

    @Test
    fun `components ordered by first member index`() {
        val tmdb111 = setOf(key(MediaType.MOVIE, ExternalIdProvider.TMDB, "111"))
        val imdb42 = setOf(key(MediaType.MOVIE, ExternalIdProvider.IMDB, "tt42"))
        val components = CanonicalIdentityGraph.components(
            listOf(tmdb111, tmdb111, emptySet(), imdb42, imdb42),
        )
        assertEquals(listOf(listOf(0, 1), listOf(3, 4)), components.map { it.indices })
    }
}
