package com.mediahub.provider.emby.progress

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.session.EmbySession
import com.mediahub.provider.emby.session.EmbySessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * fnOS / Emby 兼容服续播（用户条目 38 / handoff 条目 10）：
 *
 * - 服务端进度读取：`/Users/{userId}/Items/{itemId}?Fields=UserData` 与
 *   `/Users/{userId}/Items/Resume`（404/405 = 能力缺失 → 空列表，不是致命错误）；
 * - 兼容回写：客户端兜底会话 id（`mh-` 前缀）在最终 Stopped 之前补发一次
 *   Progress（最终位置 + IsPaused=true）——部分兼容服只按 Progress 落库位置。
 *
 * 只用 MockWebServer，绝不访问真实服务器。
 */
class EmbyResumeCompatTest {

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

    private val mediaServer = MediaServer(
        id = "srv-1", name = "Emby", type = ServerType.EMBY,
        baseUrl = "http://localhost", createdAtEpochMs = 0,
    )

    private fun provider(): EmbyProgressProvider {
        val logger = StdoutLogger()
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(OkHttpClient(), logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(
                ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
            ),
            logger = logger,
        )
        return EmbyProgressProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(EmbySession("srv-1", "remote-1", "user-1", "Alice"))
    }

    private fun progress(
        itemId: String = "m1",
        positionMs: Long = 90_000,
        sessionId: String? = "psid-1",
    ) = PlaybackProgress(
        serverId = "srv-1", itemId = itemId, positionMs = positionMs, durationMs = 600_000,
        isPaused = false, updatedAtEpochMs = 0, sessionId = sessionId,
        mode = PlaybackMode.DIRECT_STREAM,
    )

    private fun enqueueOk(n: Int) {
        repeat(n) {
            server.enqueue(MockResponse().setResponseCode(204).setHeader("Connection", "Close"))
        }
    }

    private fun recordedPaths(requests: List<RecordedRequest>): List<String> =
        requests.map { it.requestUrl!!.encodedPath }

    // ==================================================================
    // 继续观看列表
    // ==================================================================

    private fun resumeJson() = """
        {"Items":[
          {"Id":"e1","Name":"第一集","Type":"Episode","SeriesId":"s1",
           "ParentIndexNumber":1,"IndexNumber":2,"RunTimeTicks":14400000000,
           "Container":"mkv",
           "UserData":{"PlaybackPositionTicks":900000000,"PlayedPercentage":6.25,
             "PlayCount":1,"IsFavorite":false}},
          {"Id":"e2","Name":"第二集","Type":"Episode","RunTimeTicks":14400000000},
          {"Id":"","Name":"无 id 条目","Type":"Episode"}
        ],"TotalRecordCount":3}
    """.trimIndent()

