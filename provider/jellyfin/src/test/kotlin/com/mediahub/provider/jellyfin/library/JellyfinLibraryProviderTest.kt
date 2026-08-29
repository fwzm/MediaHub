package com.mediahub.provider.jellyfin.library

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.LibraryType
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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Jellyfin 媒体库浏览（Phase 1G-B）MockWebServer 测试：
 * Views 顶层 / ParentId browse **无 Recursive** / 季集类型锁定 / 分页数学 / 错误与取消映射。
 */
class JellyfinLibraryProviderTest {

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

    private fun provider(client: OkHttpClient = OkHttpClient()): JellyfinLibraryProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        val api = JellyfinApiClient(
            endpointResolver = JellyfinEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = JellyfinAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return JellyfinLibraryProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(
            JellyfinSession("srv-1", "remote-1", "user-1", "Alice")
        )
    }

    // ---- 1：顶层 Views → MediaLibrary ----

    @Test
    fun `library root maps views with collection type and image url`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[
                    {"Id":"v1","Name":"电影","CollectionType":"movies","Type":"CollectionFolder",
                     "IsFolder":true,"ImageTags":{"Primary":"tag-p"}},
                    {"Id":"v2","Name":"剧集","CollectionType":"tvshows","Type":"CollectionFolder",
                     "IsFolder":true}
                ],"TotalRecordCount":2}"""
            )
        )

        val libraries = provider().getLibraries()

        assertEquals(2, libraries.size)
        assertEquals(LibraryType.MOVIES, libraries[0].type)
        assertEquals("srv-1", libraries[0].serverId)
        assertTrue(libraries[0].imageUrl!!.contains("/Items/v1/Images/Primary"))
        assertEquals(LibraryType.TV_SHOWS, libraries[1].type)
        assertNull(libraries[1].imageUrl)

        val request = server.takeRequest()
        assertEquals("/Users/user-1/Views", request.requestUrl!!.encodedPath)
        // Jellyfin 标准 Authorization：Token 内嵌，绝不使用 X-Emby-Token legacy 头
        assertNull(request.getHeader("X-Emby-Token"))
        assertTrue(request.getHeader("Authorization")!!.contains("Token=\"tok-1\""))
    }

    // ---- 2：browse——ParentId 分页 + SortBy=SortName + **无 Recursive** ----

    @Test
    fun `browse builds parent scoped stable page request without recursive`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[
                    {"Id":"m1","Name":"Fargo","Type":"Movie","ProductionYear":2014,
                     "ProviderIds":{"Tmdb":"550"},
                     "ImageTags":{"Primary":"tag-p"}}
                ],"TotalRecordCount":1}"""
            )
        )

        val result = provider().getItems("lib-1", PageRequest(offset = 20, limit = 30))

        assertEquals(1, result.items.size)
        assertEquals("550", result.items[0].externalIds?.tmdb)
        assertTrue(result.items[0].posterUrl!!.contains("/Items/m1/Images/Primary"))

        val url = server.takeRequest().requestUrl!!
        assertEquals("/Users/user-1/Items", url.encodedPath)
        assertEquals("lib-1", url.queryParameter("ParentId"))
        assertEquals("20", url.queryParameter("StartIndex"))
        assertEquals("30", url.queryParameter("Limit"))
        assertEquals("SortName", url.queryParameter("SortBy"))
        assertEquals("Ascending", url.queryParameter("SortOrder"))
        assertNull("浏览禁止 Recursive（ADR-039 红线）", url.queryParameter("Recursive"))
        assertTrue(url.queryParameter("Fields")!!.contains("ProviderIds"))
        assertNull(url.queryParameter("api_key"))
    }

    // ---- 3：分页数学 ----

    @Test
    fun `paging math mirrors total record count`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[{"Id":"m1","Name":"A","Type":"Movie"}],"TotalRecordCount":67}"""
            )
        )

        val result = provider().getItems("lib-1", PageRequest(offset = 60, limit = 20))

        assertEquals(67, result.totalCount)
        assertTrue(result.hasMore)
        assertEquals(61, result.nextOffset)
    }

    // ---- 4：季/集——IncludeItemTypes 锁类型 + IndexNumber 排序 ----

    @Test
    fun `seasons and episodes lock item type with index sort`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[
                    {"Id":"s1","Name":"第1季","Type":"Season","IndexNumber":1,"SeriesId":"series-1"}
                ],"TotalRecordCount":1}"""
            )
        )
        val seasons = provider().getSeasons("series-1")
        assertEquals(1, seasons.size)
        assertEquals("第1季", seasons[0].name)
        assertEquals(1, seasons[0].seasonNumber)
        assertEquals("series-1", seasons[0].seriesId)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[
                    {"Id":"e1","Name":"鳄鱼的困境","Type":"Episode","IndexNumber":1,
                     "ParentIndexNumber":1,"SeriesId":"series-1","SeasonId":"season-1"}
                ],"TotalRecordCount":1}"""
            )
        )
        val episodes = provider().getEpisodes("season-1")
        assertEquals(1, episodes.size)
        assertEquals("鳄鱼的困境", episodes[0].name)
        assertEquals(1, episodes[0].episodeNumber)
        assertEquals("season-1", episodes[0].seasonId)

        val seasonsRequest = server.takeRequest()
        assertEquals("series-1", seasonsRequest.requestUrl!!.queryParameter("ParentId"))
        assertEquals("Season", seasonsRequest.requestUrl!!.queryParameter("IncludeItemTypes"))
        assertEquals("IndexNumber", seasonsRequest.requestUrl!!.queryParameter("SortBy"))
        assertNull(seasonsRequest.requestUrl!!.queryParameter("Recursive"))

        val episodesRequest = server.takeRequest()
        assertEquals("season-1", episodesRequest.requestUrl!!.queryParameter("ParentId"))
        assertEquals("Episode", episodesRequest.requestUrl!!.queryParameter("IncludeItemTypes"))
    }

    // ---- 5：错误映射 ----

    @Test
    fun `401 maps to auth expired`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            provider().getItems("lib-1", PageRequest())
            fail("必须抛 AuthExpired")
        } catch (e: ProviderException.AuthExpired) {
            assertEquals("srv-1", e.serverId)
        }
    }

    @Test
    fun `404 maps to not found`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            provider().getItems("lib-1", PageRequest())
            fail("必须抛 NotFound")
        } catch (_: ProviderException.NotFound) {
        }
    }

    @Test
    fun `cancellation propagates unchanged`() = runBlocking {
        seedSession()
        val cancellingClient = OkHttpClient.Builder()
            .addInterceptor { throw CancellationException("scope cancelled") }
            .build()
        val thrown = try {
            provider(cancellingClient).getItems("lib-1", PageRequest())
            null
        } catch (e: CancellationException) {
            e
        } catch (e: Throwable) {
            e
        }
        assertTrue("取消必须穿透（实际：$thrown）", thrown is CancellationException)
    }

    @Test
    fun `network failure maps to network error`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try {
            provider().getItems("lib-1", PageRequest())
            fail("必须抛 Network")
        } catch (_: ProviderException.Network) {
        }
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
