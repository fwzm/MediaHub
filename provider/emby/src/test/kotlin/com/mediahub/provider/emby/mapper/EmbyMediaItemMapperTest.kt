package com.mediahub.provider.emby.mapper

import com.mediahub.model.MediaType
import com.mediahub.provider.emby.api.EmbyBaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** preflight 加固（Phase 1B-2）：Id 缺失/空白的条目禁止进入领域层。 */
class EmbyMediaItemMapperTest {
    @Test
    fun `items without usable id map to null`() {
        assertNull(EmbyMediaItemMapper.map(EmbyBaseItemDto(id = null, name = "无名"), "srv-1"))
        assertNull(EmbyMediaItemMapper.map(EmbyBaseItemDto(id = "  ", name = "空id"), "srv-1"))
    }
}

// ---- Phase 1E：ProviderIds 归一化（key 小写/trim、冲突 provider 丢弃） ----

private fun dto(vararg providers: Pair<String, String>) = EmbyBaseItemDto(
    id = "m1", name = "X", type = "Movie",
    providerIds = if (providers.isEmpty()) null else mapOf(*providers),
)

class EmbyProviderIdsMappingTest {

    @Test
    fun `provider ids map to external ids with normalized keys`() {
        val item = EmbyMediaItemMapper.map(dto("Imdb" to " tt0076759 ", "TMDB" to "123"), "srv-1")!!
        assertEquals("tt0076759", item.externalIds?.imdb)
        assertEquals("123", item.externalIds?.tmdb)
        assertNull(item.externalIds?.tvdb)
    }

    @Test
    fun `blank or empty values are dropped`() {
        val item = EmbyMediaItemMapper.map(dto("Tmdb" to "123", "Imdb" to "  "), "srv-1")!!
        assertEquals("123", item.externalIds?.tmdb)
        assertNull(item.externalIds?.imdb)
    }

    @Test
    fun `conflicting same provider different value drops the provider`() {
        // 评审冻结规则：Tmdb=123 与 tmdb=456 同时出现 → 整个 tmdb 丢弃，
        // 不做 first/last wins（避免 map 迭代顺序制造错误跨源聚合）
        val item = EmbyMediaItemMapper.map(dto("Tmdb" to "123", "tmdb" to "456"), "srv-1")!!
        assertNull(item.externalIds?.tmdb)
    }

    @Test
    fun `same provider same value duplicates keep the provider`() {
        val item = EmbyMediaItemMapper.map(dto("Tmdb" to "123", "tmdb" to "123"), "srv-1")!!
        assertEquals("123", item.externalIds?.tmdb)
    }

    @Test
    fun `absent provider ids yield null external ids`() {
        val item = EmbyMediaItemMapper.map(EmbyBaseItemDto(id = "m1", name = "X", type = "Movie"), "srv-1")
        assertNull(item?.externalIds)
    }

    @Test
    fun `key normalization is case insensitive`() {
        // "TVDB" / "TvDb" 等任意大小写组合 → 归一为 tvdb
        val item = EmbyMediaItemMapper.map(dto("TVDB" to "12"), "srv-1")!!
        assertEquals("12", item.externalIds?.tvdb)
    }
}
