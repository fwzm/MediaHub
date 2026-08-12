package com.mediahub.provider.webdav

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ConnectionTestRequest
import com.mediahub.provider.api.CredentialVault
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.SessionCredential
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

/**
 * WebDAV 认证（review P2-6/P2-7 + Copilot #10）：
 * - OPTIONS 只探测协议；凭据必须经受保护操作（PROPFIND）验证；
 * - Basic charset 遵循 RFC 7617（WWW-Authenticate charset 声明，默认 ISO-8859-1）；
 * - testConnection 401/403 → AUTH_REQUIRED，而非"未声明 WebDAV 能力"。
 */
class WebDavAuthTest {

    @Test
    fun `authenticate validates credentials via PROPFIND`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            // 1) OPTIONS：401 + WWW-Authenticate（无 charset 声明 → 默认 ISO-8859-1）
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .addHeader("WWW-Authenticate", "Basic realm=\"dav\"")
            )
            // 2) PROPFIND：凭据正确 → 207
            server.enqueue(MockResponse().setResponseCode(207))

            val provider = provider(server)
            val result = provider.authenticate(Credentials.BasicAuth("alice", "pw-123"))

            assertTrue("result=$result", result is AuthResult.Success)
            val session = (result as AuthResult.Success).session
            assertTrue(session is SessionCredential.BasicAuth)
            assertEquals("alice", (session as SessionCredential.BasicAuth).username)

            // 认证流程：OPTIONS（探测 charset）→ PROPFIND（受保护操作验证凭据）
            val options = server.takeRequest()
            assertEquals("OPTIONS", options.method)
            val propfind = server.takeRequest()
            assertEquals("PROPFIND", propfind.method)
            assertEquals("0", propfind.getHeader("Depth"))
            // Authorization: Basic base64("alice:pw-123" ISO-8859-1，无 charset 声明时默认)
            val decoded = decodeBasic(propfind.getHeader("Authorization"))
            assertEquals("alice:pw-123", decoded)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `wrong password fails PROPFIND and returns AuthFailed`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"dav\""))
            server.enqueue(MockResponse().setResponseCode(401)) // PROPFIND 401 = 凭据错误

            val result = provider(server).authenticate(Credentials.BasicAuth("alice", "wrong"))

            assertTrue(result is AuthResult.Failure)
            val error = (result as AuthResult.Failure).error
            assertTrue(error is ProviderException.AuthFailed)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `non-ascii credentials use charset from challenge (UTF-8)`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .addHeader("WWW-Authenticate", "Basic realm=\"dav\", charset=\"UTF-8\"")
            )
            server.enqueue(MockResponse().setResponseCode(207))

            val provider = provider(server)
            provider.authenticate(Credentials.BasicAuth("测试用户", "密码"))
            server.takeRequest() // OPTIONS
            val propfind = server.takeRequest()

            // charset=UTF-8 声明 → Basic 用 UTF-8 编码
            val expected = "Basic " + Base64.getEncoder()
                .encodeToString("测试用户:密码".toByteArray(StandardCharsets.UTF_8))
            assertEquals(expected, propfind.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `testConnection 401 reports AUTH_REQUIRED not protocol mismatch`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            val status: ConnectionStatus = provider(server).testConnection(ConnectionTestRequest())
            assertFalse(status.ok)
            assertEquals(ProviderException.ErrorCode.AUTH_REQUIRED, status.errorCode)
            assertTrue(status.message!!.contains("需要认证"))
        } finally {
            server.shutdown()
        }
    }

    private fun decodeBasic(header: String?): String {
        val encoded = header!!.removePrefix("Basic ")
        return String(Base64.getDecoder().decode(encoded), StandardCharsets.ISO_8859_1)
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
