package com.mediahub.provider.jellyfin.detail

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.HdrType
import com.mediahub.model.MediaServer
import com.mediahub.model.Person
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Jellyfin 条目详情（Phase 1G-B）MockWebServer 测试：映射 / 错误映射 / 无 Fields 裁剪。 */
class JellyfinDetailProviderTest {

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

    private fun provider(): JellyfinDetailProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        val api = JellyfinApiClient(
            endpointResolver = JellyfinEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(OkHttpClient(), logger = logger),
            authHeaderBuilder = JellyfinAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return JellyfinDetailProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(JellyfinSession("srv-1", "remote-1", "user-1", "Alice"))
    }

    // ---- 1：详情映射——People/Studios/ProviderIds/海报 ----

    @Test
    fun `detail maps full item with people studios and provider ids`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Id":"m1","Name":"Fargo","Type":"Movie","ProductionYear":2014,
                     "Overview":"crime drama","Genres":["Crime","Drama"],
                     "CommunityRating":8.4,"Container":"mkv",
                     "ProviderIds":{"Imdb":"tt0137523","Tmdb":"275"},
                     "Studios":[{"Name":"FX"}],
                     "People":[
                        {"Name":"Billy Bob","Type":"Actor","Role":"Gator","Id":"p1",
                         "PrimaryImageTag":"ptag"},
                        {"Name":"Noah","Type":"Director","Id":"p2"}
                     ],
                     "ImageTags":{"Primary":"tag-p"}}"""
            )
        )

        val detail = provider().getItemDetail("m1")

        val item = detail.item
        assertEquals("Fargo", item.title)
        assertEquals("tt0137523", item.externalIds?.imdb)
        assertEquals(listOf("Crime", "Drama"), item.genres)
        assertEquals(listOf("FX"), item.studios)
        assertEquals(2, item.people.size)
        assertEquals("Gator", item.people.first { it.id == "p1" }.characterName)
        assertEquals(Person.Role.DIRECTOR, item.people.first { it.id == "p2" }.role)
        assertTrue(item.people.first { it.id == "p1" }.imageUrl!!.contains("/Items/p1/Images/Primary"))
        assertTrue(item.posterUrl!!.contains("/Items/m1/Images/Primary"))

        // 单条目详情端点：路径精确；Fields 只请求 MediaSources（多版本闭环）
        val request = server.takeRequest()
        assertEquals("/Items/m1", request.requestUrl!!.encodedPath)
        assertEquals("user-1", request.requestUrl!!.queryParameter("UserId"))
        assertTrue(request.getHeader("Authorization")!!.endsWith("Token=\"tok-1\""))
        assertEquals("MediaSources", request.requestUrl!!.queryParameter("Fields"))
    }

    // ---- 1b：多版本映射（与 Emby 同构：服务端顺序、id/name/container/宽高/HDR/码率/体积） ----

    @Test
    fun `detail maps media sources into versions`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Id":"m1","Name":"Fargo","Type":"Movie","Container":"mkv",
                     "MediaSources":[
                       {"Id":"ms-1","Name":"4K HDR10","Container":"mkv",
                        "Size":20000000000,"Bitrate":25000000,
                        "MediaStreams":[{"Type":"Video","Codec":"hevc","Width":3840,
                          "Height":2160,"VideoRange":"HDR10"}]},
                       {"Id":"ms-2","Name":"1080P","Container":"mp4",
                        "Size":8000000000,"Bitrate":8000000,
                        "MediaStreams":[{"Type":"Video","Codec":"h264","Width":1920,
                          "Height":1080}]}
                     ]}"""
            )
        )

        val detail = provider().getItemDetail("m1")

        assertEquals(listOf("ms-1", "ms-2"), detail.versions.map { it.id })
        val uhd = detail.versions[0]
        assertEquals("4K HDR10", uhd.name)
        assertEquals("mkv", uhd.container)
        assertEquals(3840, uhd.width)
        assertEquals(2160, uhd.height)
        assertEquals(HdrType.HDR10, uhd.hdrType)
        assertEquals(25_000_000L, uhd.bitrate)
        assertEquals(20_000_000_000L, uhd.sizeBytes)
        assertEquals(HdrType.NONE, detail.versions[1].hdrType)
        assertEquals(8_000_000L, detail.versions[1].bitrate)
    }

    // ---- 2：错误映射 ----

    @Test
    fun `404 maps to not found`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            provider().getItemDetail("missing")
            fail("必须抛 NotFound")
        } catch (_: ProviderException.NotFound) {
        }
    }

    @Test
    fun `id-less response maps to parse error`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Name":"no id"}"""))
        try {
            provider().getItemDetail("m1")
            fail("必须抛 Parse")
        } catch (_: ProviderException.Parse) {
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
