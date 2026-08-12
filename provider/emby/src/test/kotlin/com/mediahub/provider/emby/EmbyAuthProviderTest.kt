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
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.auth.EmbyAuthErrorKind
import com.mediahub.provider.emby.auth.EmbyAuthProvider
import com.mediahub.provider.emby.auth.EmbyAuthState
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
 */
class EmbyAuthProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStorage: RecordingSecretStorage
    private lateinit var sessionStorage: RecordingSessionStorage
    private lateinit var tokenStore: TokenStore
    private lateinit var sessionStore: EmbySessionStore

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

    private fun provider(
        timeoutMs: Long = 5_000,
    ): EmbyAuthProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity(client = "MediaHub", device = "Android", deviceId = "test-device-1", version = "0.1.0")
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val api = EmbyApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return EmbyAuthProvider(
            server = mediaServer,
            api = api,
            tokenStore = tokenStore,
            sessionStore = sessionStore,
            logger = logger,
        )
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
        assertEquals("/Users/AuthenticateByName", request.path)
        assertEquals("POST", request.method)
        // body 含 Username/Pw（JSON，body 只读一次）
        val body = request.body.readUtf8()
        assertTrue("body=$body", body.contains("\"Username\":\"alice\""))
        assertTrue("body=$body", body.contains("\"Pw\":\"pw-123\""))
        // 客户端身份头存在
        val authHeader = request.getHeader("X-Emby-Authorization")
        assertNotNull(authHeader)
        assertTrue(authHeader!!.startsWith("MediaBrowser Client=\"MediaHub\""))

        // Token 按 localServerId 保存
        val tokens = tokenStore.readTokens("srv-local-1")
        assertEquals("tok-abc", tokens?.accessToken)
        // Session：localServerId / remoteServerId 区分（第 12 项）
        val session = sessionStore.read("srv-local-1")
        assertEquals(
            EmbySession(localServerId = "srv-local-1", remoteServerId = "emby-remote-1", userId = "user-1", userName = "Alice"),
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

    // ---- 3. malformed JSON ----

    @Test
    fun `login malformed json fails and saves nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))
        val result = provider().authenticate(Credentials.UsernamePassword("alice", "pw"))
        assertTrue(result is AuthResult.Failure)
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    // ---- 4. 200 但 AccessToken 空 ----

    @Test
    fun `login with blank access token fails`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"User":{"Id":"user-1","Name":"Alice"},"AccessToken":"","ServerId":"emby-remote-1"}"""
            )
        )
        val result = provider().authenticate(Credentials.UsernamePassword("alice", "pw"))
        assertTrue(result is AuthResult.Failure)
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    // ---- 5. 200 但 User.Id 空 ----

    @Test
    fun `login with blank user id fails`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"User":{"Name":"Alice"},"AccessToken":"tok-1","ServerId":"emby-remote-1"}"""
            )
        )
        val result = provider().authenticate(Credentials.UsernamePassword("alice", "pw"))
        assertTrue(result is AuthResult.Failure)
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    // ---- 6. 重启恢复成功 ----

    @Test
    fun `restore validates session successfully`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"Id":"user-1","Name":"Alice"}""")
        )
        val state = provider().validateSession()
        assertTrue("state=$state", state is EmbyAuthState.Authenticated)
        assertEquals("Alice", (state as EmbyAuthState.Authenticated).user.displayName)
    }

    // ---- 7. restore 401 清会话 ----

    @Test
    fun `restore 401 clears token and session`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setResponseCode(401))
        val state = provider().validateSession()
        assertTrue(state is EmbyAuthState.Error)
        assertEquals(EmbyAuthErrorKind.SESSION_EXPIRED, (state as EmbyAuthState.Error).kind)
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    // ---- 8. restore 网络问题保留会话 ----

    @Test
    fun `restore timeout keeps token and session`() = runBlocking {
        seedSession("tok-abc")
        // 连接建立但不响应 → 读超时（短超时 client）
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val state = provider(timeoutMs = 300).validateSession()
        assertTrue(state is EmbyAuthState.Error)
        assertEquals(EmbyAuthErrorKind.NETWORK_TIMEOUT, (state as EmbyAuthState.Error).kind)
        // 保留会话
        assertEquals("tok-abc", tokenStore.readTokens("srv-local-1")?.accessToken)
        assertNotNull(sessionStore.read("srv-local-1"))
    }

    @Test
    fun `restore server error keeps token and session`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setResponseCode(500))
        val state = provider().validateSession()
        assertTrue(state is EmbyAuthState.Error)
        assertEquals(EmbyAuthErrorKind.SERVER_ERROR, (state as EmbyAuthState.Error).kind)
        assertEquals("tok-abc", tokenStore.readTokens("srv-local-1")?.accessToken)
        assertNotNull(sessionStore.read("srv-local-1"))
    }

    // ---- 9. authenticated request 带 X-Emby-Token ----

    @Test
    fun `authenticated request carries x-emby-token`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"user-1","Name":"Alice"}"""))
        provider().validateSession()
        val request = server.takeRequest()
        assertEquals("/Users/Me", request.path)
        assertEquals("tok-abc", request.getHeader("X-Emby-Token"))
    }

    // ---- 10. logout 成功 ----

    @Test
    fun `logout success calls sessions logout and clears local`() = runBlocking {
        seedSession("tok-abc")
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        provider().logout()
        val request = server.takeRequest()
        assertEquals("/Sessions/Logout", request.path)
        assertEquals("POST", request.method)
        assertNull(tokenStore.readTokens("srv-local-1"))
        assertNull(sessionStore.read("srv-local-1"))
    }

    // ---- 11. logout 网络失败仍清本地 ----

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
            EmbySession(localServerId = "srv-local-1", remoteServerId = "emby-remote-1", userId = "user-1", userName = "Alice")
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
