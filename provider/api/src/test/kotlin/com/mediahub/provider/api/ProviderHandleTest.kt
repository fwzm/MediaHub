package com.mediahub.provider.api

import com.mediahub.model.MediaItem
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackSource
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderHandleTest {
    @Test
    fun `local style provider composes only browse and playback`() {
        val provider = BrowsePlaybackProvider()
        val handle = ProviderHandle(provider, browse = provider, playback = provider)

        assertNull(handle.auth)
        assertNull(handle.library)
        assertNotNull(handle.browse)
        assertNotNull(handle.playback)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `descriptor and concrete capability must agree`() {
        val provider = BrowsePlaybackProvider()
        ProviderHandle(provider = provider, browse = provider)
    }

    private class BrowsePlaybackProvider : MediaProvider, MediaBrowseProvider, MediaPlaybackProvider {
        override val serverId = "local-test"
        override val descriptor = ProviderDescriptor(
            providerId = "local-test",
            displayName = "Local Test",
            description = "test",
            category = ProviderCategory.LOCAL_STORAGE,
            capabilities = setOf(ProviderCapability.BROWSE, ProviderCapability.PLAYBACK),
            authMethod = AuthMethod.NONE,
            status = ProviderStatus.AVAILABLE,
        )

        override suspend fun testConnection(request: ConnectionTestRequest) = ConnectionStatus(ok = true)

        override suspend fun listFolder(folder: MediaItem?, page: PageRequest) =
            PagedResult<MediaItem>(items = emptyList())

        override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions) =
            PlaybackSource(url = item.path ?: item.id)
    }
}
