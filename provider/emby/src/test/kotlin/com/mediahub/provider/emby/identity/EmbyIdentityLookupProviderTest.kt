package com.mediahub.provider.emby.identity

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.CanonicalKey
import com.mediahub.model.ExternalIdProvider
import com.mediahub.model.MediaType
import com.mediahub.model.MediaServer
import com.mediahub.model.PageRequest
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Emby canonical identity 精确查找（Phase 1F B1 / ADR-038）MockWebServer 测试。 */
class EmbyIdentityLookupProviderTest {

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

    private fun provider(client: OkHttpClient = OkHttpClient()): EmbyIdentityLookupProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return EmbyIdentityLookupProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(EmbySession("srv-1", "remote-1", "user-1", "Alice"))
    }

    private fun movieKeys() = setOf(
        CanonicalKey(MediaType.MOVIE, ExternalIdProvider.TMDB, "275"),
        CanonicalKey(MediaType.MOVIE, ExternalIdProvider.IMDB, "tt0137523"),
    )

    // ---- 1：请求构造（AnyProviderIdEquals wire 形式 / Recursive / 类型锁定 / 分页） ----

    @Test
    fun `lookup builds anyProviderIdEquals request with type lock and pagination`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}""")
        )

        provider().findByCanonicalKeys(movieKeys(), PageRequest(offset = 10, limit = 20))

        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/emby/Users/user-1/Items", url.encodedPath)
        // wire 前缀小写 + "." + value；Set 顺序不进断言（只看成员与分隔）
        assertEquals(
            setOf("tmdb.275", "imdb.tt0137523"),
            url.queryParameter("AnyProviderIdEquals")!!.split(",").toSet(),
        )
        assertEquals("true", url.queryParameter("Recursive"))
        assertEquals("Movie", url.queryParameter("IncludeItemTypes"))
        assertEquals("10", url.queryParameter("StartIndex"))
        assertEquals("20", url.queryParameter("Limit"))
        assertEquals("true", url.queryParameter("EnableUserData"))
        assertTrue(url.queryParameter("Fields")!!.contains("ProviderIds"))
        assertNull("非文本查找不得携带 SearchTerm", url.queryParameter("SearchTerm"))
    }

    // ---- 2：Token 只走 Header，绝不进 URL（ADR-026） ----

    @Test
    fun `token goes to header only never into url`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}""")
        )

        provider().findByCanonicalKeys(movieKeys(), PageRequest())

        val request = server.takeRequest()
        assertEquals("tok-1", request.getHeader("X-Emby-Token"))
        val full = request.requestUrl!!.toString()
        assertFalse("Token 泄漏进 URL", full.contains("tok-1"))
    }

    // ---- 3：响应映射——serverId 保留 + ProviderIds → externalIds 端到端 ----

    @Test
    fun `maps response items with serverId and externalIds end to end`() = runBlocking {
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

        val result = provider().findByCanonicalKeys(movieKeys(), PageRequest())

        val item = result.items.single()
        assertEquals("srv-1", item.serverId)
        assertEquals(MediaType.MOVIE, item.type)
        assertEquals("tt0137523", item.externalIds?.imdb)
        assertEquals("275", item.externalIds?.tmdb)
        assertTrue("查找结果带海报", item.posterUrl!!.contains("/Items/m1/Images/Primary"))
    }

    // ---- 4：分页 hasMore 数学（与浏览/搜索同一语义） ----

    @Test
    fun `hasMore reflects totalRecordCount across pages`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[{"Id":"m1","Name":"A","Type":"Movie"}],"TotalRecordCount":67}"""
            )
        )

        val result = provider().findByCanonicalKeys(movieKeys(), PageRequest(offset = 60, limit = 20))

        assertEquals(67, result.totalCount)
        assertTrue(result.hasMore)
        assertEquals(61, result.nextOffset)
    }

    // ---- 5：契约错误——空 keys / 混合 MediaType 拒绝，不发请求 ----

    @Test
    fun `empty keys rejected without network`() {
        try {
            runBlocking { provider().findByCanonicalKeys(emptySet(), PageRequest()) }
            fail("必须抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `mixed media type keys rejected without network`() {
        val mixed = setOf(
            CanonicalKey(MediaType.MOVIE, ExternalIdProvider.TMDB, "275"),
            CanonicalKey(MediaType.SERIES, ExternalIdProvider.TMDB, "275"),
        )
        try {
            runBlocking { provider().findByCanonicalKeys(mixed, PageRequest()) }
            fail("必须抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, server.requestCount)
    }

    // ---- 评审 hardening 回归：无 IncludeItemTypes wire 值的类型拒绝，不隐式放宽为全类型查询 ----

    @Test
    fun `unsupported media type season rejected without network`() {
        val seasonKeys = setOf(CanonicalKey(MediaType.SEASON, ExternalIdProvider.TVDB, "42"))
        try {
            runBlocking { provider().findByCanonicalKeys(seasonKeys, PageRequest()) }
            fail("必须抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, server.requestCount)
    }

    // ---- 6：401 → AuthExpired；无会话 → AuthRequired ----

    @Test
    fun `401 maps to AuthExpired`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            provider().findByCanonicalKeys(movieKeys(), PageRequest())
            fail("必须抛 AuthExpired")
        } catch (e: ProviderException.AuthExpired) {
            assertEquals("srv-1", e.serverId)
        }
    }

    @Test
    fun `missing session maps to AuthRequired before any request`() = runBlocking {
        try {
            provider().findByCanonicalKeys(movieKeys(), PageRequest())
            fail("必须抛 AuthRequired")
        } catch (_: ProviderException.AuthRequired) {
        }
        assertEquals(0, server.requestCount)
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
