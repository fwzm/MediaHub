package com.mediahub.provider.jellyfin.progress

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
import com.mediahub.provider.jellyfin.api.JellyfinApiClient
import com.mediahub.provider.jellyfin.api.JellyfinAuthorizationHeaderBuilder
import com.mediahub.provider.jellyfin.api.JellyfinEndpointResolver
import com.mediahub.provider.jellyfin.session.JellyfinSession
import com.mediahub.provider.jellyfin.session.JellyfinSessionStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Jellyfin 进度上报（Phase 1G-C）MockWebServer 测试：
 * Playing（首报）/ Progress（后续）/ Stopped（条目切换 final stop）/ ticks 换算 /
 * IsPaused / 续播位置 / 继续观看 wire / 错误与取消。
 */
class JellyfinProgressProviderTest {

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

    private fun provider(client: OkHttpClient = OkHttpClient()): JellyfinProgressProvider {
        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        val api = JellyfinApiClient(
            endpointResolver = JellyfinEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = JellyfinAuthorizationHeaderBuilder(identity),
            logger = logger,
        )
        return JellyfinProgressProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(JellyfinSession("srv-1", "remote-1", "user-1", "Alice"))
    }

    private fun progress(itemId: String, positionMs: Long, paused: Boolean = false) = PlaybackProgress(
        serverId = "srv-1", itemId = itemId, positionMs = positionMs, durationMs = 600_000,
        isPaused = paused, updatedAtEpochMs = 0, mode = PlaybackMode.DIRECT_STREAM,
    )

    // ---- 1：同一条目——首报 Playing + Progress；后续仅 Progress ----

    @Test
    fun `first report posts playing then progress`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        val p = provider()

        p.reportProgress(progress("m1", 90_000))

        val start = server.takeRequest()
        assertEquals("/Sessions/Playing", start.requestUrl!!.encodedPath)
        val startBody = start.body.readUtf8()
        assertTrue(startBody.contains("\"ItemId\":\"m1\""))
        assertTrue(startBody.contains("\"PositionTicks\":900000000"))

        val update = server.takeRequest()
        assertEquals("/Sessions/Playing/Progress", update.requestUrl!!.encodedPath)
        val updateBody = update.body.readUtf8()
        assertTrue(updateBody.contains("\"ItemId\":\"m1\""))
        assertTrue(updateBody.contains("\"PositionTicks\":900000000"))
        assertTrue(updateBody.contains("\"IsPaused\":false"))

