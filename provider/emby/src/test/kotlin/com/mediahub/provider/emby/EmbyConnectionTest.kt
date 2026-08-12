package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.model.MediaServer
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbyConnectionTest {
    @Test
    fun `connection test accepts Emby system info and rejects Jellyfin identity`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"Id":"emby-id","ServerName":"Home","Version":"4.8.10.0","ProductName":"Emby Server"}"""
                )
            )
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"Id":"jf-id","ServerName":"Home","Version":"10.10.0","ProductName":"Jellyfin Server"}"""
                )
            )
            val provider = provider(server)

            assertTrue(provider.testConnection().ok)
            assertFalse(provider.testConnection().ok)
            assertTrue(server.takeRequest().path!!.endsWith("/System/Info/Public"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `connection test rejects wrong malformed and unauthorized responses`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"foo":"bar"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(403))
            val provider = provider(server)

            repeat(4) { assertFalse(provider.testConnection().ok) }
        } finally {
            server.shutdown()
        }
    }

    private fun provider(mock: MockWebServer): EmbyProvider {
        val client = OkHttpClient()
        val mediaServer = MediaServer(
            id = "server-1",
            name = "Emby",
            providerId = "emby",
            baseUrl = mock.url("/emby").toString().trimEnd('/'),
            createdAtEpochMs = 1L,
        )
        val apiClient = ApiClient(client, logger = NoOpLogger)
        val embyApi = EmbyApiClient(
            mediaServer.baseUrl,
            apiClient,
            EmbyAuthorizationHeaderBuilder(ClientIdentity("MediaHub", "Android", "device", "test")),
        )
        return EmbyProvider(mediaServer, apiClient, MediaHttpClient(client, NoOpLogger), NoOpLogger, embyApi)
    }

    private object NoOpLogger : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }
}
