package com.mediahub.provider.base

import com.mediahub.model.MediaServer
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ConnectionTestRequest
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DefaultProviderRegistryTest {
    @Test
    fun `registered factory is discoverable by open provider id`() {
        val factory = FakeFactory("custom-drive")
        val registry = DefaultProviderRegistry(setOf(factory))
        val server = MediaServer(
            id = "server-1",
            name = "Custom",
            providerId = "custom-drive",
            baseUrl = "https://example.invalid",
            createdAtEpochMs = 1L,
        )

        assertEquals(factory, registry.factoryFor("custom-drive"))
        assertEquals("Custom Drive", registry.descriptorFor("custom-drive")?.displayName)
        assertNotNull(registry.create(server))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate provider ids fail fast`() {
        DefaultProviderRegistry(setOf(FakeFactory("duplicate"), FakeFactory("duplicate")))
    }

    private class FakeFactory(id: String) : MediaProviderFactory {
        override val descriptor = ProviderDescriptor(
            providerId = id,
            displayName = "Custom Drive",
            description = "test",
            category = ProviderCategory.CLOUD_DRIVE,
            capabilities = emptySet(),
            authMethod = AuthMethod.NONE,
            status = ProviderStatus.AVAILABLE,
        )

        override fun create(server: MediaServer): ProviderHandle = ProviderHandle(
            provider = object : MediaProvider {
                override val serverId = server.id
                override val descriptor = this@FakeFactory.descriptor
                override suspend fun testConnection(request: ConnectionTestRequest) = ConnectionStatus(ok = true)
            }
        )
    }
}