        // 同一条目第二次上报：仅 Progress
        server.enqueue(MockResponse().setResponseCode(200))
        p.reportProgress(progress("m1", 120_000, paused = true))
        val second = server.takeRequest()
        assertEquals("/Sessions/Playing/Progress", second.requestUrl!!.encodedPath)
        assertTrue(second.body.readUtf8().contains("\"IsPaused\":true"))
    }

    // ---- 2：条目切换——先为上一条目补发 Stopped（final stop reporting），再开新会话 ----

    @Test
    fun `item switch posts stopped for previous item before starting new session`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        val p = provider()
        p.reportProgress(progress("m1", 90_000))
        server.takeRequest()
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        p.reportProgress(progress("m2", 30_000))

        val stopped = server.takeRequest()
        assertEquals("/Sessions/Playing/Stopped", stopped.requestUrl!!.encodedPath)
        assertTrue(stopped.body.readUtf8().contains("\"ItemId\":\"m1\""))

        val start = server.takeRequest()
        assertEquals("/Sessions/Playing", start.requestUrl!!.encodedPath)
        assertTrue(start.body.readUtf8().contains("\"ItemId\":\"m2\""))

        val progressRequest = server.takeRequest()
        assertEquals("/Sessions/Playing/Progress", progressRequest.requestUrl!!.encodedPath)
    }

    // ---- 2b：最终退出上报（shared finality hook）——只发 Stopped，带最终 PositionTicks ----

    @Test
    fun `final report posts stopped only with final position`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        val p = provider()
        p.reportProgress(progress("m1", 90_000))
        server.takeRequest()
        server.takeRequest()

        p.reportFinalProgress(progress("m1", 300_000))

        val stopped = server.takeRequest()
        assertEquals("/Sessions/Playing/Stopped", stopped.requestUrl!!.encodedPath)
        val body = stopped.body.readUtf8()
        assertTrue(body.contains("\"ItemId\":\"m1\""))
        assertTrue(body.contains("\"PositionTicks\":3000000000"))
        assertEquals("final 后无其他请求", 3, server.requestCount)
    }

    // ---- 2c：switch-Stopped 的取消必须穿透，且不得继续新会话任何请求 ----
    // 注入方式：StoppedCancellingApi 在 playbackStopped 抛 CancellationException
    // （wire 层 flake 无法确定性注入取消；open seam 为最小测试设施）。

    private class StoppedCancellingApi(
        endpointResolver: JellyfinEndpointResolver,
        apiClient: ApiClient,
        authHeaderBuilder: JellyfinAuthorizationHeaderBuilder,
        logger: StdoutLogger,
    ) : JellyfinApiClient(endpointResolver, apiClient, authHeaderBuilder, logger) {
        override suspend fun playbackStopped(token: String, itemId: String, positionTicks: Long?) {
            throw CancellationException("cancelled at switch")
        }
    }

    @Test
    fun `switch stopped cancellation propagates and blocks new session`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(200)) // m1 Playing
        server.enqueue(MockResponse().setResponseCode(200)) // m1 Progress

        val logger = StdoutLogger()
        val identity = ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        val apiClient = ApiClient(OkHttpClient(), logger = logger)
        val api = StoppedCancellingApi(
            JellyfinEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient,
            JellyfinAuthorizationHeaderBuilder(identity),
            logger,
        )
        val p = JellyfinProgressProvider(mediaServer, api, tokenStore, sessionStore, logger)

        p.reportProgress(progress("m1", 90_000)) // Playing + Progress 正常落 wire
        server.takeRequest()
        server.takeRequest()

        val thrown = try {
            p.reportProgress(progress("m2", 30_000)) // Stopped → 取消
            null
        } catch (e: CancellationException) {
            e
        } catch (e: Throwable) {
            e
        }

        assertTrue("取消必须穿透（实际：$thrown）", thrown is CancellationException)
        assertEquals("取消后不得继续 m2 的 Playing/Progress", 2, server.requestCount)
    }

    // ---- 2d：并发 reportProgress 串行——1× Playing + 2× Progress，绝不重复 Playing ----

    @Test
    fun `concurrent reports of same item serialize into single playing session`() = runBlocking {
        seedSession()
        repeat(6) { server.enqueue(MockResponse().setResponseCode(200)) }
        val p = provider()

        val jobs = listOf(
            launch { p.reportProgress(progress("m1", 90_000)) },
            launch { p.reportProgress(progress("m1", 120_000)) },
        )
        jobs.forEach { it.join() }

        val paths = mutableListOf<String>()
        while (server.requestCount > paths.size) {
            paths += server.takeRequest(1000, java.util.concurrent.TimeUnit.MILLISECONDS)!!.path!!
        }
        assertEquals(1, paths.count { it == "/Sessions/Playing" })
        assertEquals(2, paths.count { it == "/Sessions/Playing/Progress" })
    }

    // ---- 3：续播位置（UserData ticks → ms；零值 null） ----

    @Test
    fun `resume position converts ticks to millis and nulls zero`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Id":"m1","UserData":{"PlaybackPositionTicks":900000000}}"""
            )
        )
        assertEquals(90_000L, provider().getResumePosition("m1"))

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Id":"m1","UserData":{"PlaybackPositionTicks":0}}"""
            )
        )
        assertNull(provider().getResumePosition("m1"))
    }

    // ---- 4：继续观看（IsResumable filter wire） ----

    @Test
    fun `continue watching uses is resumable filter wire`() = runBlocking {
        seedSession()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Items":[
                    {"Id":"m1","Name":"Fargo","Type":"Movie","ProviderIds":{"Tmdb":"550"}}
                ],"TotalRecordCount":1}"""
            )
        )

        val items = provider().getContinueWatching(10)

        assertEquals(1, items.size)
        assertEquals("550", items[0].externalIds?.tmdb)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/Items", url.encodedPath)
        assertEquals("IsResumable", url.queryParameter("Filters"))
        assertEquals("true", url.queryParameter("Recursive"))
        assertEquals("10", url.queryParameter("Limit"))
        assertTrue(url.queryParameter("Fields")!!.contains("ProviderIds"))
    }

    // ---- 5：错误与取消 ----

    @Test
    fun `401 maps to auth expired`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            provider().reportProgress(progress("m1", 1_000))
            fail("必须抛 AuthExpired")
        } catch (_: ProviderException.AuthExpired) {
        }
    }

    @Test
    fun `cancellation propagates unchanged`() = runBlocking {
        seedSession()
        val cancellingClient = OkHttpClient.Builder()
            .addInterceptor { throw CancellationException("scope cancelled") }
            .build()
        val thrown = try {
            provider(cancellingClient).reportProgress(progress("m1", 1_000))
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
