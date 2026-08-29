package com.mediahub.provider.jellyfin.search

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.api.JellyfinAuthorizationHeaderBuilder
import com.mediahub.provider.jellyfin.api.JellyfinEndpointResolver
import com.mediahub.provider.jellyfin.session.JellyfinSession
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Jellyfin 全局搜索（Phase 1G-B）MockWebServer 测试：
 * SearchTerm 编码 / Recursive=true 搜索契约（与 browse 红线互斥）/ relevance 排序 /
 * ProviderIds 端到端 / 空白短路 / 错误与取消映射。
 */
class JellyfinSearchProviderTest {

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

    private fun provider(client: OkHttpClient = OkHttpClient()): JellyfinSearchProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        val api = JellyfinApiClient(
            endpointResolver = JellyfinEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = JellyfinAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return JellyfinSearchProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(JellyfinSession("srv-1", "remote-1", "user-1", "Alice"))
    }

    // ---- 1：搜索请求契约——SearchTerm 编码 / Recursive=true / 四类锁定 / relevance 排序 ----

    @Test
    fun `search builds recursive searchTerm request with encoding and relevance order`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}"""))

        // 中文 + 空格 + URL 保留字符，验证 HttpUrl builder 全量编码
        provider().search("冰血暴 & dune=2", PageRequest(offset = 30, limit = 20))

        val url = server.takeRequest().requestUrl!!
        assertEquals("/Users/user-1/Items", url.encodedPath)
        assertEquals("冰血暴 & dune=2", url.queryParameter("SearchTerm"))
        assertEquals("true", url.queryParameter("Recursive"))
        assertEquals("Movie,Series,Episode,Video", url.queryParameter("IncludeItemTypes"))
        assertNull("搜索走服务器 relevance，不传 SortBy", url.queryParameter("SortBy"))
        assertEquals("30", url.queryParameter("StartIndex"))
        assertEquals("20", url.queryParameter("Limit"))
        assertTrue(url.queryParameter("Fields")!!.contains("ProviderIds"))
        assertTrue(url.queryParameter("Fields")!!.contains("UserData"))
    }

    // ---- 2：空白 query 短路，不发请求 ----

    @Test
    fun `blank query short circuits without network`() = runBlocking {
        val result = provider().search("   ", PageRequest())

        assertTrue(result.items.isEmpty())
        assertEquals(0, result.totalCount)
        assertFalse(result.hasMore)
        assertNull(result.nextOffset)
        assertEquals(0, server.requestCount)
    }

    // ---- 3：ProviderIds → externalIds 端到端 ----

    @Test
    fun `search response provider ids populate external ids end to end`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[
                    {"Id":"m1","Name":"Fargo","Type":"Movie","ProductionYear":2014,
                     "ProviderIds":{"Imdb":"tt0137523","Tmdb":"275"},
                     "ImageTags":{"Primary":"tag-p"}}
                ],"TotalRecordCount":1}"""
            )
        )

        val result = provider().search("fargo", PageRequest())

        val item = result.items.single()
        assertEquals("srv-1", item.serverId)
        assertEquals("tt0137523", item.externalIds?.imdb)
        assertEquals("275", item.externalIds?.tmdb)
        assertTrue(item.posterUrl!!.contains("/Items/m1/Images/Primary"))
    }

    // ---- 4：分页 hasMore 数学 ----

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
        assertEquals(61, result.nextOffset)
    }

    // ---- 5：错误与取消 ----

    @Test
    fun `401 maps to auth expired`() = runBlocking {
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
    fun `missing session maps to auth required before any request`() = runBlocking {
        try {
            provider().search("fargo", PageRequest())
            fail("必须抛 AuthRequired")
        } catch (_: ProviderException.AuthRequired) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancellation propagates unchanged`() = runBlocking {
        seedSession()
        val cancellingClient = OkHttpClient.Builder()
            .addInterceptor { throw CancellationException("scope cancelled") }
            .build()
        val thrown = try {
            provider(cancellingClient).search("fargo", PageRequest())
            null
        } catch (e: CancellationException) {
            e
        } catch (e: Throwable) {
            e
        }
        assertTrue("取消必须穿透（实际：$thrown）", thrown is CancellationException)
    }

    // ---- 6：Artwork URL 契约（ADR-026/039）：URL 永不含 Token/api_key；反代子路径保留 ----

    @Test
    fun `image url never contains credentials and preserves subpath`() {
        val logger = StdoutLogger()
        val api = JellyfinApiClient(
            endpointResolver = JellyfinEndpointResolver(server.url("/jellyfin").toString().trimEnd('/')),
            apiClient = ApiClient(OkHttpClient(), logger = logger),
            authHeaderBuilder = JellyfinAuthorizationHeaderBuilder(
                ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
            ),
            logger = logger,
        )

        val url = api.imageUrl("m1", com.mediahub.provider.jellyfin.api.JellyfinImageType.PRIMARY, "tag-x", 400)

        assertTrue(url.contains("/jellyfin/Items/m1/Images/Primary"))
        assertTrue(url.contains("tag=tag-x"))
        assertTrue(url.contains("maxWidth=400"))
        assertFalse("Token 不得进 URL", url.contains("tok"))
        assertNull(url.toHttpUrlOrNull()!!.queryParameter("api_key"))
        assertNull(url.toHttpUrlOrNull()!!.queryParameter("X-Emby-Token"))
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
