package com.mediahub.provider.emby.favorite

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.session.EmbySession
import com.mediahub.provider.emby.session.EmbySessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * task-22 / A1：Emby 收藏端到端（MockWebServer）测试。
 *
 * 钉住真机契约（Emby 4.10.2）：
 * - 写：POST/DELETE /Users/{uid}/FavoriteItems/{id}（成功 200 空体）；
 * - 读回：GET /Users/{uid}/Items/{id}?Fields=UserData → UserData.IsFavorite；
 * - /Users/{uid}/Favorites/{id} 真服 404 → **mock 也不为它返回 200**（本测试用精确
 *   path 断言 + 只入队真实请求数来保证：若实现走错路径，takeRequest 断言即失败）。
 */
class EmbyFavoriteProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: TokenStore
    private lateinit var sessionStore: EmbySessionStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tokenStore = TokenStore(FakeSecretStorage())
        sessionStore = EmbySessionStore(FakeSessionStorage())
    }

    @After
    fun tearDown() { server.shutdown() }

    private val mediaServer = MediaServer(
        id = "srv-1", name = "Emby", type = ServerType.EMBY,
        baseUrl = "http://localhost", createdAtEpochMs = 0,
    )

    private fun provider(): EmbyFavoriteProvider {
        val logger = StdoutLogger()
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(OkHttpClient(), logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(
                ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
            ),
            logger = logger,
        )
        return EmbyFavoriteProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(EmbySession("srv-1", "remote-1", "user-1", "Alice"))
    }

    private fun readBackBody(isFavorite: Boolean) =
        """{"Id":"item-9","UserData":{"IsFavorite":$isFavorite}}"""

    // ---- 1：POST 正确 path/头/空 body + 回读 true ----

    @Test
    fun `setFavorite true posts FavoriteItems then returns readback true`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200).setBody(readBackBody(true)))

        val result = provider().setFavorite("item-9", true)

        assertTrue(result)
        val write = server.takeRequest()
        assertEquals("POST", write.method)
        assertEquals("/emby/Users/user-1/FavoriteItems/item-9", write.path)
        assertEquals("tok-1", write.getHeader("X-Emby-Token"))
        assertEquals(0L, write.bodySize)
        // 真服 404 的 legacy 路径绝不能出现在线上请求里
        assertFalse(write.path!!.contains("/Favorites/"))

        val read = server.takeRequest()
        assertEquals("GET", read.method)
        assertEquals("/emby/Users/user-1/Items/item-9?Fields=UserData", read.path)
        assertEquals("tok-1", read.getHeader("X-Emby-Token"))
    }

    // ---- 2：DELETE 正确（无 body）+ 回读 false ----

    @Test
    fun `setFavorite false deletes FavoriteItems then returns readback false`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200).setBody(readBackBody(false)))

        val result = provider().setFavorite("item-9", false)

        assertFalse(result)
        val write = server.takeRequest()
        assertEquals("DELETE", write.method)
        assertEquals("/emby/Users/user-1/FavoriteItems/item-9", write.path)
        assertEquals("tok-1", write.getHeader("X-Emby-Token"))
        assertEquals(0L, write.bodySize)

        assertEquals("/emby/Users/user-1/Items/item-9?Fields=UserData", server.takeRequest().path)
    }

    // ---- 3：服务器收下写请求但忽略 → 不得谎报成功 ----

    @Test
    fun `server ignoring the write yields false from readback`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200).setBody(readBackBody(false)))

        assertFalse(provider().setFavorite("item-9", true))
        assertEquals(2, server.requestCount)
    }

    // ---- 4/5：回读缺 IsFavorite / 缺 UserData → Parse（绝不是 false） ----

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

    @Test
    fun `readback without UserData object is a parse error`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"item-9"}"""))

        assertFailsWithType<ProviderException.Parse> {
            runBlocking { provider().isFavorite("item-9") }
        }
        // 只发了一次回读请求，没有 fallback 猜测
        assertEquals(1, server.requestCount)
    }

    // ---- 6：401 → AuthExpired 且不回读 ----

    @Test
    fun `write 401 maps to AuthExpired and skips readback`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))

        val e = assertFailsWithType<ProviderException.AuthExpired> {
            runBlocking { provider().setFavorite("item-9", true) }
        }
        assertEquals(ProviderException.ErrorCode.AUTH_EXPIRED, e.code)
        assertEquals("写失败后不得继续回读", 1, server.requestCount)
    }

    // ---- 7：404 → NotFound（端点/条目不存在，不重试 legacy 路径） ----

    @Test
    fun `write 404 maps to NotFound`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(404))

        val e = assertFailsWithType<ProviderException.NotFound> {
            runBlocking { provider().setFavorite("item-9", true) }
        }
        assertEquals(ProviderException.ErrorCode.NOT_FOUND, e.code)
        assertEquals(1, server.requestCount)
    }

    // ---- 8：写成功但回读 500 → Http(500, GET) ----

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

    // ---- 9：isFavorite 单请求读回 ----

    @Test
    fun `isFavorite returns server state from single readback request`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody(readBackBody(true)))

        assertTrue(provider().isFavorite("item-9"))
        assertEquals("/emby/Users/user-1/Items/item-9?Fields=UserData", server.takeRequest().path)
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

    // ---- 辅助：类型断言（JUnit4 无 assertThrows 的 Kotlin 便捷写法） ----

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

    private class FakeSessionStorage : EmbySessionStore.Storage {
        private val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }
}
