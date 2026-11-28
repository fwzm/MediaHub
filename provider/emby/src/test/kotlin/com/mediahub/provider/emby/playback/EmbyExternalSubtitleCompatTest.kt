package com.mediahub.provider.emby.playback

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
import com.mediahub.model.ServerType
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
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
 * 部分媒体服外挂字幕（用户条目 107 / 审计 81）：codec 别名 + DeliveryUrl 外挂判定。
 *
 * 只用 MockWebServer，绝不访问真实服务器。每条轨道仍必须指向官方字幕端点，
 * DeliveryUrl（可能内嵌 api_key）绝不能被当成请求地址。
 */
class EmbyExternalSubtitleCompatTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: TokenStore
    private lateinit var sessionStore: EmbySessionStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tokenStore = TokenStore(FakeSecretStorage())
        sessionStore = EmbySessionStore(FakeSessionStorage())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

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
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(OkHttpClient(), logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(
                ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
            ),
            logger = logger,
        )
        return EmbyPlaybackProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(EmbySession("srv-1", "remote-1", "user-1", "Alice"))
    }

    private fun enqueueStreams(streams: String) {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"PlaySessionId":"ps-1","MediaSources":[
                    {"Id":"src1","Container":"mkv","SupportsDirectStream":true,
                     "MediaStreams":[$streams]}]}"""
            )
        )
    }

    @Test
    fun `media browser codec aliases map to text subtitle formats`() = runBlocking {
        seedSession()
        enqueueStreams(
            """
            {"Index":2,"Type":"Subtitle","Codec":"S_TEXT/UTF8","Language":"zho","IsExternal":true},
            {"Index":3,"Type":"Subtitle","Codec":"S_TEXT/ASS","IsExternal":true},
            {"Index":4,"Type":"Subtitle","Codec":"s_text/ssa","IsExternal":true},
            {"Index":5,"Type":"Subtitle","Codec":"S_TEXT/WEBVTT","IsExternal":true},
            {"Index":6,"Type":"Subtitle","Codec":"SUBRIP","IsExternal":true}
            """.trimIndent(),
        )

        val source = provider().resolvePlayback(movie, PlaybackOptions())

        assertEquals(listOf(2, 3, 4, 5, 6), source.externalSubtitles.map { it.index })
        assertEquals(listOf("srt", "ass", "ssa", "vtt", "srt"), source.externalSubtitles.map { it.format })
        for (track in source.externalSubtitles) {
            assertEquals(
                "/emby/Videos/m1/src1/Subtitles/${track.index}/Stream.${track.format}",
                track.url!!.toHttpUrl().encodedPath,
            )
            assertEquals(0, track.url!!.toHttpUrl().querySize)
        }
    }

    @Test
    fun `bitmap subtitle codecs are still skipped`() = runBlocking {
        seedSession()
        enqueueStreams(
            """
            {"Index":7,"Type":"Subtitle","Codec":"pgssub","IsExternal":true},
            {"Index":8,"Type":"Subtitle","Codec":"VOBSUB","IsExternal":true},
            {"Index":9,"Type":"Subtitle","Codec":"dvdsub","IsExternal":true}
            """.trimIndent(),
        )

        val source = provider().resolvePlayback(movie, PlaybackOptions())

        assertTrue("位图字幕需要另一套加载器，不得产出文本轨", source.externalSubtitles.isEmpty())
    }

    @Test
    fun `delivery url marks an external sidecar but is never used as the request address`() = runBlocking {
        seedSession()
        enqueueStreams(
            """{"Index":9,"Type":"Subtitle","Codec":"srt","IsExternal":false,
                "DeliveryUrl":"https://untrusted.example/sub.srt?api_key=server-secret"}"""
        )

        val source = provider().resolvePlayback(movie, PlaybackOptions())

        val track = source.externalSubtitles.single()
        assertEquals(9, track.index)
        assertTrue("DeliveryUrl 存在即视为外挂字幕", track.isExternal)
        assertTrue(track.url!!.startsWith(server.url("/").toString()))
        assertFalse("DeliveryUrl 可能内嵌凭据，绝不能作为请求地址", track.url!!.contains("untrusted.example"))
        assertFalse(track.url!!.contains("server-secret"))
        assertEquals(0, track.url!!.toHttpUrl().querySize)
    }

    @Test
    fun `internal subtitle without delivery url is not treated as external`() = runBlocking {
        seedSession()
        enqueueStreams(
            """{"Index":10,"Type":"Subtitle","Codec":"srt","IsExternal":false}"""
        )

        val source = provider().resolvePlayback(movie, PlaybackOptions())

        assertTrue(source.externalSubtitles.isEmpty())
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
