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
}
