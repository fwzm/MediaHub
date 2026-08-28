package com.mediahub.provider.api

import com.mediahub.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProviderHandle 运行时能力推导（ADR-022）：
 * 字段非空 ⇔ runtimeCapabilities 包含对应能力；未实现的能力不得进入 Handle。
 */
class ProviderHandleTest {

    private fun provider() = object : MediaProvider, MediaBrowseProvider, MediaPlaybackProvider {
        override val serverId: String = "s1"
        override val type: ServerType = ServerType.LOCAL
        override val displayName: String = "本地"
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = "local",
            serverType = ServerType.LOCAL,
            displayName = "本地",
            category = ProviderCategory.CLOUD_STORAGE,
            declaredCapabilities = setOf(
                ProviderCapability.BROWSE,
                ProviderCapability.DETAIL,
                ProviderCapability.PLAYBACK,
            ),
            authMethod = AuthMethod.NONE,
            status = ProviderStatus.STABLE,
        )
        override suspend fun testConnection() = ConnectionStatus(ok = true, message = "ok")
        override suspend fun listFolder(
            folder: com.mediahub.model.MediaItem?,
            page: com.mediahub.model.PageRequest,
        ) = com.mediahub.model.PagedResult<com.mediahub.model.MediaItem>(emptyList())
        override suspend fun resolvePlayback(
            item: com.mediahub.model.MediaItem,
            options: com.mediahub.model.PlaybackOptions,
        ) = com.mediahub.model.PlaybackSource(url = "file:///x")
    }

    @Test
    fun `runtime capabilities derived from fields`() {
        val p = provider()
        val handle = ProviderHandle(provider = p, browse = p, playback = p)
        assertEquals(
            setOf(ProviderCapability.BROWSE, ProviderCapability.PLAYBACK),
            handle.runtimeCapabilities,
        )
        assertTrue(handle.hasAnyCapability)
    }

    @Test
    fun `empty handle has no runtime capabilities`() {
        val p = provider()
        val handle = ProviderHandle(provider = p)
        assertTrue(handle.runtimeCapabilities.isEmpty())
        assertFalse(handle.hasAnyCapability)
    }

    @Test
    fun `runtime capabilities are subset of declared`() {
        val p = provider()
        val handle = ProviderHandle(provider = p, browse = p, playback = p)
        assertTrue(handle.runtimeCapabilities.all { it in p.descriptor.declaredCapabilities })
    }

    // ---- Phase 1C-2：Query 能力推导 ----

    /** 实现 Query 能力的最小 fake。 */
    private fun queryProvider() = object : MediaProvider, MediaQueryLibraryProvider {
        override val serverId: String = "s1"
        override val type: ServerType = ServerType.LOCAL
        override val displayName: String = "本地"
        override val descriptor: ProviderDescriptor = provider().descriptor
        override val sortCapabilities = com.mediahub.model.MediaSortCapabilities(
            setOf(com.mediahub.model.MediaSortField.SERVER_DEFAULT, com.mediahub.model.MediaSortField.TITLE),
        )
        override suspend fun testConnection() = ConnectionStatus(ok = true, message = "ok")
        override suspend fun getItems(
            libraryId: String,
            query: com.mediahub.model.MediaListQuery,
        ) = com.mediahub.model.PagedResult<com.mediahub.model.MediaItem>(emptyList())
    }

    @Test
    fun `query capability derived from field and absent when null`() {
        val q = queryProvider()
        val withQuery = ProviderHandle(provider = q, query = q)
        assertEquals(setOf(ProviderCapability.QUERY), withQuery.runtimeCapabilities)
        assertTrue(withQuery.hasAnyCapability)

        val withoutQuery = ProviderHandle(provider = q)
        assertTrue(ProviderCapability.QUERY !in withoutQuery.runtimeCapabilities)
    }

    @Test
    fun `query fallback semantics - page request extracts from query`() {
        // 兼容迁移语义：未实现 Query 能力时，调用方回退 library.getItems(libraryId, query.page)
        val query = com.mediahub.model.MediaListQuery(
            page = com.mediahub.model.PageRequest(offset = 60, limit = 30),
            sort = com.mediahub.model.MediaSort(com.mediahub.model.MediaSortField.COMMUNITY_RATING),
        )
        assertEquals(60, query.page.offset)
        assertEquals(30, query.page.limit)
    }
}
