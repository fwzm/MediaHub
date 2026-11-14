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
 * fnOS / Emby 兼容服播放兜底（用户条目 38 + 79 / handoff 条目 10 + 11）：
 *
 * - PlaybackInfo 缺 PlaySessionId → 客户端生成非空会话 id 兜底，不再 Parse 失败；
 * - 服务端给出真值 → 仍用真值；
 * - 源级 RunTimeTicks 缺失 → 回退条目 runtimeMs。
 *
 * 只用 MockWebServer，绝不访问真实服务器。
 */
class EmbyPlaybackCompatTest {

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

    @Test
    fun `playback info without play session id falls back to a client session id`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"MediaSources":[
                    {"Id":"src1","Container":"mkv","RunTimeTicks":72000000000,
                     "SupportsDirectStream":true}]}"""
            )
        )

        val source = provider().resolvePlayback(movie, PlaybackOptions())

        assertTrue("兜底会话 id 必须非空", source.sessionId!!.isNotBlank())
        assertTrue(
            "兜底会话 id 必须有可识别前缀（实际：${source.sessionId}）",
            source.sessionId!!.startsWith("mh-"),
        )
        assertEquals("stream URL 必须与返回的 sessionId 一致", source.sessionId, source.url.toHttpUrl().queryParameter("PlaySessionId"))
        assertEquals(7_200_000L, source.durationMs)
    }

    @Test
    fun `server provided play session id is still authoritative`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"PlaySessionId":"ps-1","MediaSources":[
                    {"Id":"src1","Container":"mkv","SupportsDirectStream":true}]}"""
            )
        )

        val source = provider().resolvePlayback(movie, PlaybackOptions())

        assertEquals("ps-1", source.sessionId)
        assertEquals("ps-1", source.url.toHttpUrl().queryParameter("PlaySessionId"))
        assertFalse("必须使用服务端真值而非兜底值", source.sessionId!!.startsWith("mh-"))
    }

    @Test
    fun `duration falls back to item runtime when source runtime is missing`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"PlaySessionId":"ps-1","MediaSources":[
                    {"Id":"src1","Container":"mkv","SupportsDirectStream":true}]}"""
            )
        )

        val source = provider().resolvePlayback(movie.copy(runtimeMs = 600_000L), PlaybackOptions())

        assertEquals(600_000L, source.durationMs)
    }

    @Test
    fun `duration falls back to item runtime when source runtime is not a plausible value`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"PlaySessionId":"ps-1","MediaSources":[
                    {"Id":"src1","Container":"mkv","RunTimeTicks":-1,
                     "SupportsDirectStream":true}]}"""
            )
        )

        val source = provider().resolvePlayback(movie.copy(runtimeMs = 600_000L), PlaybackOptions())

        assertEquals(600_000L, source.durationMs)
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
