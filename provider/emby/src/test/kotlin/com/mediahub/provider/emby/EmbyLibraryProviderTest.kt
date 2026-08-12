package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.LibraryType
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.ServerType
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
