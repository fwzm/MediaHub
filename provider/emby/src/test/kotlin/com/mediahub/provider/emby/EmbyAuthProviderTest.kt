package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSessionErrorKind
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.auth.EmbyAuthProvider
import com.mediahub.provider.emby.session.EmbySession
import com.mediahub.provider.emby.session.EmbySessionStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 1A 认证测试（MockWebServer，不连真实服务器）。
 * 覆盖：登录、关键字段校验、恢复（含服务器身份校验）、失效策略、登出、密码不落库。
 */
class EmbyAuthProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStorage: RecordingSecretStorage
    private lateinit var sessionStorage: RecordingSessionStorage
    private lateinit var tokenStore: TokenStore
    private lateinit var sessionStore: EmbySessionStore

    private val REMOTE_SERVER_ID = "emby-remote-1"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStorage = RecordingSecretStorage()
        sessionStorage = RecordingSessionStorage()
        tokenStore = TokenStore(tokenStorage)
        sessionStore = EmbySessionStore(sessionStorage)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val mediaServer = MediaServer(
        id = "srv-local-1",
        name = "我的Emby",
        type = ServerType.EMBY,
        baseUrl = "http://localhost",
        createdAtEpochMs = 0,
    )

    private fun provider(timeoutMs: Long = 5_000): EmbyAuthProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "test-device-1", "0.1.0")
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return EmbyAuthProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    // ---- 1. 正确登录 ----

    @Test
    fun `login success saves token and session`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"User":{"Id":"user-1","Name":"Alice"},"AccessToken":"tok-abc","ServerId":"emby-remote-1"}"""
            )
        )
        val result = provider().authenticate(Credentials.UsernamePassword("alice", "pw-123"))
        assertTrue("result=$result", result is AuthResult.Success)

        val request = server.takeRequest()
        assertEquals("/emby/Users/AuthenticateByName", request.path)
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue("body=$body", body.contains("\"Username\":\"alice\""))
        assertTrue("body=$body", body.contains("\"Pw\":\"pw-123\""))
        // 官方 schema 前缀（review #3）：Emby Client="MediaHub"
        val authHeader = request.getHeader("X-Emby-Authorization")
        assertNotNull(authHeader)
        assertTrue(authHeader!!.startsWith("Emby Client=\"MediaHub\""))

        val tokens = tokenStore.readTokens("srv-local-1")
        assertEquals("tok-abc", tokens?.accessToken)
        val session = sessionStore.read("srv-local-1")
        assertEquals(
            EmbySession("srv-local-1", "emby-remote-1", "user-1", "Alice"),
            session,
        )
    }

    // ---- 2. 401 登录失败 ----

    @Test
    fun `login 401 returns failure and saves nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = provider().authenticate(Credentials.UsernamePassword("alice", "wrong"))
        assertTrue(result is AuthResult.Failure)
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    // ---- 3/4/5. 无效响应 ----

    @Test
    fun `login malformed json fails and saves nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))
        assertTrue(provider().authenticate(Credentials.UsernamePassword("alice", "pw")) is AuthResult.Failure)
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    @Test
    fun `login with blank access token fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"User":{"Id":"user-1","Name":"Alice"},"AccessToken":"","ServerId":"emby-remote-1"}"""
        ))
        assertTrue(provider().authenticate(Credentials.UsernamePassword("alice", "pw")) is AuthResult.Failure)
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    @Test
    fun `login with blank user id fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"User":{"Name":"Alice"},"AccessToken":"tok-1","ServerId":"emby-remote-1"}"""
        ))
        assertTrue(provider().authenticate(Credentials.UsernamePassword("alice", "pw")) is AuthResult.Failure)
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    // ---- 6. 恢复成功（先校验服务器身份，再发认证请求） ----

    @Test
    fun `restore validates session successfully`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"emby-remote-1","ServerName":"E","Version":"1"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"user-1","Name":"Alice"}"""))
        val state = provider().restoreSession()
        assertTrue("state=$state", state is AuthSessionState.Authenticated)
        assertEquals("Alice", (state as AuthSessionState.Authenticated).user.displayName)
        // 第一个请求是无 Token 的 SystemInfo（服务器身份校验）
        val infoReq = server.takeRequest()
        assertEquals("/emby/System/Info/Public", infoReq.path)
        assertNull(infoReq.getHeader("X-Emby-Token"))
    }

    // ---- 7. 401 清会话 ----

    @Test
    fun `restore 401 clears token and session`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"emby-remote-1"}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        val state = provider().restoreSession()
        assertTrue(state is AuthSessionState.Error)
        assertEquals(AuthSessionErrorKind.SESSION_EXPIRED, (state as AuthSessionState.Error).kind)
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    // ---- 8. 网络问题保留会话 ----

    @Test
    fun `restore timeout keeps token and session`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val state = provider(timeoutMs = 300).restoreSession()
        assertTrue(state is AuthSessionState.Error)
        assertEquals(AuthSessionErrorKind.NETWORK_TIMEOUT, (state as AuthSessionState.Error).kind)
        assertEquals("tok-abc", tokenStore.readTokens("srv-local-1")?.accessToken)
        assertNotNull(sessionStore.read("srv-local-1"))
    }

    // ---- 9. 403 保留会话（review #4：403 ≠ 认证失效） ----

    @Test
    fun `restore 403 keeps token and session`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"emby-remote-1"}"""))
        server.enqueue(MockResponse().setResponseCode(403))
        val state = provider().restoreSession()
        assertTrue(state is AuthSessionState.Error)
        assertEquals(AuthSessionErrorKind.FORBIDDEN, (state as AuthSessionState.Error).kind)
        assertEquals("tok-abc", tokenStore.readTokens("srv-local-1")?.accessToken)
        assertNotNull(sessionStore.read("srv-local-1"))
    }

    // ---- 10. malformed 响应保留会话（review #4：协议异常 ≠ 认证失效） ----

    @Test
    fun `restore malformed response keeps token and session`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"emby-remote-1"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>error page</html>"))
        val state = provider().restoreSession()
        assertTrue(state is AuthSessionState.Error)
        assertEquals(AuthSessionErrorKind.INVALID_RESPONSE, (state as AuthSessionState.Error).kind)
        assertEquals("tok-abc", tokenStore.readTokens("srv-local-1")?.accessToken)
        assertNotNull(sessionStore.read("srv-local-1"))
    }

    // ---- 11. 服务器身份变更：绝不发送 Token（review #2） ----

    @Test
    fun `restore with server mismatch never sends token`() = runBlocking {
        seedSession("tok-abc")
        // 当前服务器返回不同的 ServerId（SERVER_B）
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"emby-remote-OTHER","ServerName":"B"}"""))
        val state = provider().restoreSession()
        assertTrue(state is AuthSessionState.Error)
        assertEquals(AuthSessionErrorKind.SERVER_MISMATCH, (state as AuthSessionState.Error).kind)

        // 只有 SystemInfo 一个请求；SERVER_B 从未收到 X-Emby-Token
        val request = server.takeRequest()
        assertEquals("/emby/System/Info/Public", request.path)
        assertNull(request.getHeader("X-Emby-Token"))
        // 没有第二个请求（没有 /Users/Me）
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        // 会话保留（用户可重新登录）
        assertNotNull(sessionStore.read("srv-local-1"))
    }

    // ---- 12. authenticated request 带 X-Emby-Token ----

    @Test
    fun `authenticated request carries x-emby-token`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"emby-remote-1"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"user-1","Name":"Alice"}"""))
        provider().restoreSession()
        server.takeRequest() // SystemInfo
        val me = server.takeRequest()
        assertEquals("/emby/Users/Me", me.path)
        assertEquals("tok-abc", me.getHeader("X-Emby-Token"))
    }

    // ---- 13. logout（先校验服务器身份） ----

    @Test
    fun `logout success calls sessions logout and clears local`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"emby-remote-1"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        provider().logout()
        server.takeRequest() // SystemInfo
        val logoutReq = server.takeRequest()
        assertEquals("/emby/Sessions/Logout", logoutReq.path)
        assertEquals("POST", logoutReq.method)
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    @Test
    fun `logout with mismatched server skips remote logout but clears local`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"emby-remote-OTHER"}"""))
        provider().logout()
        // 身份不一致 → 不发 /Sessions/Logout（不把旧 Token 发给错误服务器）
        val request = server.takeRequest()
        assertEquals("/emby/System/Info/Public", request.path)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    @Test
    fun `logout network failure still clears local`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        provider(timeoutMs = 300).logout()
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    // ---- 14. password 不进入任何持久化 ----

    @Test
    fun `password never stored in any persistence`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"User":{"Id":"user-1","Name":"Alice"},"AccessToken":"tok-abc","ServerId":"emby-remote-1"}"""
            )
        )
        provider().authenticate(Credentials.UsernamePassword("alice", "super-secret-pw-xyz"))
        val allValues = tokenStorage.allValues() + sessionStorage.allValues()
        assertTrue("password leaked into storage: $allValues", allValues.none { it.contains("super-secret-pw-xyz") })
    }

    private suspend fun seedSession(accessToken: String) {
        tokenStore.saveTokens("srv-local-1", com.mediahub.core.security.StoredToken(accessToken = accessToken))
        sessionStore.save(
            EmbySession("srv-local-1", REMOTE_SERVER_ID, "user-1", "Alice")
        )
    }

    private class RecordingSecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
        fun allValues(): List<String> = map.values.toList()
    }

    private class RecordingSessionStorage : EmbySessionStore.Storage {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        fun allValues(): List<String> = map.values.toList()
    }
}
