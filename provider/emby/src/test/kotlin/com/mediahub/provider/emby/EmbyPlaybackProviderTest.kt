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

/**
 * Emby 无转码 Direct Stream 播放（Phase 1B-2.1 FINAL HARDENING）MockWebServer 测试。
 * 覆盖：官方 POST PlaybackInfo contract、Token 不进 URL/body、RequiredHttpHeaders 合并与
 * 鉴权头保护、MediaSourceId/PlaySessionId/MediaSources 严格校验、错误映射、
 * AUDIO 拒绝路径与 Direct Stream URL 必备参数。
 */
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
    private val audioItem = MediaItem(
        serverId = "srv-1", id = "a1", type = MediaType.AUDIO,
        title = "歌曲A", container = "flac",
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

    // ---- 1：官方 POST PlaybackInfo contract（Token 不进 URL/body） ----
    @Test
    fun `playback info uses official POST contract with params in body not query`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody(playbackInfoJson()))
        provider().resolvePlayback(
            movie,
            PlaybackOptions(startPositionMs = 60_000, maxBitrate = 2_000_000),
        )
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("POST", request.method)
        assertEquals("/emby/Items/m1/PlaybackInfo", url.encodedPath)
        // query 只保留官方 GET/POST 共同参数 UserId
        assertEquals("user-1", url.queryParameter("UserId"))
        assertEquals(1, url.querySize)
        // 协商参数全部在 JSON body
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"EnableTranscoding\":false"))
        assertTrue(body.contains("\"EnableDirectStream\":true"))
        assertTrue(body.contains("\"EnableDirectPlay\":false"))
        assertTrue(body.contains("\"IsPlayback\":true"))
        assertTrue(body.contains("\"UserId\":\"user-1\""))
        assertTrue(body.contains("\"DeviceProfile\""))
        assertTrue(body.contains("\"SupportedMediaTypes\":\"Video\""))
        assertTrue(body.contains("\"DirectPlayProfiles\":[{\"Type\":\"Video\"}]"))
        // 三项协商开关只属于 PlaybackInfoRequest，不能在 DeviceProfile 内重复伪造。
        assertEquals(1, Regex("\\\"EnableDirectPlay\\\"").findAll(body).count())
        assertEquals(1, Regex("\\\"EnableDirectStream\\\"").findAll(body).count())
        assertEquals(1, Regex("\\\"EnableTranscoding\\\"").findAll(body).count())
        assertTrue(body.contains("\"StartTimeTicks\":600000000"))
        assertTrue(body.contains("\"MaxStreamingBitrate\":2000000"))
        // Token 不进 URL query / JSON body，只走请求头
        assertFalse(url.toString().contains("tok-1"))
        assertFalse(body.contains("tok-1"))
        assertEquals("tok-1", request.getHeader("X-Emby-Token"))
    }

    // ---- 2：Direct Stream URL 必备参数 ----
    @Test
    fun `direct stream url always carries mediaSourceId playSessionId and static`() = runBlocking {
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

    // ---- 3：元数据映射 ----
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

    // ---- 4：ExtendedVideoType → Dolby Vision ----
    @Test
    fun `dolby vision detected via extended video type`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"PlaySessionId":"ps-1","MediaSources":[
                    {"Id":"src1","Container":"mkv","SupportsDirectStream":true,
                     "MediaStreams":[{"Index":0,"Type":"Video","Codec":"hevc",
                       "VideoRange":"HDR10","ExtendedVideoType":"DolbyVision"}]}]}"""
            )
        )
        val source = provider().resolvePlayback(movie, PlaybackOptions())
        assertEquals(HdrType.DOLBY_VISION, source.hdrType)
    }

    @Test
    fun `dolby vision detected via dovi extended video subtype`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"PlaySessionId":"ps-1","MediaSources":[
                    {"Id":"src1","Container":"mkv","SupportsDirectStream":true,
                     "MediaStreams":[{"Index":0,"Type":"Video","Codec":"hevc",
                       "VideoRange":"HDR10","ExtendedVideoSubType":"DoviProfile81"}]}]}"""
            )
        )
        val source = provider().resolvePlayback(movie, PlaybackOptions())
        assertEquals(HdrType.DOLBY_VISION, source.hdrType)
    }

    // ---- 5：RequiredHttpHeaders 合并 ----
    @Test
    fun `required http headers merged into playback source headers`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"PlaySessionId":"ps-1","MediaSources":[
                    {"Id":"src1","Container":"mkv","SupportsDirectStream":true,
                     "RequiredHttpHeaders":{"X-Forwarded-For":"1.2.3.4","X-Emby-ItemId":"m1"}}]}"""
            )
        )
        val source = provider().resolvePlayback(movie, PlaybackOptions())
        assertEquals("1.2.3.4", source.headers["X-Forwarded-For"])
        assertEquals("m1", source.headers["X-Emby-ItemId"])
    }

    // ---- 6：RequiredHttpHeaders 不能覆盖鉴权头 ----
    @Test
    fun `required http headers cannot override emby auth headers`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"PlaySessionId":"ps-1","MediaSources":[
                    {"Id":"src1","Container":"mkv","SupportsDirectStream":true,
                     "RequiredHttpHeaders":{"x-emby-token":"evil-token",
                       "x-emby-authorization":"evil-auth"}}]}"""
            )
        )
        val source = provider().resolvePlayback(movie, PlaybackOptions())
        assertEquals("tok-1", source.headers["X-Emby-Token"])
        assertTrue(source.headers["X-Emby-Authorization"]!!.contains("UserId=\"user-1\""))
        assertFalse(source.headers["X-Emby-Authorization"]!!.contains("evil"))
        assertFalse(source.headers.keys.any { it == "x-emby-token" })
        assertFalse(source.headers.keys.any { it == "x-emby-authorization" })
    }

    // ---- 7/8/9：严格校验（响应损坏 ≠ 需要转码） ----
    @Test
    fun `empty media sources throws parse not needs transcode`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"PlaySessionId":"ps-1","MediaSources":[]}"""
            )
        )
        val thrown = runCatching {
            provider().resolvePlayback(movie, PlaybackOptions())
        }.exceptionOrNull()
        assertTrue("expected Parse, got $thrown", thrown is ProviderException.Parse)
    }

    @Test
    fun `missing media source id throws parse`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"PlaySessionId":"ps-1","MediaSources":[
                    {"Container":"mkv","SupportsDirectStream":true}]}"""
            )
        )
        val thrown = runCatching {
            provider().resolvePlayback(movie, PlaybackOptions())
        }.exceptionOrNull()
        assertTrue("expected Parse, got $thrown", thrown is ProviderException.Parse)
    }

    @Test
    fun `missing play session id throws parse`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"MediaSources":[
                    {"Id":"src1","Container":"mkv","SupportsDirectStream":true}]}"""
            )
        )
        val thrown = runCatching {
            provider().resolvePlayback(movie, PlaybackOptions())
        }.exceptionOrNull()
        assertTrue("expected Parse, got $thrown", thrown is ProviderException.Parse)
    }

    // ---- 10：有源但全都不支持 Direct Stream → 需要转码 ----
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

    // ---- 11/12/13：HTTP 错误映射 ----
    @Test
    fun `403 maps to http error`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(403))
        val thrown = runCatching {
            provider().resolvePlayback(movie, PlaybackOptions())
        }.exceptionOrNull()
        assertTrue(thrown is ProviderException.Http)
        assertEquals(403, (thrown as ProviderException.Http).statusCode)
    }

    @Test
    fun `404 maps to notFound`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(404))
        val thrown = runCatching {
            provider().resolvePlayback(movie, PlaybackOptions())
        }.exceptionOrNull()
        assertTrue(thrown is ProviderException.NotFound)
    }

    @Test
    fun `500 maps to http error`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(500))
        val thrown = runCatching {
            provider().resolvePlayback(movie, PlaybackOptions())
        }.exceptionOrNull()
        assertTrue(thrown is ProviderException.Http)
        assertEquals(500, (thrown as ProviderException.Http).statusCode)
    }

    // ---- 14：AUDIO 明确拒绝且不发 HTTP（绝不构造 /Videos/... 音频地址） ----
    @Test
    fun `audio item throws NotYetImplemented without http request`() = runBlocking {
        seedSession()
        val thrown = runCatching {
            provider().resolvePlayback(audioItem, PlaybackOptions())
        }.exceptionOrNull()
        assertTrue(thrown is ProviderException.NotYetImplemented)
        assertTrue(thrown!!.message!!.contains("音频播放尚未接入"))
        assertEquals(0, server.requestCount)
    }

    // ---- 15：forceTranscode 不触网 ----
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

    // ---- 16：缺 session 不触网 ----
    @Test
    fun `missing session throws AuthRequired without http request`() = runBlocking {
        val thrown = runCatching {
            provider().resolvePlayback(movie, PlaybackOptions())
        }.exceptionOrNull()
        assertTrue(thrown is ProviderException.AuthRequired)
        assertEquals(0, server.requestCount)
    }

    // ---- 17：401 → AuthExpired ----
    @Test
    fun `401 maps to AuthExpired`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))
        val thrown = runCatching {
            provider().resolvePlayback(movie, PlaybackOptions())
        }.exceptionOrNull()
        assertTrue(thrown is ProviderException.AuthExpired)
    }

    // ---- 18：多源选择取第一个 DirectStream ----
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
