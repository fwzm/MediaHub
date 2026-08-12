package com.mediahub.provider.webdav

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.webdav.auth.WebDavAuthProvider
import java.nio.charset.StandardCharsets
import java.util.Base64
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
            val authRequired = provider.testConnection()
            assertFalse(authRequired.ok)
            assertEquals(ProviderException.ErrorCode.AUTH_REQUIRED, authRequired.errorCode)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `authenticate validates credentials with protected PROPFIND`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            val failed = authProvider(server).authenticate(Credentials.BasicAuth("alice", "wrong"))
            assertTrue(failed is AuthResult.Failure)
            assertEquals("PROPFIND", server.takeRequest().method)

            server.enqueue(MockResponse().setResponseCode(207))
            val success = authProvider(server).authenticate(Credentials.BasicAuth("alice", "correct"))
            assertTrue(success is AuthResult.Success)
            assertEquals("0", server.takeRequest().getHeader("Depth"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `basic auth uses standard charset and retries utf8 only when challenged`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .addHeader("WWW-Authenticate", "Basic realm=\"dav\", charset=\"UTF-8\"")
            )
            server.enqueue(MockResponse().setResponseCode(207))

            val credentials = Credentials.BasicAuth("用户", "秘密")
            assertTrue(authProvider(server).authenticate(credentials) is AuthResult.Success)
            val defaultHeader = server.takeRequest().getHeader("Authorization")
            val utf8Header = server.takeRequest().getHeader("Authorization")
            val expectedUtf8 = "Basic " + Base64.getEncoder().encodeToString(
                "用户:秘密".toByteArray(StandardCharsets.UTF_8)
            )
            assertFalse(defaultHeader == expectedUtf8)
            assertEquals(expectedUtf8, utf8Header)
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
            logger = NoOpLogger,
        )
    }

    private fun authProvider(mock: MockWebServer): WebDavAuthProvider = WebDavAuthProvider(
        server = MediaServer(
            id = "server-1",
            name = "WebDAV",
            providerId = "webdav",
            baseUrl = mock.url("/dav").toString().trimEnd('/'),
            createdAtEpochMs = 1L,
        ),
        apiClient = ApiClient(OkHttpClient(), logger = NoOpLogger),
    )

    private object NoOpLogger : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }
}
