package com.mediahub.provider.jellyfin.auth

import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSessionErrorKind
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.api.JellyfinAuthorizationHeaderBuilder
import com.mediahub.provider.jellyfin.api.JellyfinEndpointResolver
import com.mediahub.provider.jellyfin.session.JellyfinSession
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Jellyfin 认证（Phase 1G-A / ADR-039）MockWebServer 测试：
 * 标准 Authorization 头 / Token 不进 URL / 登录成功与失败映射 / 恢复（防串服 + 仅 401 清、
 * 网络保留）/ 登出（身份一致才发远端，取消下本地清理仍必达）。
 */
class JellyfinAuthProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStorage: FakeSecretStorage
    private lateinit var sessionStorage: FakeSessionStorage
    private lateinit var tokenStore: TokenStore
    private lateinit var sessionStore: JellyfinSessionStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tokenStorage = FakeSecretStorage()
        sessionStorage = FakeSessionStorage()
        tokenStore = TokenStore(tokenStorage)
        sessionStore = JellyfinSessionStore(sessionStorage)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val mediaServer = MediaServer(
        id = "srv-1", name = "Jellyfin", type = ServerType.JELLYFIN,
        baseUrl = "http://localhost", createdAtEpochMs = 0,
    )

    private fun provider(client: OkHttpClient = OkHttpClient()): JellyfinAuthProvider {
        val logger = StdoutLogger()
        val identity = com.mediahub.core.common.ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        val api = JellyfinApiClient(
            endpointResolver = JellyfinEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = JellyfinAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return JellyfinAuthProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession(remoteId: String = "remote-1") {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(
            JellyfinSession(
                localServerId = "srv-1", remoteServerId = remoteId,
                userId = "user-1", userName = "Alice",
            )
        )
    }

    private fun loginBody() =
        """{"AccessToken":"tok-1","ServerId":"remote-1","User":{"Id":"user-1","Name":"Alice"}}"""

    // ---- 1：登录成功——标准 Authorization 头（无 Token）、Token/body 只走各自合法位置 ----

    @Test
    fun `login success persists token and session with standard authorization header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginBody()))

        val result = provider().authenticate(Credentials.UsernamePassword("alice", "pw-秘密"))

        assertTrue(result is AuthResult.Success)
        assertEquals("tok-1", tokenStore.readTokens("srv-1")?.accessToken)
        assertEquals("user-1", sessionStore.read("srv-1")?.userId)

        val request = server.takeRequest()
        assertEquals("/Users/AuthenticateByName", request.requestUrl!!.encodedPath)
        assertEquals(
            "MediaBrowser Client=\"MediaHub\", Device=\"Android\", DeviceId=\"dev-1\", Version=\"0.1.0\"",
            request.getHeader("Authorization"),
        )
        // 密码只在 body；Token 不进 URL
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"Pw\":\"pw-秘密\""))
        assertFalse(request.requestUrl!!.toString().contains("tok"))
        assertNull(request.requestUrl!!.queryParameter("api_key"))
    }

    // ---- 2：登录失败映射；关键字段缺失不保存半成品 ----

    @Test
    fun `login 401 maps to auth failed without persisting anything`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = provider().authenticate(Credentials.UsernamePassword("alice", "bad"))

        assertTrue(result is AuthResult.Failure)
        assertNull(tokenStore.readTokens("srv-1"))
        assertNull(sessionStore.read("srv-1"))
    }

    @Test
    fun `login 403 is not reported as wrong password`() = runBlocking {
        // ADR-039 review 修正：403 = 访问策略拒绝，保留 HTTP 语义，禁止谎报凭据错误
        server.enqueue(MockResponse().setResponseCode(403))

        val result = provider().authenticate(Credentials.UsernamePassword("alice", "pw"))

        assertTrue(result is AuthResult.Failure)
        val error = (result as AuthResult.Failure).error
        assertTrue("403 不得映射为凭据错误（实际：$error）", error !is com.mediahub.provider.api.ProviderException.AuthFailed)
        assertTrue(error is com.mediahub.provider.api.ProviderException.Http)
        assertEquals(403, (error as com.mediahub.provider.api.ProviderException.Http).statusCode)
        assertNull(tokenStore.readTokens("srv-1"))
    }

    @Test
    fun `login malformed response missing token maps to parse without persisting`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"User":{"Id":"user-1"}}""")
        )

        val result = provider().authenticate(Credentials.UsernamePassword("alice", "pw"))

        assertTrue(result is AuthResult.Failure)
        assertNull(tokenStore.readTokens("srv-1"))
        assertNull(sessionStore.read("srv-1"))
        assertEquals("无半成品后无后续调用", 1, server.requestCount)
    }

    // ---- 3：恢复成功——先无 Token 身份校验，再带 Token 认证请求 ----

    @Test
    fun `restore success verifies server identity then current user`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"remote-1","Version":"10.9.0"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"user-1","Name":"Alice"}"""))

        val state = provider().restoreSession()

        assertTrue(state is AuthSessionState.Authenticated)
        val identity = server.takeRequest()
        assertEquals("/System/Info/Public", identity.requestUrl!!.encodedPath)
        assertFalse(
            "身份校验请求绝不能携带 Token",
            identity.getHeader("Authorization")!!.contains("Token="),
        )
        val authorized = server.takeRequest()
        assertEquals("/Users/user-1", authorized.requestUrl!!.encodedPath)
        assertTrue(authorized.getHeader("Authorization")!!.endsWith("Token=\"tok-1\""))
        assertFalse(authorized.requestUrl!!.toString().contains("tok-1"))
    }

    // ---- 4：服务器身份变更 → SERVER_MISMATCH，绝不发送 Token ----

    @Test
    fun `restore with changed server identity fails without sending token`() = runBlocking {
        seedSession(remoteId = "remote-1")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"other-server","Version":"10.9.0"}"""))

        val state = provider().restoreSession()

        assertTrue(state is AuthSessionState.Error)
        assertEquals(AuthSessionErrorKind.SERVER_MISMATCH, (state as AuthSessionState.Error).kind)
        assertEquals(1, server.requestCount) // 身份不一致即终止，不发生认证请求
        assertNotNull(tokenStore.readTokens("srv-1")) // 身份不符 ≠ 凭据失效，保留本地
    }

    // ---- 5：401/403 均清本地会话（contract §2.3 冻结文本，ADR-039 有意分歧于 Emby） ----

    @Test
    fun `restore 401 clears local token and session`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"remote-1","Version":"10.9.0"}"""))
        server.enqueue(MockResponse().setResponseCode(401))

        val state = provider().restoreSession()

        assertTrue(state is AuthSessionState.Error)
        assertEquals(AuthSessionErrorKind.SESSION_EXPIRED, (state as AuthSessionState.Error).kind)
        assertNull(tokenStore.readTokens("srv-1"))
        assertNull(sessionStore.read("srv-1"))
    }

    @Test
    fun `restore 403 preserves token and session and returns forbidden`() = runBlocking {
        // ADR-039 review 修正：403 可能是 remote access disabled 等访问策略拒绝，
        // 不是 token 失效——清会话会误删仍有效的凭据
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"remote-1","Version":"10.9.0"}"""))
        server.enqueue(MockResponse().setResponseCode(403))

        val state = provider().restoreSession()

        assertTrue(state is AuthSessionState.Error)
        assertEquals(AuthSessionErrorKind.FORBIDDEN, (state as AuthSessionState.Error).kind)
        assertEquals("tok-1", tokenStore.readTokens("srv-1")?.accessToken)
        assertNotNull(sessionStore.read("srv-1"))
    }

    // ---- 6：网络失败保留本地会话（不误判凭据失效） ----

    @Test
    fun `network failure during restore preserves local session`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val state = provider().restoreSession()

        assertTrue(state is AuthSessionState.Error)
        assertEquals(
            AuthSessionErrorKind.NETWORK_UNAVAILABLE,
            (state as AuthSessionState.Error).kind,
        )
        assertEquals("tok-1", tokenStore.readTokens("srv-1")?.accessToken)
        assertNotNull(sessionStore.read("srv-1"))
    }

    // ---- 7：登出——身份一致才发远端 best-effort，本地清理权威 ----

    @Test
    fun `logout with matching identity calls remote then clears local`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"remote-1","Version":"10.9.0"}"""))
        server.enqueue(MockResponse().setResponseCode(204))

        provider().logout()

        assertEquals("/System/Info/Public", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/Sessions/Logout", server.takeRequest().requestUrl!!.encodedPath)
        assertNull(tokenStore.readTokens("srv-1"))
        assertNull(sessionStore.read("srv-1"))
    }

    @Test
    fun `logout with mismatched identity skips remote but still clears local`() = runBlocking {
        seedSession(remoteId = "remote-1")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"other-server","Version":"10.9.0"}"""))

        provider().logout()

        assertEquals("身份不符绝不发送旧 Token", 1, server.requestCount)
        assertNull(tokenStore.readTokens("srv-1"))
        assertNull(sessionStore.read("srv-1"))
    }

    // ---- 8：取消必须原样穿透（取消红线） ----

    @Test
    fun `cancellation during login propagates unchanged`() = runBlocking {
        val cancelled = CancellationException("scope cancelled")
        val cancellingClient = OkHttpClient.Builder()
            .addInterceptor { throw cancelled }
            .build()
        val thrown = try {
            provider(cancellingClient).authenticate(Credentials.UsernamePassword("a", "b"))
            null
        } catch (e: CancellationException) {
            e
        } catch (e: Throwable) {
            e
        }
        assertTrue(
            "取消必须穿透，不得折叠为业务异常（实际：$thrown）",
            thrown is CancellationException,
        )
    }

    // ---- 凭据生命周期一致性：登录持久化取消下不留半成品（ADR-039 review hardening） ----

    @Test
    fun `login cancellation during session save leaves no orphan token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginBody()))
        sessionStorage.failPutWithCancellation = true

        val thrown = try {
            provider().authenticate(Credentials.UsernamePassword("alice", "pw"))
            null
        } catch (e: CancellationException) {
            e
        } catch (e: Throwable) {
            e
        }

        assertTrue("取消必须穿透（实际：$thrown）", thrown is CancellationException)
        assertNull("Token 不得残留", tokenStore.readTokens("srv-1"))
        assertNull(sessionStore.read("srv-1"))
    }

    @Test
    fun `login cancellation at token save leaves no partial credentials`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(loginBody()))
        tokenStorage.failPutWithCancellation = true

        val thrown = try {
            provider().authenticate(Credentials.UsernamePassword("alice", "pw"))
            null
        } catch (e: CancellationException) {
            e
        } catch (e: Throwable) {
            e
        }

        assertTrue("取消必须穿透（实际：$thrown）", thrown is CancellationException)
        assertNull(tokenStore.readTokens("srv-1"))
        assertNull(sessionStore.read("srv-1"))
    }

    @Test
    fun `anonymous identity probe 401 does not report session expired`() = runBlocking {
        // 探针请求未携带存储 Token，其 401 不能证明 AccessToken 失效
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))

        val state = provider().restoreSession()

        assertTrue(state is AuthSessionState.Error)
        val kind = (state as AuthSessionState.Error).kind
        assertTrue("不得误报 SESSION_EXPIRED（实际：$kind）", kind != AuthSessionErrorKind.SESSION_EXPIRED)
        assertEquals("tok-1", tokenStore.readTokens("srv-1")?.accessToken)
        assertNotNull(sessionStore.read("srv-1"))
    }

    // ---- ADR-039 review 修正：AuthenticationResult 只接受顶层 ServerId（协议证据先行，无猜测 fallback） ----

    @Test
    fun `authentication result without top level server id is rejected`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"AccessToken":"tok-1","ServerInfo":{"Id":"remote-1"},"User":{"Id":"user-1","Name":"Alice"}}""")
        )

        val result = provider().authenticate(Credentials.UsernamePassword("alice", "pw"))

        assertTrue(result is AuthResult.Failure)
        assertNull(tokenStore.readTokens("srv-1"))
        assertNull(sessionStore.read("srv-1"))
    }

    // ---- 取消红线：restore 身份探针 / logout 均不得吞取消；logout 取消下凭据仍清 ----

    private fun cancellingClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { throw CancellationException("scope cancelled") }
        .build()

    @Test
    fun `restore identity probe cancellation propagates and preserves session`() = runBlocking {
        seedSession()
        val thrown = try {
            provider(cancellingClient()).restoreSession()
            null
        } catch (e: CancellationException) {
            e
        } catch (e: Throwable) {
            e
        }
        assertTrue("取消必须穿透（实际：$thrown）", thrown is CancellationException)
        assertEquals("探针取消 ≠ 凭据失效", "tok-1", tokenStore.readTokens("srv-1")?.accessToken)
        assertNotNull(sessionStore.read("srv-1"))
    }

    @Test
    fun `logout cancellation propagates but local credentials are still cleared`() = runBlocking {
        seedSession()
        val thrown = try {
            provider(cancellingClient()).logout()
            null
        } catch (e: CancellationException) {
            e
        } catch (e: Throwable) {
            e
        }
        assertTrue("取消必须穿透（实际：$thrown）", thrown is CancellationException)
        assertNull("NonCancellable 保证取消下凭据仍被清理", tokenStore.readTokens("srv-1"))
        assertNull(sessionStore.read("srv-1"))
    }

    private class FakeSecretStorage : SecretStorage {
        var failPutWithCancellation = false
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) {
            if (failPutWithCancellation) throw CancellationException("cancelled mid-write")
            map[key] = value
        }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }

    private class FakeSessionStorage : JellyfinSessionStore.Storage {
        var failPutWithCancellation = false
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            if (failPutWithCancellation) throw CancellationException("cancelled mid-write")
            map[key] = value
        }
        override fun remove(key: String) { map.remove(key) }
    }
}
