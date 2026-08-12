package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.HdrType
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.playback.EmbyPlaybackProvider
import com.mediahub.provider.emby.session.EmbySession
import com.mediahub.provider.emby.session.EmbySessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Emby 无转码 Direct Stream 播放（Phase 1B-2）MockWebServer 测试。 */
class EmbyPlaybackProviderTest {
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
    private val movie = MediaItem(
        serverId = "srv-1", id = "m1", type = MediaType.MOVIE,
        title = "电影A", container = "mkv",
    )

    private fun provider(): EmbyPlaybackProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        val client = OkHttpClient()
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return EmbyPlaybackProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(EmbySession("srv-1", "remote-1", "user-1", "Alice"))
    }

    private fun playbackInfoJson() = """
        {"PlaySessionId":"ps-1",
         "MediaSources":[{"Id":"src1","Container":"mkv","Bitrate":1000000,
           "RunTimeTicks":72000000000,"SupportsDirectStream":true,
           "MediaStreams":[
             {"Index":0,"Type":"Video","Codec":"hevc","Width":1920,
              "Height":1080,"VideoRange":"HDR10"},
             {"Index":1,"Type":"Audio","Codec":"aac","Channels":6}]}]}
    """.trimIndent()

    @Test
    fun `resolvePlayback returns static direct stream url with no token in url`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody(playbackInfoJson()))

        val source = provider().resolvePlayback(movie, PlaybackOptions())

        val url = source.url.toHttpUrl()
        assertEquals("/emby/Videos/m1/stream.mkv", url.encodedPath)
        assertEquals("true", url.queryParameter("static"))
        assertEquals("src1", url.queryParameter("MediaSourceId"))
        assertEquals("ps-1", url.queryParameter("PlaySessionId"))
        assertFalse(source.url.contains("tok-1"))
        assertEquals("tok-1", source.headers["X-Emby-Token"])
        assertTrue(source.headers["X-Emby-Authorization"]!!.contains("UserId=\"user-1\""))
        assertEquals(PlaybackMode.DIRECT_STREAM, source.mode)
    }

    @Test
    fun `resolvePlayback maps codecs bitrate dimensions hdr duration`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody(playbackInfoJson()))

        val source = provider().resolvePlayback(movie, PlaybackOptions())

        assertEquals("hevc", source.videoCodec)
        assertEquals("aac", source.audioCodec)
        assertEquals(1_000_000L, source.bitrate)
        assertEquals(1920, source.width)
        assertEquals(1080, source.height)
        assertEquals(HdrType.HDR10, source.hdrType)
        assertEquals(7_200_000L, source.durationMs)
        assertEquals("mkv", source.container)
    }

    @Test
    fun `playbackInfo request disables transcoding and carries resume params`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody(playbackInfoJson()))

        provider().resolvePlayback(
            movie,
            PlaybackOptions(startPositionMs = 60_000, maxBitrate = 2_000_000),
        )

        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("/emby/Items/m1/PlaybackInfo", url.encodedPath)
        assertEquals("user-1", url.queryParameter("UserId"))
        assertEquals("true", url.queryParameter("IsPlayback"))
        assertEquals("false", url.queryParameter("EnableDirectPlay"))
        assertEquals("true", url.queryParameter("EnableDirectStream"))
        assertEquals("false", url.queryParameter("EnableTranscoding"))
        assertEquals("600000000", url.queryParameter("StartTimeTicks"))
        assertEquals("2000000", url.queryParameter("MaxStreamingBitrate"))
        assertEquals("tok-1", request.getHeader("X-Emby-Token"))
    }

    @Test
    fun `no direct stream source throws NotYetImplemented needs transcode`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"PlaySessionId":"ps-1","MediaSources":[
                    {"Id":"src1","Container":"mkv","SupportsDirectStream":false,
                     "SupportsTranscoding":true}]}"""
            )
        )

        val thrown = runCatching {
            provider().resolvePlayback(movie, PlaybackOptions())
        }.exceptionOrNull()

        assertTrue(thrown is ProviderException.NotYetImplemented)
        assertTrue(thrown!!.message!!.contains("需要转码"))
    }

    @Test
    fun `forceTranscode throws NotYetImplemented without http request`() = runBlocking {
        seedSession()

        val thrown = runCatching {
            provider().resolvePlayback(movie, PlaybackOptions(forceTranscode = true))
        }.exceptionOrNull()

        assertTrue(thrown is ProviderException.NotYetImplemented)
        assertTrue(thrown!!.message!!.contains("需要转码"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `missing session throws AuthRequired without http request`() = runBlocking {
        val thrown = runCatching {
            provider().resolvePlayback(movie, PlaybackOptions())
        }.exceptionOrNull()
        assertTrue(thrown is ProviderException.AuthRequired)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `401 maps to AuthExpired`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))
        val thrown = runCatching {
            provider().resolvePlayback(movie, PlaybackOptions())
        }.exceptionOrNull()
        assertTrue(thrown is ProviderException.AuthExpired)
    }

    @Test
    fun `picks first direct stream source among multiple and uses its id`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"PlaySessionId":"ps-2","MediaSources":[
                    {"Id":"dovi","Container":"mkv","SupportsDirectStream":false,
                     "SupportsTranscoding":true},
                    {"Id":"src2","Container":"mp4","SupportsDirectStream":true}]}"""
            )
        )

        val source = provider().resolvePlayback(movie, PlaybackOptions())

        assertEquals("src2", source.url.toHttpUrl().queryParameter("MediaSourceId"))
        assertEquals("mp4", source.container)
        assertEquals("/emby/Videos/m1/stream.mp4", source.url.toHttpUrl().encodedPath)
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
