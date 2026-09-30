package com.mediahub.provider.webdav

import com.mediahub.core.security.CredentialVault
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSessionErrorKind
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.Credentials
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** WebDAV 只读认证与会话恢复的状态契约。 */
class WebDavAuthProviderTest {

    private lateinit var webServer: MockWebServer
    private lateinit var stack: WebDavTestStack

    @Before
    fun setUp() {
        webServer = MockWebServer().apply { start() }
        stack = WebDavTestStack(webServer.url("/dav/").toString())
    }

    @After
    fun tearDown() {
        webServer.shutdown()
    }

    private fun enqueuePropfind(code: Int): Unit =
        webServer.enqueue(
            MockResponse().setResponseCode(code)
                .setHeader("Content-Type", "application/xml")
                .setBody(WebDavFixtures.multistatus(WebDavFixtures.collection("/dav/", "根")))
        )

    @Test
    fun `authenticate verifies against root and persists password securely`() = runBlocking {
        enqueuePropfind(207)

        val result = stack.authProvider().authenticate(
            Credentials.UsernamePassword("alice", WebDavTestStack.PASSWORD)
        )

        assertTrue("实际：$result", result is AuthResult.Success)
        assertEquals("alice", (result as AuthResult.Success).user.userId)
        assertEquals(WebDavTestStack.PASSWORD, stack.storedPassword())

        val request = webServer.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.getHeader("Depth"))
        assertEquals(stack.expectedBasicHeader, request.getHeader("Authorization"))
        assertFalse(
            "原始密码不得出现在线级请求中",
            request.headers.toString().contains(WebDavTestStack.PASSWORD) ||
                request.body.readUtf8().contains(WebDavTestStack.PASSWORD),
        )
    }

    @Test
    fun `authenticate accepts webdav credential variant`() = runBlocking {
        enqueuePropfind(207)
        val result = stack.authProvider().authenticate(
            Credentials.WebDav("alice", WebDavTestStack.PASSWORD)
        )
        assertTrue("实际：$result", result is AuthResult.Success)
    }

    @Test
    fun `authenticate with unsupported credential type fails`() = runBlocking {
        val result = stack.authProvider().authenticate(Credentials.BearerToken("tok"))
        assertTrue(result is AuthResult.Failure)
        assertEquals("不得发出请求", 0, webServer.requestCount)
    }

    @Test
    fun `authenticate 401 maps to auth failure and stores nothing`() = runBlocking {
        enqueuePropfind(401)
        val result = stack.authProvider().authenticate(
            Credentials.UsernamePassword("alice", "wrong")
        )
        assertTrue("实际：$result", result is AuthResult.Failure)
        assertNull("认证失败不得保存凭据", stack.storedPassword())
    }

    @Test
    fun `restore session without password is signed out and sends nothing`() = runBlocking {
        val state = stack.authProvider().restoreSession()
        assertEquals(AuthSessionState.SignedOut, state)
        assertEquals(0, webServer.requestCount)
    }

    @Test
    fun `restore session success is authenticated`() = runBlocking {
        stack.storePassword()
        enqueuePropfind(207)
        val state = stack.authProvider().restoreSession()
        assertTrue("实际：$state", state is AuthSessionState.Authenticated)
    }

    @Test
    fun `restore session 401 clears credentials`() = runBlocking {
        stack.storePassword()
        enqueuePropfind(401)
        val state = stack.authProvider().restoreSession()
        assertTrue(state is AuthSessionState.Error)
        assertEquals(AuthSessionErrorKind.SESSION_EXPIRED, (state as AuthSessionState.Error).kind)
        assertNull("401 必须销毁凭据", stack.storedPassword())
    }

    @Test
    fun `restore session 403 keeps credentials`() = runBlocking {
        stack.storePassword()
        enqueuePropfind(403)
        val state = stack.authProvider().restoreSession()
        assertTrue(state is AuthSessionState.Error)
        assertEquals(AuthSessionErrorKind.FORBIDDEN, (state as AuthSessionState.Error).kind)
        assertEquals("403 保留会话", WebDavTestStack.PASSWORD, stack.storedPassword())
    }

    @Test
    fun `restore session 404 reports invalid address and keeps credentials`() = runBlocking {
        stack.storePassword()
        enqueuePropfind(404)
        val state = stack.authProvider().restoreSession()
        assertEquals(
            AuthSessionErrorKind.INVALID_RESPONSE,
            (state as AuthSessionState.Error).kind,
        )
        assertEquals(WebDavTestStack.PASSWORD, stack.storedPassword())
    }

    @Test
    fun `restore session 500 reports server error`() = runBlocking {
        stack.storePassword()
        enqueuePropfind(500)
        val state = stack.authProvider().restoreSession()
        assertEquals(AuthSessionErrorKind.SERVER_ERROR, (state as AuthSessionState.Error).kind)
    }

    @Test
    fun `restore session network failure is reported as unavailable`() = runBlocking {
        // 指向一个没有监听者的端口：连接被拒绝 → IOException → NETWORK_UNAVAILABLE。
        val offline = WebDavTestStack("http://127.0.0.1:1/dav/")
        offline.storePassword()
        val state = offline.authProvider().restoreSession()
        assertTrue("实际：$state", state is AuthSessionState.Error)
        assertEquals(
            AuthSessionErrorKind.NETWORK_UNAVAILABLE,
            (state as AuthSessionState.Error).kind,
        )
    }

    @Test
    fun `logout clears stored credentials`() = runBlocking {
        stack.storePassword()
        stack.authProvider().logout()
        assertNull(stack.storedPassword())
    }

    @Test
    fun `malformed multistatus is an invalid response`() = runBlocking {
        stack.storePassword()
        webServer.enqueue(
            MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml")
                .setBody("<D:multistatus xmlns:D=\"DAV:\"><D:response>")
        )
        val state = stack.authProvider().restoreSession()
        assertEquals(
            AuthSessionErrorKind.INVALID_RESPONSE,
            (state as AuthSessionState.Error).kind,
        )
    }

    @Test
    fun `refresh session mirrors restore outcome`() = runBlocking {
        stack.storePassword()
        enqueuePropfind(207)
        val refreshed = stack.authProvider().refreshSession()
        assertTrue("实际：$refreshed", refreshed is AuthResult.Success)
    }

    @Test
    fun `credential vault is the only credential home`() = runBlocking {
        stack.storePassword("secret-1")
        assertEquals("secret-1", stack.vault.read(WebDavTestStack.SERVER_ID, CredentialVault.CredentialKind.PASSWORD))
        // 用户名不进 vault（复用 MediaServer.username，非敏感）。
        assertFalse(stack.vault.contains(WebDavTestStack.SERVER_ID, CredentialVault.CredentialKind.API_KEY))
    }
}
