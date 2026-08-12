package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.ProviderCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EmbyProviderFactoryTest {
    @Test
    fun `phase one A handle exposes only auth`() {
        val logger = StdoutLogger()
        val factory = EmbyProviderFactory(
            HttpClientFactory(logger),
            ClientIdentity("MediaHub", "Android", "device", "test"),
            logger,
        )

        val handle = factory.create(
            MediaServer("server-1", "Emby", "emby", "http://localhost:8096", createdAtEpochMs = 0)
        )

        assertNotNull(handle.auth)
        assertNull(handle.library)
        assertNull(handle.browse)
        assertNull(handle.playback)
        assertNull(handle.search)
        assertNull(handle.subtitle)
        assertNull(handle.progress)
        assertEquals(setOf(ProviderCapability.AUTH), handle.runtimeCapabilities)
    }
}
