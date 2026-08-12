package com.mediahub.provider.jellyfin

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinConnectionTest {
    @Test
    fun `connection test requires Jellyfin product identity and successful endpoint`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"Id":"jf-id","ServerName":"Home","Version":"10.10.0","ProductName":"Jellyfin Server"}"""
                )
            )
            server.enqueue(MockResponse().setResponseCode(404))
            val provider = provider(server)

            assertTrue(provider.testConnection().ok)
            assertFalse(provider.testConnection().ok)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `connection test rejects valid json without Jellyfin product signature`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"Id":"other-id","ServerName":"Other","Version":"1.0","ProductName":"Other"}"""
                )
            )

            assertFalse(provider(server).testConnection().ok)
        } finally {
            server.shutdown()
        }
    }

    private fun provider(mock: MockWebServer): JellyfinProvider {
        val client = OkHttpClient()
        return JellyfinProvider(
            server = MediaServer(
                id = "server-1",
                name = "Jellyfin",
                providerId = "jellyfin",
                baseUrl = mock.url("/").toString().trimEnd('/'),
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
