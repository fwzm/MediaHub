package com.mediahub.provider.base

import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaBrowseProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProviderRegistryTest {

    private fun descriptor(id: String, type: ServerType, name: String) = ProviderDescriptor(
        id = id,
        serverType = type,
        displayName = name,
        category = ProviderCategory.CLOUD_STORAGE,
        capabilities = emptySet(),
        authMethod = AuthMethod.NONE,
        status = ProviderStatus.STABLE,
    )

    private class FakeProvider(
        override val serverId: String,
        override val type: ServerType,
        override val displayName: String,
        override val descriptor: ProviderDescriptor,
    ) : MediaProvider, MediaBrowseProvider, MediaPlaybackProvider {
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

    private class FakeFactory(
        override val descriptor: ProviderDescriptor,
    ) : MediaProviderFactory {
        override fun create(server: MediaServer): ProviderHandle {
            val provider = FakeProvider(
                serverId = server.id,
                type = descriptor.serverType,
                displayName = server.displayName,
                descriptor = descriptor,
            )
            return ProviderHandle(provider = provider, browse = provider, playback = provider)
        }
    }

    private fun server(type: ServerType, id: String = "s1") = MediaServer(
        id = id,
        name = "测试",
        type = type,
        baseUrl = "http://localhost",
        createdAtEpochMs = 0,
    )

    @Test
    fun `create returns handle for registered type`() {
        val registry = DefaultProviderRegistry(
            setOf(FakeFactory(descriptor("local", ServerType.LOCAL, "本地")))
        )
        val handle = registry.create(server(ServerType.LOCAL))
        assertNotNull(handle)
        assertEquals(ServerType.LOCAL, handle!!.provider.type)
    }

    @Test
    fun `unregistered type returns null`() {
        val registry = DefaultProviderRegistry(
            setOf(FakeFactory(descriptor("local", ServerType.LOCAL, "本地")))
        )
        assertNull(registry.create(server(ServerType.EMBY)))
    }

    @Test
    fun `descriptors lists all registered sorted by display name`() {
        val registry = DefaultProviderRegistry(
            setOf(
                FakeFactory(descriptor("emby", ServerType.EMBY, "Emby")),
                FakeFactory(descriptor("local", ServerType.LOCAL, "本地存储")),
            )
        )
        val names = registry.descriptors().map { it.displayName }
        assertEquals(listOf("Emby", "本地存储"), names)
        assertEquals(setOf(ServerType.EMBY, ServerType.LOCAL), registry.supportedTypes)
    }

    @Test
    fun `handle fields match declared capabilities`() {
        val registry = DefaultProviderRegistry(
            setOf(FakeFactory(descriptor("local", ServerType.LOCAL, "本地")))
        )
        val handle = registry.create(server(ServerType.LOCAL))!!
        assertNotNull(handle.browse)
        assertNotNull(handle.playback)
        assertNull(handle.auth)
        assertNull(handle.library)
        assertTrue(handle.hasAnyCapability)
    }
}
