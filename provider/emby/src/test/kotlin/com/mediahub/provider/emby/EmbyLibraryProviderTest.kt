package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.LibraryType
import com.mediahub.model.MediaFilter
import com.mediahub.model.MediaListQuery
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaSort
import com.mediahub.model.MediaSortField
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.ServerType
import com.mediahub.model.SortDirection
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.library.EmbyLibraryProvider
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
import org.junit.Before
import org.junit.Test

/** Emby 媒体库浏览（Phase 1B-1）MockWebServer 测试。 */
class EmbyLibraryProviderTest {

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
    fun tearDown() { server.shutdown() }

    private val mediaServer = MediaServer(
        id = "srv-1", name = "Emby", type = ServerType.EMBY,
        baseUrl = "http://localhost", createdAtEpochMs = 0,
    )

    private fun provider(): EmbyLibraryProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        val client = OkHttpClient()
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return EmbyLibraryProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(EmbySession("srv-1", "remote-1", "user-1", "Alice"))
    }

    // ---- 1/2/3：Views 请求 + header + 映射 ----

    @Test
    fun `getLibraries returns mapped MediaLibrary with auth headers`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[{"Id":"lib1","Name":"电影","CollectionType":"movies"},
                    {"Id":"lib2","Name":"剧集","CollectionType":"tvshows"},
                    {"Id":"lib3","Name":"音乐","CollectionType":"music"}]}"""
            )
        )

        val libraries = provider().getLibraries()

        assertEquals(3, libraries.size)
        assertEquals("lib1", libraries[0].id)
        assertEquals(LibraryType.MOVIES, libraries[0].type)
        assertEquals(LibraryType.TV_SHOWS, libraries[1].type)
        assertEquals(LibraryType.MUSIC, libraries[2].type)

        val request = server.takeRequest()
        assertEquals("/emby/Users/user-1/Views", request.path)
        assertEquals("tok-1", request.getHeader("X-Emby-Token"))
        assertTrue(request.getHeader("X-Emby-Authorization")!!.contains("UserId=\"user-1\""))
    }

    // ---- 5/6/7/8/9：Items 请求 + ParentId/StartIndex/Limit + 映射 + total ----

    @Test
    fun `getItems maps types and passes parentId startIndex limit`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[
                    {"Id":"m1","Name":"电影1","Type":"Movie"},
                    {"Id":"s1","Name":"剧1","Type":"Series"},
                    {"Id":"sea1","Name":"第1季","Type":"Season","IndexNumber":1},
                    {"Id":"e1","Name":"第1集","Type":"Episode","IndexNumber":1,"ParentIndexNumber":1},
                    {"Id":"a1","Name":"歌1","Type":"Audio"},
                    {"Id":"f1","Name":"文件夹","Type":"Folder","IsFolder":true}
                ],"TotalRecordCount":6}"""
            )
        )

        val result = provider().getItems("parent-123", PageRequest(offset = 50, limit = 30))

        val types = result.items.map { it.type }
        assertEquals(
            listOf(MediaType.MOVIE, MediaType.SERIES, MediaType.SEASON, MediaType.EPISODE, MediaType.AUDIO, MediaType.FOLDER),
            types,
        )
        assertEquals(6, result.totalCount)
        assertEquals(1, result.items.first { it.type == MediaType.SEASON }.seasonNumber)
        assertEquals(1, result.items.first { it.type == MediaType.EPISODE }.episodeNumber)

        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/emby/Users/user-1/Items", url.encodedPath)
        assertEquals("parent-123", url.queryParameter("ParentId"))
        assertEquals("50", url.queryParameter("StartIndex"))
        assertEquals("30", url.queryParameter("Limit"))
        assertEquals("tok-1", request.getHeader("X-Emby-Token"))
    }

    // ---- 10：token/session 缺失不发 HTTP 请求 ----

    @Test
    fun `missing session throws AuthRequired without http request`() = runBlocking {
        // 不 seed session
        val thrown = runCatching { provider().getLibraries() }.exceptionOrNull()
        assertTrue(thrown is ProviderException.AuthRequired)
        assertEquals(0, server.requestCount)
    }

    // ---- Phase 1C-2：排序下沉（MediaListQuery） ----

    @Test
    fun `query getItems passes sortBy sortOrder to server`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[{"Id":"m1","Name":"A","Type":"Movie"}],"TotalRecordCount":1}"""
            )
        )

        val query = MediaListQuery(
            page = PageRequest(offset = 0, limit = 50),
            sort = MediaSort(MediaSortField.DATE_ADDED, SortDirection.DESC),
        )
        val result = provider().getItems("lib-1", query)

        assertEquals(1, result.items.size)
        val url = server.takeRequest().requestUrl!!
        assertEquals("DateCreated", url.queryParameter("SortBy"))
        assertEquals("Descending", url.queryParameter("SortOrder"))
    }

    @Test
    fun `query server default carries no sortBy sortOrder`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}""")
        )

        provider().getItems("lib-1", MediaListQuery())

        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("SortBy"))
        assertNull(url.queryParameter("SortOrder"))
    }

    @Test
    fun `random sort is snapshot only - page0 carries no sortOrder, page1 returns empty without request`() =
        runBlocking {
            seedSession()
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    // TotalRecordCount 故意给大值：RANDOM 即使服务器报告更多条，
                    // 也必须单页终止（Integration 审计 §4.4 快照语义）
                    """{"Items":[{"Id":"m1","Name":"A","Type":"Movie"}],"TotalRecordCount":67}"""
                )
            )

            val page0 = provider().getItems(
                "lib-1",
                MediaListQuery(page = PageRequest(offset = 0, limit = 50), sort = MediaSort(MediaSortField.RANDOM)),
            )
            assertEquals(1, page0.items.size)
            assertEquals(67, page0.totalCount)
            assertFalse(page0.hasMore)
            assertNull(page0.nextOffset)

            val url = server.takeRequest().requestUrl!!
            assertEquals("Random", url.queryParameter("SortBy"))
            assertNull(url.queryParameter("SortOrder"))

            // offset>0：不发请求，直接空快照页（随机跨页不重不漏无保证）
            val page1 = provider().getItems(
                "lib-1",
                MediaListQuery(page = PageRequest(offset = 50, limit = 50), sort = MediaSort(MediaSortField.RANDOM)),
            )
            assertTrue(page1.items.isEmpty())
            assertFalse(page1.hasMore)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `sort capabilities hide unconfirmed emby sortBy fields`() {
        val caps = provider().capabilities
        assertTrue(caps.supportsSort(MediaSortField.SERVER_DEFAULT))
        assertTrue(caps.supportsSort(MediaSortField.CRITIC_RATING))
        assertTrue(caps.supportsSort(MediaSortField.RANDOM))
        // 官方 SortBy 枚举未包含：capability 隐藏，恢复需 per-server probe（评审 P1）
        assertFalse(caps.supportsSort(MediaSortField.OFFICIAL_RATING))
        assertFalse(caps.supportsSort(MediaSortField.BITRATE))
        assertFalse(caps.supportsSort(MediaSortField.SIZE))
        // Phase 1D 筛选能力：四项全开（官方已文档化参数）
        assertTrue(caps.supportsFilter(com.mediahub.model.MediaFilterField.MEDIA_TYPE))
        assertTrue(caps.supportsFilter(com.mediahub.model.MediaFilterField.YEAR))
        assertTrue(caps.supportsFilter(com.mediahub.model.MediaFilterField.PLAYED))
        assertTrue(caps.supportsFilter(com.mediahub.model.MediaFilterField.FAVORITE))
    }

    // ---- Phase 1D：筛选 wire contract ----

    @Test
    fun `default filter sends no filter params`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}""")
        )

        provider().getItems("lib-1", MediaListQuery())

        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("IncludeItemTypes"))
        assertNull(url.queryParameter("Years"))
        assertNull(url.queryParameter("IsPlayed"))
        assertNull(url.queryParameter("IsFavorite"))
        // 不加 Recursive：筛选只作用于当前容器直接子级，不改变浏览导航契约
        assertNull(url.queryParameter("Recursive"))
    }

    @Test
    fun `played tri state maps to IsPlayed boolean wire`() = runBlocking {
        seedSession()
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}""")
            )
        }

        provider().getItems("lib-1", MediaListQuery(filter = MediaFilter(played = true)))
        assertEquals("true", server.takeRequest().requestUrl!!.queryParameter("IsPlayed"))

        provider().getItems("lib-1", MediaListQuery(filter = MediaFilter(played = false)))
        assertEquals("false", server.takeRequest().requestUrl!!.queryParameter("IsPlayed"))
    }

    @Test
    fun `favorite tri state maps to IsFavorite boolean wire`() = runBlocking {
        seedSession()
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}""")
            )
        }

        provider().getItems("lib-1", MediaListQuery(filter = MediaFilter(favorite = true)))
        assertEquals("true", server.takeRequest().requestUrl!!.queryParameter("IsFavorite"))

        provider().getItems("lib-1", MediaListQuery(filter = MediaFilter(favorite = false)))
        assertEquals("false", server.takeRequest().requestUrl!!.queryParameter("IsFavorite"))
    }

    @Test
    fun `media type maps to IncludeItemTypes wire`() = runBlocking {
        seedSession()
        repeat(3) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}""")
            )
        }

        provider().getItems("lib-1", MediaListQuery(filter = MediaFilter(mediaType = MediaType.MOVIE)))
        assertEquals("Movie", server.takeRequest().requestUrl!!.queryParameter("IncludeItemTypes"))
        provider().getItems("lib-1", MediaListQuery(filter = MediaFilter(mediaType = MediaType.SERIES)))
        assertEquals("Series", server.takeRequest().requestUrl!!.queryParameter("IncludeItemTypes"))
        provider().getItems("lib-1", MediaListQuery(filter = MediaFilter(mediaType = MediaType.EPISODE)))
        assertEquals("Episode", server.takeRequest().requestUrl!!.queryParameter("IncludeItemTypes"))
    }

    /** 1D 核心 contract：filter + sort + 分页可同时存在于同一请求。 */
    @Test
    fun `combined filter sort pagination contract`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}""")
        )

        val query = MediaListQuery(
            page = PageRequest(offset = 200, limit = 200),
            sort = MediaSort(MediaSortField.COMMUNITY_RATING, SortDirection.DESC),
            filter = MediaFilter(mediaType = MediaType.MOVIE, year = 2024, played = false, favorite = true),
        )
        provider().getItems("lib-1", query)

        val url = server.takeRequest().requestUrl!!
        assertEquals("Movie", url.queryParameter("IncludeItemTypes"))
        assertEquals("2024", url.queryParameter("Years"))
        assertEquals("false", url.queryParameter("IsPlayed"))
        assertEquals("true", url.queryParameter("IsFavorite"))
        assertEquals("CommunityRating", url.queryParameter("SortBy"))
        assertEquals("Descending", url.queryParameter("SortOrder"))
        assertEquals("200", url.queryParameter("StartIndex"))
        assertEquals("200", url.queryParameter("Limit"))
    }

    @Test
    fun `random snapshot with filter carries filter and terminates`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[{"Id":"m1","Name":"A","Type":"Movie"}],"TotalRecordCount":88}"""
            )
        )

        val page0 = provider().getItems(
            "lib-1",
            MediaListQuery(
                page = PageRequest(offset = 0, limit = 50),
                sort = MediaSort(MediaSortField.RANDOM),
                filter = MediaFilter(mediaType = MediaType.MOVIE),
            ),
        )
        assertEquals(1, page0.items.size)
        assertFalse(page0.hasMore)
        assertNull(page0.nextOffset)
        val url = server.takeRequest().requestUrl!!
        assertEquals("Movie", url.queryParameter("IncludeItemTypes"))
        assertEquals("Random", url.queryParameter("SortBy"))
        assertNull(url.queryParameter("SortOrder"))

        // offset>0：不发请求，直接空快照页
        val page1 = provider().getItems(
            "lib-1",
            MediaListQuery(
                page = PageRequest(offset = 50, limit = 50),
                sort = MediaSort(MediaSortField.RANDOM),
                filter = MediaFilter(mediaType = MediaType.MOVIE),
            ),
        )
        assertTrue(page1.items.isEmpty())
        assertEquals(1, server.requestCount)
    }

    // ---- Phase 1C-2：排序/发现字段映射 ----

    @Test
    fun `response discovery fields map to media item`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[{"Id":"m1","Name":"疤面人","Type":"Movie",
                    "SortName":"疤面人",
                    "DateCreated":"2024-01-02T03:04:05.0000000Z",
                    "PremiereDate":"1932-05-01T00:00:00Z",
                    "CriticRating":92.5,
                    "OfficialRating":"PG-13",
                    "Size":2147483648,
                    "Bitrate":8192}],"TotalRecordCount":1}"""
            )
        )

        val result = provider().getItems("lib-1", PageRequest())

        val item = result.items.single()
        assertEquals("疤面人", item.sortName)
        assertEquals(1704164645000L, item.dateAddedEpochMs)
        assertEquals(-1188777600000L, item.premiereDateEpochMs)
        assertEquals(92.5, item.criticRating!!, 0.001)
        assertEquals("PG-13", item.officialRating)
        assertEquals(2147483648L, item.sizeBytes)
        assertEquals(8192L, item.bitrate)
    }

    @Test
    fun `request fields param includes discovery fields`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"Items":[],"TotalRecordCount":0}""")
        )

        provider().getItems("lib-1", PageRequest())

        val fields = server.takeRequest().requestUrl!!.queryParameter("Fields")!!
        listOf("DateCreated", "CriticRating", "PremiereDate", "OfficialRating", "Size", "Bitrate").forEach {
            assertTrue("Fields 缺少 $it", fields.contains(it))
        }
    }

    // ---- 11：401/403/5xx 不被吞掉 ----

    @Test
    fun `401 maps to AuthExpired`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))
        val thrown = runCatching { provider().getLibraries() }.exceptionOrNull()
        assertTrue(thrown is ProviderException.AuthExpired)
    }

    @Test
    fun `403 and 5xx map to Http error`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(403))
        val e403 = runCatching { provider().getLibraries() }.exceptionOrNull()
        assertTrue(e403 is ProviderException.Http)
        assertEquals(403, (e403 as ProviderException.Http).statusCode)

        server.enqueue(MockResponse().setResponseCode(500))
        val e500 = runCatching { provider().getLibraries() }.exceptionOrNull()
        assertTrue(e500 is ProviderException.Http)
        assertEquals(500, (e500 as ProviderException.Http).statusCode)
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
