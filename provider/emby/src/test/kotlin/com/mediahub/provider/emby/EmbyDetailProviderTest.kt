package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.HdrType
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.detail.EmbyDetailProvider
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

/** Emby 条目详情（Phase 1B-2）MockWebServer 测试。 */
class EmbyDetailProviderTest {
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

    private fun provider(): EmbyDetailProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        val client = OkHttpClient()
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return EmbyDetailProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(EmbySession("srv-1", "remote-1", "user-1", "Alice"))
    }

    private val movieJson = """
        {"Id":"m1","Name":"电影A","Type":"Movie","ProductionYear":2020,
         "RunTimeTicks":72000000000,"Overview":"简介","Genres":["动作","科幻"],
         "CommunityRating":8.5,"Container":"mkv",
         "MediaSources":[{"Id":"src1","Name":"4K HDR10","Container":"mkv","Size":123,
           "Bitrate":1000000,"RunTimeTicks":72000000000,"SupportsDirectStream":true,
           "MediaStreams":[{"Index":0,"Type":"Video","Codec":"hevc","Width":3840,
             "Height":2160,"BitRate":8000000,"VideoRange":"HDR10","Level":153,"IsDefault":true}]}],
         "MediaStreams":[
           {"Index":0,"Type":"Video","Codec":"hevc","Width":3840,"Height":2160,
            "BitRate":8000000,"VideoRange":"HDR10","Level":153,"IsDefault":true},
           {"Index":1,"Type":"Audio","Codec":"aac","Channels":6,"SampleRate":48000,
            "Language":"eng","IsDefault":true},
           {"Index":2,"Type":"Subtitle","Codec":"srt","Language":"chi"}],
         "Chapters":[{"StartPositionTicks":0,"Name":"开场"},
           {"StartPositionTicks":6000000000,"Name":"高潮"}]}
    """.trimIndent()

    @Test
    fun `getItemDetail maps movie detail with versions streams tracks chapters`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody(movieJson))

        val detail = provider().getItemDetail("m1")

        assertEquals("m1", detail.item.id)
        assertEquals(MediaType.MOVIE, detail.item.type)
        assertEquals("简介", detail.item.overview)
        assertEquals(listOf("动作", "科幻"), detail.item.genres)
        assertEquals("mkv", detail.item.container)
        assertEquals(1, detail.versions.size)
        assertEquals("src1", detail.versions[0].id)
        assertEquals(HdrType.HDR10, detail.versions[0].hdrType)
        assertEquals(3840, detail.versions[0].width)
        assertEquals(3, detail.streams.size)
        assertEquals(1, detail.audioTracks.size)
        assertEquals(6, detail.audioTracks[0].channels)
        assertEquals("eng", detail.audioTracks[0].language)
        assertEquals(1, detail.subtitles.size)
        assertEquals("srt", detail.subtitles[0].format)
        assertEquals(2, detail.chapters.size)
        assertEquals(600_000L, detail.chapters[1].startMs)
    }

    @Test
    fun `getItemDetail sends auth headers to correct path`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"Id":"m1","Name":"M","Type":"Movie"}""")
        )

        provider().getItemDetail("m1")

        val request = server.takeRequest()
        assertEquals("/emby/Users/user-1/Items/m1", request.path)
        assertEquals("tok-1", request.getHeader("X-Emby-Token"))
        assertTrue(request.getHeader("X-Emby-Authorization")!!.contains("UserId=\"user-1\""))
    }

    @Test
    fun `missing session throws AuthRequired without http request`() = runBlocking {
        val thrown = runCatching { provider().getItemDetail("m1") }.exceptionOrNull()
        assertTrue(thrown is ProviderException.AuthRequired)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `404 maps to NotFound`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(404))
        val thrown = runCatching { provider().getItemDetail("m1") }.exceptionOrNull()
        assertTrue(thrown is ProviderException.NotFound)
    }

    @Test
    fun `401 maps to AuthExpired`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))
        val thrown = runCatching { provider().getItemDetail("m1") }.exceptionOrNull()
        assertTrue(thrown is ProviderException.AuthExpired)
    }

    @Test
    fun `item without id maps to Parse error`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"Name":"无id","Type":"Movie"}""")
        )
        val thrown = runCatching { provider().getItemDetail("m1") }.exceptionOrNull()
        assertTrue(thrown is ProviderException.Parse)
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
