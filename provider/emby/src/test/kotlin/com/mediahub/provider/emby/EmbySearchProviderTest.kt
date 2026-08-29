package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaType
import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.search.EmbySearchProvider
import com.mediahub.provider.emby.session.EmbySession
import com.mediahub.provider.emby.session.EmbySessionStore
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/** Emby 全库搜索（Phase 1C-1）MockWebServer 测试。 */
class EmbySearchProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStorage: FakeSecretStorage
    private lateinit var sessionStorage: FakeSessionStorage
    private lateinit var tokenStore: TokenStore
    private lateinit var sessionStore: EmbySessionStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tokenStorage = FakeSecretStorage()
        sessionStorage = FakeSessionStorage()
        tokenStore = TokenStore(tokenStorage)
        sessionStore = EmbySessionStore(sessionStorage)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val mediaServer = MediaServer(
        id = "srv-1", name = "Emby", type = ServerType.EMBY,
        baseUrl = "http://localhost", createdAtEpochMs = 0,
    )

    private fun provider(client: OkHttpClient = OkHttpClient()): EmbySearchProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return EmbySearchProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(EmbySession("srv-1", "remote-1", "user-1", "Alice"))
    }

    // ---- 1：请求构造（SearchTerm 编码 / Recursive / IncludeItemTypes / 分页） ----

    @Test
    fun `search builds recursive searchTerm request with encoding and pagination`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}""")
        )

        // 中文 + 空格 + URL 保留字符，验证 HttpUrl builder 全量编码
        provider().search("冰血暴 & dune=2", PageRequest(offset = 30, limit = 20))

        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/emby/Users/user-1/Items", url.encodedPath)
        assertEquals("冰血暴 & dune=2", url.queryParameter("SearchTerm"))
        assertEquals("true", url.queryParameter("Recursive"))
        assertEquals("Movie,Series,Episode,Video", url.queryParameter("IncludeItemTypes"))
        assertEquals("30", url.queryParameter("StartIndex"))
        assertEquals("20", url.queryParameter("Limit"))
        assertEquals("true", url.queryParameter("EnableUserData"))
        assertTrue(url.queryParameter("Fields")!!.contains("PrimaryImageAspectRatio"))
    }

    // ---- 2：Token 只走 Header，绝不进 URL（ADR-026） ----

    @Test
    fun `token goes to header only never into url`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}""")
        )

        provider().search("fargo", PageRequest())

        val request = server.takeRequest()
        assertEquals("tok-1", request.getHeader("X-Emby-Token"))
        val full = request.requestUrl!!.toString()
        assertFalse("Token 泄漏进 URL", full.contains("tok-1"))
        assertFalse(full.contains("api_key", ignoreCase = true))
    }

    // ---- 3：映射 Movie / Series / Episode，serverId 保留 ----

    @Test
    fun `maps movie series episode with serverId preserved`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[
                    {"Id":"m1","Name":"冰血暴","Type":"Movie","ProductionYear":2014,
                     "CommunityRating":8.6,"Container":"mkv",
                     "ImageTags":{"Primary":"tag-p"}},
                    {"Id":"s1","Name":"Fargo","Type":"Series","ProductionYear":2014},
                    {"Id":"e1","Name":"鳄鱼的困境","Type":"Episode",
                     "SeriesId":"s1","ParentIndexNumber":1,"IndexNumber":1}
                ],"TotalRecordCount":3}"""
            )
        )

        val result = provider().search("冰", PageRequest())

        assertEquals(3, result.items.size)
        result.items.forEach { assertEquals("srv-1", it.serverId) }
        val movie = result.items[0]
        assertEquals(MediaType.MOVIE, movie.type)
        assertEquals("冰血暴", movie.title)
        assertEquals(2014, movie.year)
        assertEquals(8.6, movie.communityRating!!, 0.001)
        assertEquals("mkv", movie.container)
        assertTrue("搜索结果带海报", movie.posterUrl!!.contains("/Items/m1/Images/Primary"))
        assertEquals(MediaType.SERIES, result.items[1].type)
        val episode = result.items[2]
        assertEquals(MediaType.EPISODE, episode.type)
        assertEquals("s1", episode.seriesId)
        assertEquals(1, episode.seasonNumber)
        assertEquals(1, episode.episodeNumber)
    }

    // ---- 4：空白 query 短路，不发请求 ----

    @Test
    fun `blank query short circuits without network`() = runBlocking {
        val result = provider().search("   ", PageRequest())

        assertTrue(result.items.isEmpty())
        assertEquals(0, result.totalCount)
        assertFalse(result.hasMore)
        assertNull(result.nextOffset)
        assertEquals(0, server.requestCount)
    }

    // ---- 5：空结果 ----

    @Test
    fun `empty result maps to empty page without more`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}""")
        )

        val result = provider().search("不存在的片名", PageRequest())

        assertTrue(result.items.isEmpty())
        assertEquals(0, result.totalCount)
        assertFalse(result.hasMore)
        assertNull(result.nextOffset)
    }

    // ---- 6：分页 hasMore 数学 ----

    @Test
    fun `hasMore reflects totalRecordCount across pages`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[{"Id":"m1","Name":"A","Type":"Movie"}],"TotalRecordCount":67}"""
            )
        )

        val result = provider().search("a", PageRequest(offset = 60, limit = 20))

        assertEquals(67, result.totalCount)
        assertTrue(result.hasMore)
        // nextOffset = offset + 实际返回条数（与浏览链路同一语义，非 offset+limit）
        assertEquals(61, result.nextOffset)
    }

    @Test
    fun `empty raw search page with larger total terminates without repeating offset`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":67}""")
        )

        val result = provider().search("a", PageRequest(offset = 60, limit = 20))

        assertTrue(result.items.isEmpty())
        assertEquals(67, result.totalCount)
        assertFalse(result.hasMore)
        assertNull(result.nextOffset)
    }

    // ---- 7：401 → AuthExpired ----

    @Test
    fun `401 maps to AuthExpired`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            provider().search("fargo", PageRequest())
            fail("必须抛 AuthExpired")
        } catch (e: ProviderException.AuthExpired) {
            assertEquals("srv-1", e.serverId)
        }
    }

    @Test
    fun `http failure does not expose encoded search term through provider exception`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(500).setBody("server failure"))
        val privateQuery = "冰血暴 & dune=2"

        val thrown = runCatching {
            provider().search(privateQuery, PageRequest())
        }.exceptionOrNull()

        assertTrue(thrown is ProviderException.Http)
        val http = thrown as ProviderException.Http
        assertFalse(http.url.contains(privateQuery))
        assertFalse(http.url.contains("%E5%86%B0", ignoreCase = true))
        assertFalse(http.url.contains("dune", ignoreCase = true))
        assertTrue(http.url.contains("SearchTerm=****", ignoreCase = true))
        assertFalse(http.message.orEmpty().contains("SearchTerm", ignoreCase = true))
        assertFalse(http.message.orEmpty().contains("dune", ignoreCase = true))
        // 服务器实际收到原始 query；测试证明隐私只在异常/日志边界被移除，而不是请求被改坏。
        assertEquals(privateQuery, server.takeRequest().requestUrl!!.queryParameter("SearchTerm"))
    }

    // ---- 8：网络错误 → Network；无会话 → AuthRequired ----

    @Test
    fun `network failure maps to Network error`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        try {
            provider().search("fargo", PageRequest())
            fail("必须抛 Network")
        } catch (_: ProviderException.Network) {
        }
    }

    @Test
    fun `missing session maps to AuthRequired before any request`() = runBlocking {
        try {
            provider().search("fargo", PageRequest())
            fail("必须抛 AuthRequired")
        } catch (_: ProviderException.AuthRequired) {
        }
        assertEquals(0, server.requestCount)
    }

    // ---- 9（评审 P1）：真实取消边界 —— CancellationException 必须穿透 Provider ----

    @Test
    fun `mapError rethrows cancellation exception unchanged`() {
        // kotlinx.coroutines.CancellationException 在 JVM 即本类型的 typealias
        val cancelled = java.util.concurrent.CancellationException("job cancelled")
        val thrown = try {
            com.mediahub.provider.emby.EmbyProviderSupport.mapError("srv-1", cancelled)
            null
        } catch (e: java.util.concurrent.CancellationException) {
            e
        }
        assertTrue("必须原样传播同一实例", thrown === cancelled)
    }

    @Test
    fun `in-flight search cancellation propagates as CancellationException not ProviderException`() =
        runBlocking {
            seedSession()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient()
            val observed = CompletableDeferred<Throwable>()
            val job = launch(Dispatchers.Default) {
                try {
                    provider(client).search("冰血暴", PageRequest())
                } catch (e: Throwable) {
                    observed.complete(e)
                    throw e
                }
            }
            assertNotNull("真实 provider 请求必须已进入网络层", server.takeRequest(2, TimeUnit.SECONDS))

            job.cancel()
            withTimeout(2_000) {
                job.join()
                while (client.dispatcher.runningCallsCount() != 0) delay(10)
            }

            val thrown = observed.await()
            assertTrue(
                "取消必须原样穿透（实际：$thrown）",
                thrown is java.util.concurrent.CancellationException,
            )
            assertFalse("取消不得折叠成业务异常（实际：$thrown）", thrown is ProviderException)
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
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
