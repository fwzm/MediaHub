package com.mediahub.provider.webdav

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.CredentialVault
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.SessionCredential
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavConnectionTest {
    @Test
    fun `connection test uses OPTIONS and requires DAV capability header`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).addHeader("DAV", "1, 2"))
            server.enqueue(MockResponse().setResponseCode(401))
            val provider = provider(server)

            assertTrue(provider.testConnection().ok)
            assertEquals("OPTIONS", server.takeRequest().method)
            assertFalse(provider.testConnection().ok)
        } finally {
            server.shutdown()
        }
    }

    private fun provider(mock: MockWebServer): WebDavProvider {
        val client = OkHttpClient()
        return WebDavProvider(
            server = MediaServer(
                id = "server-1",
                name = "WebDAV",
                providerId = "webdav",
                baseUrl = mock.url("/dav").toString().trimEnd('/'),
                createdAtEpochMs = 1L,
            ),
            apiClient = ApiClient(client, logger = NoOpLogger),
            mediaHttpClient = MediaHttpClient(client, NoOpLogger),
            credentialVault = EmptyVault,
            logger = NoOpLogger,
        )
    }

    private object EmptyVault : CredentialVault {
        override suspend fun savePending(serverId: String, credentials: Credentials) = Unit
        override suspend fun readPending(serverId: String): Credentials? = null
        override suspend fun saveSession(serverId: String, session: SessionCredential) = Unit
        override suspend fun readSession(serverId: String): SessionCredential? = null
        override suspend fun clearPending(serverId: String) = Unit
        override suspend fun clear(serverId: String) = Unit
    }

    private object NoOpLogger : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }
}
