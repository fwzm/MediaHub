package com.mediahub.provider.jellyfin.playback

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackMode
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * Jellyfin 播放（Phase 1G-C）MockWebServer 测试：
 * 无转码红线 / PlaybackInfo 协商 / Direct Stream URL / RequiredHttpHeaders 与鉴权头合并 /
 * Token 不进 URL / 错误与取消映射。
 */
class JellyfinPlaybackProviderTest {

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

    private fun movie() = MediaItem(
        serverId = "srv-1", id = "m1", type = MediaType.MOVIE, title = "Fargo",
        container = "mkv",
    )

    private fun playbackInfoBody() = """
        {"MediaSources":[
            {"Id":"ms-1","Container":"mkv","SupportsDirectStream":true,
             "RunTimeTicks":660000000,
             "MediaStreams":[
                {"Type":"Video","Codec":"hevc","Width":3840,"Height":2160},
                {"Type":"Audio","Codec":"eac3"}],
             "RequiredHttpHeaders":{"X-Custom-Auth":"source-secret"}},
         {"Id":"ms-2","Container":"mp4","SupportsDirectStream":false,
          "SupportsTranscoding":true,"Path":"/mnt/x.iso"}],
         "PlaySessionId":"ps-1"}
    """.trimIndent()

    private fun provider(client: OkHttpClient = OkHttpClient()): JellyfinPlaybackProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        val api = JellyfinApiClient(
            endpointResolver = JellyfinEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = JellyfinAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return JellyfinPlaybackProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(JellyfinSession("srv-1", "remote-1", "user-1", "Alice"))
    }

    // ---- 1：协商成功 → Direct Stream URL + 头合并（鉴权权威获胜） ----

    @Test
    fun `direct stream negotiation builds same origin static url with merged headers`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody(playbackInfoBody()))

        val source = provider().resolvePlayback(
            movie(),
            PlaybackOptions(startPositionMs = 90_000, maxBitrate = 120_000_000),
        )

        // 无转码 Direct Stream：configured server origin + Static=true
        assertEquals(PlaybackMode.DIRECT_STREAM, source.mode)
        assertTrue(source.url.contains("/Videos/m1/stream.mkv"))
        assertTrue(source.url.contains("MediaSourceId=ms-1"))
        assertTrue(source.url.contains("Static=true"))
        assertFalse("Token 绝不进 URL", source.url.contains("tok-1"))
        assertNull("api_key 不得出现", source.url.toHttpUrlOrNull()!!.queryParameter("api_key"))
        // 头：源级 RequiredHttpHeaders 并入，但权威 Authorization 存在且不重复
        assertEquals("source-secret", source.headers["X-Custom-Auth"])
        assertTrue(source.headers["Authorization"]!!.endsWith("Token=\"tok-1\""))
        assertEquals(1, source.headers.entries.count { it.key.equals("Authorization", ignoreCase = true) })
        // 编解码元数据
        assertEquals("hevc", source.videoCodec)
        assertEquals("eac3", source.audioCodec)
        assertEquals(66_000L, source.durationMs)  // 660000000 ticks / 10_000 = 66s
        // 协商请求：POST /Items/m1/PlaybackInfo?UserId=user-1；StartTimeTicks ms→ticks
        val request = server.takeRequest()
        assertEquals("/Items/m1/PlaybackInfo", request.requestUrl!!.encodedPath)
        assertEquals("user-1", request.requestUrl!!.queryParameter("UserId"))
        val body = request.body.readUtf8()
        assertTrue("实际 body：$body", body.contains("\"StartTimeTicks\":900000000"))
        assertTrue(body.contains("\"EnableTranscoding\":false"))
        assertTrue(body.contains("\"EnableDirectStream\":true"))
        // v10.9.0 PlaybackInfoRequest 精确 contract：MaxStreamingBitrate int?（请求值直传）
        assertTrue(body.contains("\"MaxStreamingBitrate\":120000000"))
        // 无 IsPlayback 字段（ADR-039 review 修正：禁猜 Emby shape）
        assertFalse(body.contains("IsPlayback"))
        assertFalse(body.contains("tok-1"))
    }

    // ---- 1b：MaxStreamingBitrate int? 收缩（>Int.MAX_VALUE → 2147483647） ----

    @Test
    fun `max streaming bitrate clamps to int max`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody(playbackInfoBody()))
        provider().resolvePlayback(
            movie(),
            PlaybackOptions(maxBitrate = 50_000_000_000L), // > Int.MAX_VALUE
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(
            "实际 body：$body",
            body.contains("\"MaxStreamingBitrate\":2147483647"),
        )
    }

    // ---- 2：无转码红线 + 类型门（0 HTTP） ----

    @Test
    fun `unsupported media type rejected without any http`() = runBlocking {
        seedSession()
        try {
            provider().resolvePlayback(
                movie().copy(id = "a1", type = MediaType.AUDIO),
                PlaybackOptions(),
            )
            fail("必须抛 NotYetImplemented")
        } catch (_: ProviderException.NotYetImplemented) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `transcode request rejected without any http`() = runBlocking {
        seedSession()
        try {
            provider().resolvePlayback(movie(), PlaybackOptions(forceTranscode = true))
            fail("必须抛 NotYetImplemented")
        } catch (_: ProviderException.NotYetImplemented) {
        }
        try {
            provider().resolvePlayback(movie(), PlaybackOptions(enableDirectStream = false))
            fail("必须抛 NotYetImplemented")
        } catch (_: ProviderException.NotYetImplemented) {
        }
        assertEquals(0, server.requestCount)
    }

    // ---- 3：响应损坏 / 需要转码 ----

    @Test
    fun `empty media sources maps to parse`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"MediaSources":[]}"""))
        try {
            provider().resolvePlayback(movie(), PlaybackOptions())
            fail("必须抛 Parse")
        } catch (_: ProviderException.Parse) {
        }
    }

    @Test
    fun `no direct stream capable source maps to transcode not implemented`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"MediaSources":[{"Id":"ms-1","Container":"mp4","SupportsDirectStream":false,
                     "SupportsTranscoding":true}]}"""
            )
        )
        try {
            provider().resolvePlayback(movie(), PlaybackOptions())
            fail("必须抛 NotYetImplemented")
        } catch (e: ProviderException.NotYetImplemented) {
            assertTrue(e.message!!.contains("转码"))
        }
    }

    // ---- 4：错误与取消 ----

    @Test
    fun `401 maps to auth expired and 404 maps to not found`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            provider().resolvePlayback(movie(), PlaybackOptions())
            fail("必须抛 AuthExpired")
        } catch (_: ProviderException.AuthExpired) {
        }
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            provider().resolvePlayback(movie(), PlaybackOptions())
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
            provider(cancellingClient).resolvePlayback(movie(), PlaybackOptions())
            null
        } catch (e: CancellationException) {
            e
        } catch (e: Throwable) {
            e
        }
        assertTrue("取消必须穿透（实际：$thrown）", thrown is CancellationException)
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