    @Test
    fun `continue watching parses resume items into media items`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody(resumeJson()))

        val items = provider().getContinueWatching(20)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/emby/Users/user-1/Items/Resume", request.requestUrl!!.encodedPath)
        assertEquals("20", request.requestUrl!!.queryParameter("Limit"))
        assertEquals("Video", request.requestUrl!!.queryParameter("MediaTypes"))
        assertTrue(
            "必须请求 UserData 才能拿到续播位置",
            request.requestUrl!!.queryParameter("Fields")!!.contains("UserData"),
        )
        assertEquals("tok-1", request.getHeader("X-Emby-Token"))

        assertEquals("映射失败（空 id）的条目必须被过滤", 2, items.size)
        assertEquals(listOf("e1", "e2"), items.map { it.id })
        assertEquals("srv-1", items[0].serverId)
        assertEquals("第一集", items[0].title)
        assertEquals(90_000L, items[0].userData?.playbackPositionMs)
        assertEquals(1_440_000L, items[0].runtimeMs)
        assertNull("缺 UserData 的条目不伪造进度", items[1].userData?.playbackPositionMs)
    }

    @Test
    fun `continue watching limit is clamped to one through fifty`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Items":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Items":[]}"""))

        val p = provider()
        p.getContinueWatching(0)
        p.getContinueWatching(999)

        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("Limit"))
        assertEquals("50", server.takeRequest().requestUrl!!.queryParameter("Limit"))
    }

    @Test
    fun `resume endpoint 404 yields empty list and does not throw`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(404))

        val items = provider().getContinueWatching(20)

        assertTrue("老服务器/兼容服没有 Resume 端点属能力缺失", items.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `resume endpoint 405 yields empty list and does not throw`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(405))

        val items = provider().getContinueWatching(20)

        assertTrue(items.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `resume endpoint other errors are still mapped`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(500))

        val thrown = runCatching { provider().getContinueWatching(20) }.exceptionOrNull()

        assertTrue("500 必须照常映射（实际：$thrown）", thrown is ProviderException.Http)
    }

    // ==================================================================
    // 单条续播位置
    // ==================================================================

    @Test
    fun `resume position reads user data playback position as milliseconds`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Id":"m1","Name":"电影A","UserData":{"PlaybackPositionTicks":900000000}}"""
            )
        )

        val positionMs = provider().getResumePosition("m1")

        val request = server.takeRequest()
        assertEquals("/emby/Users/user-1/Items/m1", request.requestUrl!!.encodedPath)
        assertEquals("UserData", request.requestUrl!!.queryParameter("Fields"))
        assertEquals(90_000L, positionMs)
    }

    @Test
    fun `resume position missing user data yields null`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Id":"m1","Name":"电影A"}"""))

        assertNull(provider().getResumePosition("m1"))
    }

    @Test
    fun `resume position zero ticks yields null`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Id":"m1","UserData":{"PlaybackPositionTicks":0}}"""
            )
        )

        assertNull(provider().getResumePosition("m1"))
    }

    // ==================================================================
    // 兼容回写：最终 Progress 在 Stopped 之前
    // ==================================================================

    @Test
    fun `compat session posts final progress before stopped`() = runBlocking {
        seedSession()
        enqueueOk(4) // Playing + Progress + 兼容 Progress + Stopped
        val p = provider()
        p.reportProgress(progress(positionMs = 90_000, sessionId = "mh-compat-1"))
        p.reportFinalProgress(progress(positionMs = 300_000, sessionId = "mh-compat-1"))

        val requests = List(4) { server.takeRequest() }
        assertEquals(
            "兼容会话必须先发最终 Progress 再发 Stopped",
            listOf(
                "/emby/Sessions/Playing",
                "/emby/Sessions/Playing/Progress",
                "/emby/Sessions/Playing/Progress",
                "/emby/Sessions/Playing/Stopped",
            ),
            recordedPaths(requests),
        )
        val finalProgressBody = requests[2].body.readUtf8()
        assertTrue(finalProgressBody.contains("\"ItemId\":\"m1\""))
        assertTrue(finalProgressBody.contains("\"PlaySessionId\":\"mh-compat-1\""))
        assertTrue(finalProgressBody.contains("\"PositionTicks\":3000000000"))
        assertTrue("最终 Progress 必须标记为暂停", finalProgressBody.contains("\"IsPaused\":true"))

        val stoppedBody = requests[3].body.readUtf8()
        assertTrue(stoppedBody.contains("\"PositionTicks\":3000000000"))
        assertTrue(stoppedBody.contains("\"PlaySessionId\":\"mh-compat-1\""))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `server provided session keeps playing progress stopped order`() = runBlocking {
        seedSession()
        enqueueOk(3)
        val p = provider()
        p.reportProgress(progress(positionMs = 90_000))
        p.reportFinalProgress(progress(positionMs = 300_000))

        val requests = List(3) { server.takeRequest() }
        assertEquals(
            listOf(
                "/emby/Sessions/Playing",
                "/emby/Sessions/Playing/Progress",
                "/emby/Sessions/Playing/Stopped",
            ),
            recordedPaths(requests),
        )
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `compat final progress failure does not block stopped`() = runBlocking {
        seedSession()
        enqueueOk(2) // Playing + Progress
        server.enqueue(MockResponse().setResponseCode(500).setHeader("Connection", "Close")) // 兼容 Progress 失败
        enqueueOk(1) // Stopped 仍必须发出
        val p = provider()
        p.reportProgress(progress(positionMs = 90_000, sessionId = "mh-compat-2"))

        p.reportFinalProgress(progress(positionMs = 300_000, sessionId = "mh-compat-2"))

        val requests = List(4) { server.takeRequest() }
        assertEquals(
            listOf(
                "/emby/Sessions/Playing",
                "/emby/Sessions/Playing/Progress",
                "/emby/Sessions/Playing/Progress",
                "/emby/Sessions/Playing/Stopped",
            ),
            recordedPaths(requests),
        )
    }

    @Test
    fun `compat short playback final still opens session then progress then stopped`() = runBlocking {
        seedSession()
        enqueueOk(3) // 补发 Playing + 兼容 Progress + Stopped
        provider().reportFinalProgress(progress(positionMs = 8_000, sessionId = "mh-compat-3"))

        val requests = List(3) { server.takeRequest() }
        assertEquals(
            listOf(
                "/emby/Sessions/Playing",
                "/emby/Sessions/Playing/Progress",
                "/emby/Sessions/Playing/Stopped",
            ),
            recordedPaths(requests),
        )
        assertTrue(requests[1].body.readUtf8().contains("\"PositionTicks\":80000000"))
        assertTrue(requests[2].body.readUtf8().contains("\"PositionTicks\":80000000"))
    }

    @Test
    fun `continue watching requires session and sends no http without one`() = runBlocking {
        val thrown = runCatching { provider().getContinueWatching(20) }.exceptionOrNull()

        if (thrown !is ProviderException.AuthRequired) fail("必须抛 AuthRequired（实际：$thrown）")
        assertEquals(0, server.requestCount)
    }
}
