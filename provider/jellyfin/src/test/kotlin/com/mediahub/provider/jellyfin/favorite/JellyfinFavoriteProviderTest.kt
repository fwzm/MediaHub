package com.mediahub.provider.jellyfin.favorite

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.api.JellyfinAuthorizationHeaderBuilder
import com.mediahub.provider.jellyfin.api.JellyfinEndpointResolver
import com.mediahub.provider.jellyfin.session.JellyfinSession
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * task-22 / A1：Jellyfin 收藏端到端（MockWebServer）测试。
 *
 * 与 Emby 版**契约同形、路由不同**：
 * - 路径**无 /emby 前缀**（ADR-039 endpointResolver 直拼 baseUrl）；
 * - 鉴权只认 `Authorization: MediaBrowser … Token="…"`，不得出现 `X-Emby-Token`（ADR-039）；
 * - 写 POST/DELETE /Users/{uid}/FavoriteItems/{id}，读回 GET /Users/{uid}/Items/{id}?Fields=UserData。
 *
 * 禁止 `/Users/{uid}/Favorites/{id}`：Jellyfin 12.2 真服 404 → 本 mock 不为该路径准备任何响应。
 */
class JellyfinFavoriteProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: TokenStore
    private lateinit var sessionStore: JellyfinSessionStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tokenStore = TokenStore(FakeSecretStorage())
        sessionStore = JellyfinSessionStore(FakeSessionStorage())
    }

    @After
    fun tearDown() { server.shutdown() }

    private val mediaServer = MediaServer(
        id = "srv-1", name = "Jellyfin", type = ServerType.JELLYFIN,
        baseUrl = "http://localhost", createdAtEpochMs = 0,
    )

    private fun provider(): JellyfinFavoriteProvider {
        val logger = StdoutLogger()
        val api = JellyfinApiClient(
            endpointResolver = JellyfinEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(OkHttpClient(), logger = logger),
            authHeaderBuilder = JellyfinAuthorizationHeaderBuilder(
                ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
            ),
            logger = logger,
        )
        return JellyfinFavoriteProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(JellyfinSession("srv-1", "remote-1", "user-1", "Alice"))
    }

    private fun readBackBody(isFavorite: Boolean) =
        """{"Id":"item-9","UserData":{"IsFavorite":$isFavorite}}"""

    // ---- 1：POST 无 /emby 前缀 + MediaBrowser 头 + 空 body + 回读 true ----

    @Test
    fun `setFavorite true posts unprefixed FavoriteItems with MediaBrowser header`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200).setBody(readBackBody(true)))

        assertTrue(provider().setFavorite("item-9", true))

        val write = server.takeRequest()
        assertEquals("POST", write.method)
        assertEquals("/Users/user-1/FavoriteItems/item-9", write.path)
        assertEquals(0L, write.bodySize)
        assertFalse(write.path!!.contains("/emby"))
        assertFalse(write.path!!.contains("/Favorites/"))
        val auth = write.getHeader("Authorization")
        assertNotNull(auth)
        assertTrue(auth!!.startsWith("MediaBrowser "))
        assertTrue(auth.contains("""Token="tok-1""""))
        assertNull(write.getHeader("X-Emby-Token"))

        val read = server.takeRequest()
        assertEquals("GET", read.method)
        assertEquals("/Users/user-1/Items/item-9?Fields=UserData", read.path)
        assertTrue(read.getHeader("Authorization")!!.contains("""Token="tok-1""""))
    }

    // ---- 2：DELETE 正确 + 回读 false ----

    @Test
    fun `setFavorite false deletes FavoriteItems then returns readback false`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200).setBody(readBackBody(false)))

        assertFalse(provider().setFavorite("item-9", false))

        val write = server.takeRequest()
        assertEquals("DELETE", write.method)
        assertEquals("/Users/user-1/FavoriteItems/item-9", write.path)
        assertEquals(0L, write.bodySize)
        assertEquals("/Users/user-1/Items/item-9?Fields=UserData", server.takeRequest().path)
    }

    // ---- 3：服务器忽略写入 → 返回回读的 false，不谎报成功 ----

    @Test
    fun `server ignoring the write yields readback false`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200).setBody(readBackBody(false)))

        assertFalse(provider().setFavorite("item-9", true))
        assertEquals(2, server.requestCount)
    }

    // ---- 4：回读缺 IsFavorite → Parse（不是 false） ----

    @Test
    fun `readback without IsFavorite field is a parse error`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"item-9","UserData":{}}"""))

        val e = assertFailsWithType<ProviderException.Parse> {
            runBlocking { provider().setFavorite("item-9", true) }
        }
        assertEquals(ProviderException.ErrorCode.PARSE, e.code)
    }

    // ---- 5：回读缺 UserData → Parse ----

    @Test
    fun `readback without UserData object is a parse error`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"item-9"}"""))

        assertFailsWithType<ProviderException.Parse> {
            runBlocking { provider().isFavorite("item-9") }
        }
        assertEquals(1, server.requestCount)
    }

    // ---- 6：401 → AuthExpired，且不再回读 ----

    @Test
    fun `write 401 maps to AuthExpired and skips readback`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))

        val e = assertFailsWithType<ProviderException.AuthExpired> {
            runBlocking { provider().setFavorite("item-9", true) }
        }
        assertEquals(ProviderException.ErrorCode.AUTH_EXPIRED, e.code)
        assertEquals(1, server.requestCount)
    }

    // ---- 7：404 → NotFound（未找到条目 / 端点） ----

    @Test
    fun `write 404 maps to NotFound`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(404))

        val e = assertFailsWithType<ProviderException.NotFound> {
            runBlocking { provider().setFavorite("item-9", true) }
        }
        assertEquals(ProviderException.ErrorCode.NOT_FOUND, e.code)
    }

    // ---- 8：回读 500 → Http(500, GET) ----

    @Test
    fun `readback 500 maps to Http with status and method`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(500))

        val e = assertFailsWithType<ProviderException.Http> {
            runBlocking { provider().setFavorite("item-9", true) }
        }
        assertEquals(500, e.statusCode)
        assertEquals("GET", e.method)
    }

    // ---- 9：isFavorite 单请求 ----

    @Test
    fun `isFavorite returns server state from a single readback request`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody(readBackBody(true)))

        assertTrue(provider().isFavorite("item-9"))
        assertEquals(1, server.requestCount)
        assertEquals("/Users/user-1/Items/item-9?Fields=UserData", server.takeRequest().path)
    }

    // ---- 10：无会话 → AuthRequired，0 HTTP ----

    @Test
    fun `setFavorite without session fails with AuthRequired and no HTTP`() = runBlocking {
        val e = assertFailsWithType<ProviderException.AuthRequired> {
            runBlocking { provider().setFavorite("item-9", true) }
        }
        assertEquals(ProviderException.ErrorCode.AUTH_REQUIRED, e.code)
        assertEquals(0, server.requestCount)
    }

    // ---- 11-15：listFavorites（task-25 / A2，现代 /Items 路由） ----

    private fun favoriteListBody(vararg items: Pair<String, Boolean>): String {
        val entries = items.joinToString(",") { (id, fav) ->
            """{"Id":"$id","Name":"条目$id","Type":"Movie","UserData":{"IsFavorite":$fav}}"""
        }
        return """{"Items":[$entries],"TotalRecordCount":${items.size}}"""
    }

    @Test
    fun `listFavorites uses modern Items route with Filters IsFavorite`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody(favoriteListBody("m1" to true, "m2" to true)))

        val items = provider().listFavorites(limit = 50)

        assertEquals(listOf("m1", "m2"), items.map { it.id })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        // 列表读取走 ADR-039 现代路由（与写/回读的 /Users/… 家族不同）
        assertEquals("/Items", request.requestUrl!!.encodedPath)
        val query = request.requestUrl!!
        assertEquals("user-1", query.queryParameter("UserId"))
        assertEquals("IsFavorite", query.queryParameter("Filters"))
        assertEquals("true", query.queryParameter("Recursive"))
        assertEquals("Movie,Series,Episode", query.queryParameter("IncludeItemTypes"))
        assertEquals("true", query.queryParameter("EnableUserData"))
        assertEquals("50", query.queryParameter("Limit"))
        assertFalse(request.path!!.contains("/emby"))
        assertNull(request.getHeader("X-Emby-Token"))
        assertTrue(request.getHeader("Authorization")!!.contains("""Token="tok-1""""))
    }

    @Test
    fun `listFavorites drops items the server did not actually mark favorite`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody(favoriteListBody("m1" to true, "m2" to false)))

        assertEquals(listOf("m1"), provider().listFavorites().map { it.id })
    }

    @Test
    fun `listFavorites returns empty list for empty server page`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}"""))

        assertTrue(provider().listFavorites().isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `listFavorites 401 maps to AuthExpired`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))

        val e = assertFailsWithType<ProviderException.AuthExpired> {
            runBlocking { provider().listFavorites() }
        }
        assertEquals(ProviderException.ErrorCode.AUTH_EXPIRED, e.code)
    }

    @Test
    fun `listFavorites 404 maps to NotFound`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(404))

        val e = assertFailsWithType<ProviderException.NotFound> {
            runBlocking { provider().listFavorites() }
        }
        assertEquals(ProviderException.ErrorCode.NOT_FOUND, e.code)
    }

    inline fun <reified T : Throwable> assertFailsWithType(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("期望 ${T::class.simpleName}，实际 ${e::class.simpleName}: ${e.message}", e)
        }
        throw AssertionError("期望 ${T::class.simpleName}，但没有抛异常")
    }

    private class FakeSecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }

    private class FakeSessionStorage : JellyfinSessionStore.Storage {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }
}
