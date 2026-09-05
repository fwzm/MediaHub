package com.mediahub.provider.emby.progress

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
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
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Emby 进度上报（Phase 1H，Emby PROGRESS closeout）测试：
 *
 * - 生命周期/并发/状态：RecordingApi seam（EmbyApiClient open 子类）内存记录
 *   wire 派发序列 + CompletableDeferred barrier 卡住真实在途请求——
 *   不用"launch + advanceUntilIdle"式伪并发；
 * - 真实 wire 细节（路径/头/Content-Type/Token 位置/失败语义）：
 *   MockWebServer 实请求断言。
 *
 * **enqueue 纪律**：每个预期请求恰好一个 204 响应 + `Connection: Close`——
 * POST 不会被 RetryOnceInterceptor 重试（仅 GET/HEAD/DELETE），但缺响应会
 * OkHttp read timeout；204 不 drain 请求体时连接复用可能分帧错位。
 *
 * 状态机（Mutex 原子转移；active 身份 = (itemId, PlaySessionId)）：同会话首报 →
 * Playing + Progress；后续 → Progress；条目/会话切换 → Stopped(上一会话，旧 PSID，
 * best-effort) + Playing(新) + Progress；final 三态收口：无前导 → Playing + Stopped /
 * 同会话 → Stopped / 换条目或换会话 → Stopped(旧, 旧 PSID) + Playing + Stopped(新)；
 * final 后同条目再报 → 新 Playing。PlaySessionId 必须来自 PlaybackInfo（fail-closed，
 * 缺失 0 HTTP，ADR-040 correction）。
 */
class EmbyProgressProviderTest {

    // ---- Recording seam：内存记录派发序列 + barrier 卡真实在途请求 ----

    private data class WireCall(
        val kind: String, // Playing / Progress / Stopped
        val itemId: String,
        val playSessionId: String,
        val positionTicks: Long,
        val isPaused: Boolean?,
        val playMethod: String?,
        val token: String,
        val userId: String,
    )

    private class RecordingApi(
        logger: Logger = StdoutLogger(),
    ) : EmbyApiClient(
        endpointResolver = EmbyEndpointResolver("https://emby.example"),
        apiClient = ApiClient(OkHttpClient(), logger = logger),
        authHeaderBuilder = EmbyAuthorizationHeaderBuilder(
            ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
        ),
        logger = logger,
    ) {
        val calls = mutableListOf<WireCall>()

        /** 置位后 playbackProgress 派发后阻塞，直到 complete（模拟在途网络）。 */
        var blockProgress: CompletableDeferred<Unit>? = null

        /**
         * 本次 playbackProgress 派发信号。var + 每用例重置：同一 RecordingApi 的
         * 早期上报会 complete 掉共享 deferred，陈旧信号会让 await() 提前返回、
         * barrier 失去"在途"锁定语义（CI flake 根因）。
         */
        var progressEntered = CompletableDeferred<Unit>()

        /** 置位后 playbackStopped 派发后阻塞，直到 complete。 */
        var blockStopped: CompletableDeferred<Unit>? = null
        var stoppedEntered = CompletableDeferred<Unit>()

        /** 非空时 playbackProgress 抛错（模拟 wire 失败，不经 HTTP）。 */
        var progressError: Exception? = null

        /** true 时 playbackStopped 抛取消（模拟切换补发处的取消注入）。 */
        var cancelSwitchStopped = false

        override suspend fun playbackStart(
            token: String,
            userId: String,
            itemId: String,
            playSessionId: String,
            positionTicks: Long,
            playMethod: String?,
        ) {
            calls += WireCall("Playing", itemId, playSessionId, positionTicks, null, playMethod, token, userId)
        }

        override suspend fun playbackProgress(
            token: String,
            userId: String,
            itemId: String,
            playSessionId: String,
            positionTicks: Long,
            isPaused: Boolean?,
            playMethod: String?,
        ) {
            progressError?.let { throw it }
            calls += WireCall("Progress", itemId, playSessionId, positionTicks, isPaused, playMethod, token, userId)
            progressEntered.complete(Unit)
            blockProgress?.await()
        }

        override suspend fun playbackStopped(
            token: String,
            userId: String,
            itemId: String,
            playSessionId: String,
            positionTicks: Long,
        ) {
            if (cancelSwitchStopped) throw CancellationException("cancelled at switch stopped")
            calls += WireCall("Stopped", itemId, playSessionId, positionTicks, null, null, token, userId)
            stoppedEntered.complete(Unit)
            blockStopped?.await()
        }
    }

    private class CapturingLogger : Logger {
        val lines = mutableListOf<String>()
        override fun d(tag: LogTag, message: String) { lines += message }
        override fun i(tag: LogTag, message: String) { lines += message }
        override fun w(tag: LogTag, message: String, throwable: Throwable?) {
            lines += message + (throwable?.message?.let { " | $it" } ?: "")
        }
        override fun e(tag: LogTag, message: String, throwable: Throwable?) {
            lines += message + (throwable?.message?.let { " | $it" } ?: "")
        }
    }

    // ---- fakes / helpers ----

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

    private val mediaServerB = MediaServer(
        id = "srv-2", name = "Emby B", type = ServerType.EMBY,
        baseUrl = "http://localhost", createdAtEpochMs = 0,
    )

    /** PlaybackProgress 构造：sessionId 默认来自 PlaybackInfo 真值（真机契约）。 */
    private fun progress(
        itemId: String = "m1",
        positionMs: Long = 90_000,
        paused: Boolean = false,
        mode: PlaybackMode? = PlaybackMode.DIRECT_STREAM,
        durationMs: Long = 600_000,
        sessionId: String? = "psid-1",
    ) = PlaybackProgress(
        serverId = "srv-1", itemId = itemId, positionMs = positionMs, durationMs = durationMs,
        isPaused = paused, updatedAtEpochMs = 0, sessionId = sessionId, mode = mode,
    )

    /** Recording seam provider（无 HTTP）。 */
    private fun provider(api: RecordingApi, logger: Logger = StdoutLogger()): EmbyProgressProvider =
        EmbyProgressProvider(mediaServer, api, tokenStore, sessionStore, logger)

    /** 真实 wire provider（MockWebServer + 真实 EmbyApiClient）。 */
    private fun realProvider(
        logger: Logger = StdoutLogger(),
        client: OkHttpClient = OkHttpClient(),
    ): EmbyProgressProvider {
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(
                ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
            ),
            logger = logger,
        )
        return EmbyProgressProvider(mediaServer, api, tokenStore, sessionStore, logger)
    }

    private suspend fun seedSession(serverId: String = "srv-1", token: String = "tok-1", userId: String = "user-1") {
        tokenStore.saveTokens(serverId, StoredToken(accessToken = token))
        sessionStore.save(EmbySession(serverId, "remote-1", userId, "Alice"))
    }

    /** 每个预期请求恰好一个 204 + Connection: Close（确定性分帧，禁连接复用）。 */
    private fun enqueueOk(n: Int) {
        repeat(n) {
            server.enqueue(MockResponse().setResponseCode(204).setHeader("Connection", "Close"))
        }
    }

    private fun kinds(api: RecordingApi): List<String> = api.calls.map { it.kind }

    // ==================================================================
    // wire 契约（MockWebServer 实请求）
    // ==================================================================

    @Test
    fun `first report posts playing with full wire contract`() = runBlocking {
        seedSession()
        enqueueOk(2) // Playing + Progress
        realProvider().reportProgress(progress(positionMs = 90_000))

        val playing = server.takeRequest()
        assertEquals("/emby/Sessions/Playing", playing.requestUrl!!.encodedPath)
        assertEquals("POST", playing.method)
        assertEquals("tok-1", playing.getHeader("X-Emby-Token"))
        val auth = playing.getHeader("X-Emby-Authorization")!!
        assertTrue(auth.startsWith("Emby "))
        assertTrue(auth.contains("UserId=\"user-1\""))
        assertTrue(playing.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = playing.body.readUtf8()
        assertTrue(body.contains("\"ItemId\":\"m1\""))
        assertTrue("PlaySessionId 必须为 PlaybackInfo 真值", body.contains("\"PlaySessionId\":\"psid-1\""))
        assertTrue(body.contains("\"PositionTicks\":900000000"))
        assertTrue(body.contains("\"PlayMethod\":\"DirectStream\""))
        assertFalse("start body 不含 IsPaused", body.contains("IsPaused"))
        // Token 红线：不进 URL / query / body
        assertFalse(playing.requestUrl!!.toString().contains("tok-1"))
        assertEquals(null, playing.requestUrl!!.queryParameter("api_key"))
        assertFalse(body.contains("tok-1"))

        val update = server.takeRequest()
        assertEquals("/emby/Sessions/Playing/Progress", update.requestUrl!!.encodedPath)
        assertEquals("tok-1", update.getHeader("X-Emby-Token"))
        val updateBody = update.body.readUtf8()
        assertTrue(updateBody.contains("\"ItemId\":\"m1\""))
        assertTrue(updateBody.contains("\"PlaySessionId\":\"psid-1\""))
        assertTrue(updateBody.contains("\"PositionTicks\":900000000"))
        assertTrue(updateBody.contains("\"IsPaused\":false"))
    }

    @Test
    fun `final report posts stopped with final position and minimal body`() = runBlocking {
        seedSession()
        enqueueOk(3) // Playing + Progress + Stopped
        val p = realProvider()
        p.reportProgress(progress(positionMs = 90_000))
        p.reportFinalProgress(progress(positionMs = 300_000))

        server.takeRequest() // Playing
        server.takeRequest() // Progress
        val stopped = server.takeRequest()
        assertEquals("/emby/Sessions/Playing/Stopped", stopped.requestUrl!!.encodedPath)
        assertEquals("POST", stopped.method)
        assertEquals("tok-1", stopped.getHeader("X-Emby-Token"))
        assertTrue(stopped.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = stopped.body.readUtf8()
        assertTrue(body.contains("\"ItemId\":\"m1\""))
        assertTrue("Stopped 必须带 active 会话的 PlaySessionId", body.contains("\"PlaySessionId\":\"psid-1\""))
        assertTrue(body.contains("\"PositionTicks\":3000000000"))
        // DTO 契约收缩：Stopped 只带 ItemId + PlaySessionId + PositionTicks
        assertFalse(body.contains("IsPaused"))
        assertFalse(body.contains("PlayMethod"))
        assertFalse(body.contains("tok-1"))
        assertEquals("final 后无其他请求", 3, server.requestCount)
    }

    @Test
    fun `real wire short playback final without prior report posts playing then stopped`() = runBlocking {
        seedSession()
        enqueueOk(2) // 补发 Playing + Stopped
        // <10s 短播放：coordinator sample 未触发，从冷 provider 直接 final
        realProvider().reportFinalProgress(progress(positionMs = 8_000))

        val playing = server.takeRequest()
        assertEquals("/emby/Sessions/Playing", playing.requestUrl!!.encodedPath)
        assertEquals("POST", playing.method)
        assertEquals("tok-1", playing.getHeader("X-Emby-Token"))
        val auth = playing.getHeader("X-Emby-Authorization")!!
        assertTrue(auth.startsWith("Emby "))
        assertTrue(auth.contains("UserId=\"user-1\""))
        val playingBody = playing.body.readUtf8()
        assertTrue(playingBody.contains("\"ItemId\":\"m1\""))
        assertTrue(playingBody.contains("\"PlaySessionId\":\"psid-1\""))
        assertTrue(playingBody.contains("\"PositionTicks\":80000000"))
        // Token 红线：不进 URL
        assertFalse(playing.requestUrl!!.toString().contains("tok-1"))

        val stopped = server.takeRequest()
        assertEquals("/emby/Sessions/Playing/Stopped", stopped.requestUrl!!.encodedPath)
        assertEquals("tok-1", stopped.getHeader("X-Emby-Token"))
        val stoppedBody = stopped.body.readUtf8()
        assertTrue(stoppedBody.contains("\"ItemId\":\"m1\""))
        assertTrue(stoppedBody.contains("\"PlaySessionId\":\"psid-1\""))
        assertTrue(stoppedBody.contains("\"PositionTicks\":80000000"))
        assertFalse("Stopped 只带 ItemId + PlaySessionId + PositionTicks", stoppedBody.contains("IsPaused") || stoppedBody.contains("PlayMethod"))
        assertFalse(stoppedBody.contains("tok-1"))
        assertEquals("短播放 final 恰两个请求", 2, server.requestCount)
    }

    @Test
    fun `paused state maps on the wire`() = runBlocking {
        seedSession()
        enqueueOk(3) // Playing + Progress(paused=false) + Progress(paused=true)
        val p = realProvider()
        p.reportProgress(progress(positionMs = 90_000))
        p.reportProgress(progress(positionMs = 120_000, paused = true))

        server.takeRequest() // Playing
        server.takeRequest() // Progress
        val paused = server.takeRequest()
        assertEquals("/emby/Sessions/Playing/Progress", paused.requestUrl!!.encodedPath)
        assertTrue(paused.body.readUtf8().contains("\"IsPaused\":true"))
    }

    // ==================================================================
    // PositionTicks 语义（Recording seam）
    // ==================================================================

    @Test
    fun `position zero sends explicit zero ticks`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        provider(api).reportProgress(progress(positionMs = 0))

        assertEquals(listOf("Playing", "Progress"), kinds(api))
        assertEquals(listOf(0L, 0L), api.calls.map { it.positionTicks })
    }

    @Test
    fun `negative position clamps to zero ticks`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        provider(api).reportProgress(progress(positionMs = -5_000))

        assertEquals(listOf(0L, 0L), api.calls.map { it.positionTicks })
    }

    @Test
    fun `overflow position clamps to long max value`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)
        p.reportProgress(progress(positionMs = Long.MAX_VALUE))
        p.reportProgress(progress(positionMs = Long.MAX_VALUE / 2)) // ×10_000 会溢出为负
        p.reportProgress(progress(positionMs = Long.MAX_VALUE / 10_000 + 1))

        val ticks = api.calls.map { it.positionTicks }
        assertEquals(Long.MAX_VALUE, ticks[0])
        assertEquals(Long.MAX_VALUE, ticks[1])
        assertEquals(Long.MAX_VALUE, ticks[2])
        assertTrue("钳制后不得为负", ticks.all { it >= 0 })
    }

    @Test
    fun `position beyond duration passes through unchanged`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        provider(api).reportProgress(progress(positionMs = 900_000, durationMs = 600_000))

        assertEquals(listOf(9_000_000_000L, 9_000_000_000L), api.calls.map { it.positionTicks })
    }

    @Test
    fun `play method maps from playback mode and omits when unknown`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)
        p.reportProgress(progress(mode = PlaybackMode.DIRECT_PLAY))
        p.reportProgress(progress(positionMs = 120_000, mode = PlaybackMode.TRANSCODE))
        p.reportProgress(progress(positionMs = 150_000, mode = PlaybackMode.UNSUPPORTED))
        p.reportProgress(progress(positionMs = 180_000, mode = null))

        assertEquals("DirectPlay", api.calls.first { it.kind == "Playing" }.playMethod)
        val progressMethods = api.calls.filter { it.kind == "Progress" }.map { it.playMethod }
        assertEquals(listOf("DirectPlay", "Transcode", null, null), progressMethods)
    }

    // ==================================================================
    // 生命周期（Recording seam；active 身份 = (itemId, PlaySessionId)）
    // ==================================================================

    @Test
    fun `playing exactly once across multiple progress reports`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)
        p.reportProgress(progress(positionMs = 90_000))
        p.reportProgress(progress(positionMs = 120_000))
        p.reportProgress(progress(positionMs = 150_000))

        assertEquals(listOf("Playing", "Progress", "Progress", "Progress"), kinds(api))
        assertTrue(api.calls.all { it.itemId == "m1" })
        assertTrue("同会话全程同一 PlaySessionId", api.calls.all { it.playSessionId == "psid-1" })
    }

    @Test
    fun `final posts stopped exactly once and closes session`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)
        p.reportProgress(progress(positionMs = 90_000))
        p.reportFinalProgress(progress(positionMs = 300_000))

        assertEquals(listOf("Playing", "Progress", "Stopped"), kinds(api))
        val stopped = api.calls.last()
        assertEquals("m1", stopped.itemId)
        assertEquals(3_000_000_000L, stopped.positionTicks)
        assertEquals("Stopped 用 active 会话的 PlaySessionId", "psid-1", stopped.playSessionId)
    }

    @Test
    fun `final without prior report posts playing then stopped - never bare stopped`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        // <10s 短播放：coordinator sample 未触发，从冷 provider 直接 final
        provider(api).reportFinalProgress(progress(positionMs = 8_000))

        assertEquals("禁止裸 Stopped：必须先补 Playing", listOf("Playing", "Stopped"), kinds(api))
        assertEquals(80_000_000L, api.calls[0].positionTicks)
        assertEquals(80_000_000L, api.calls[1].positionTicks)
        assertEquals("补发 Playing 用 progress.sessionId", "psid-1", api.calls[0].playSessionId)
        assertEquals("补发 Stopped 用同一 PlaySessionId", "psid-1", api.calls[1].playSessionId)
    }

    @Test
    fun `no bare progress after final - same item reopens with playing`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)
        p.reportProgress(progress(positionMs = 90_000))
        p.reportFinalProgress(progress(positionMs = 300_000))
        p.reportProgress(progress(positionMs = 60_000))

        // final 关闭会话后，同条目再上报必须开新会话（Playing + Progress），禁止裸 Progress
        assertEquals(listOf("Playing", "Progress", "Stopped", "Playing", "Progress"), kinds(api))
    }

    @Test
    fun `new item switches session with stopped for previous`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)
        p.reportProgress(progress(itemId = "m1", positionMs = 90_000))
        p.reportProgress(progress(itemId = "m2", positionMs = 30_000, sessionId = "psid-2"))

        assertEquals(listOf("Playing", "Progress", "Stopped", "Playing", "Progress"), kinds(api))
        val stopped = api.calls[2]
        assertEquals("m1", stopped.itemId)
        assertEquals("上一条目最后位置补发", 900_000_000L, stopped.positionTicks)
        assertEquals("关旧条目必须用旧条目自己的 PlaySessionId", "psid-1", stopped.playSessionId)
        assertEquals("m2", api.calls[3].itemId)
        assertEquals("新条目 Playing 用新 PlaySessionId", "psid-2", api.calls[3].playSessionId)
        assertEquals("m2", api.calls[4].itemId)
        assertEquals("psid-2", api.calls[4].playSessionId)
    }

    @Test
    fun `switch stopped uses last reported position of previous item`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)
        p.reportProgress(progress(itemId = "m1", positionMs = 90_000))
        p.reportProgress(progress(itemId = "m1", positionMs = 120_000))
        p.reportProgress(progress(itemId = "m2", positionMs = 30_000, sessionId = "psid-2"))

        val stopped = api.calls.first { it.kind == "Stopped" }
        assertEquals("m1", stopped.itemId)
        assertEquals("psid-1", stopped.playSessionId)
        assertEquals(1_200_000_000L, stopped.positionTicks)
    }

    @Test
    fun `same item with new play session id reopens session with old closed`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)
        p.reportProgress(progress(itemId = "m1", positionMs = 90_000, sessionId = "psid-1"))
        // 同条目重新 resolve 出新 PlaySessionId（无 final 穿插）：按会话替换处理
        p.reportProgress(progress(itemId = "m1", positionMs = 120_000, sessionId = "psid-2"))

        assertEquals(listOf("Playing", "Progress", "Stopped", "Playing", "Progress"), kinds(api))
        assertEquals("旧会话用旧 PSID 关闭", "psid-1", api.calls[2].playSessionId)
        assertEquals("m1", api.calls[2].itemId)
        assertEquals("新会话用新 PSID 开启", "psid-2", api.calls[3].playSessionId)
        assertEquals("psid-2", api.calls[4].playSessionId)
    }

    @Test
    fun `final on new item closes previous session first then playing stopped for final`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)
        p.reportProgress(progress(itemId = "m1", positionMs = 90_000))
        p.reportProgress(progress(itemId = "m1", positionMs = 120_000))
        // 切到 m2 后不足一个 sample 周期即退出：m2 从未 reportProgress，final 直接落在 m2
        p.reportFinalProgress(progress(itemId = "m2", positionMs = 8_000, sessionId = "psid-2"))

        assertEquals(
            "旧会话必须先关，再为 m2 补 Playing + Stopped（旧 server session 不得残留）",
            listOf("Playing", "Progress", "Progress", "Stopped", "Playing", "Stopped"),
            kinds(api),
        )
        val stopPrevious = api.calls[3]
        assertEquals("m1", stopPrevious.itemId)
        assertEquals("旧条目按 item-switch 规则用最后已知位置+旧 PSID 关闭", "psid-1", stopPrevious.playSessionId)
        assertEquals(1_200_000_000L, stopPrevious.positionTicks)
        assertEquals("m2", api.calls[4].itemId)
        assertEquals("m2", api.calls[5].itemId)
        assertEquals("final 条目补 Playing 用自己的 PSID", "psid-2", api.calls[4].playSessionId)
        assertEquals("final Stopped 用自己的 PSID", "psid-2", api.calls[5].playSessionId)
        assertEquals(80_000_000L, api.calls[4].positionTicks)
        assertEquals(80_000_000L, api.calls[5].positionTicks)
    }

    // ==================================================================
    // PlaySessionId fail-closed（ADR-040 correction：缺失 0 HTTP、无 fallback）
    // ==================================================================

    @Test
    fun `missing session id fails closed without any http`() = runBlocking {
        seedSession()
        enqueueOk(2)
        val thrown = try {
            realProvider().reportProgress(progress(positionMs = 90_000, sessionId = null))
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue("必须抛 ProviderException（实际：$thrown）", thrown is ProviderException.Parse)
        assertEquals("fail-closed：0 HTTP", 0, server.requestCount)
    }

    @Test
    fun `blank session id fails closed without any http`() = runBlocking {
        seedSession()
        enqueueOk(2)
        val thrown = try {
            realProvider().reportFinalProgress(progress(positionMs = 90_000, sessionId = "   "))
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue("必须抛 ProviderException（实际：$thrown）", thrown is ProviderException.Parse)
        assertEquals("fail-closed：0 HTTP", 0, server.requestCount)
    }

    // ==================================================================
    // 并发与顺序（真实在途 + barrier；禁止 launch+advanceUntilIdle 伪并发）
    // ==================================================================

    @Test
    fun `in flight progress cannot be overtaken by final - stopped is last`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)
        p.reportProgress(progress(positionMs = 90_000)) // 正常首报

        api.blockProgress = CompletableDeferred()
        api.progressEntered = CompletableDeferred() // 首报已 complete 共享信号：重置后才能锁定本次派发点

        // 普通 report：Progress 派发后卡住在途（持有 Mutex）
        val reportJob = launch { p.reportProgress(progress(positionMs = 120_000)) }
        api.progressEntered.await()
        assertTrue("Progress 必须已派发在途", kinds(api).last() == "Progress")

        // final 请求：只能排队等 Mutex，不得越过在途 Progress
        val finalJob = launch { p.reportFinalProgress(progress(positionMs = 300_000)) }
        repeat(4) { yield() } // 确保 finalJob 到达 mutex 挂起点（CI 慢调度下单次 yield 不足）

        api.blockProgress!!.complete(Unit)
        reportJob.join()
        finalJob.join()

        assertEquals(listOf("Playing", "Progress", "Progress", "Stopped"), kinds(api))
        assertEquals("Stopped 必须最后", "Stopped", api.calls.last().kind)
        assertEquals(3_000_000_000L, api.calls.last().positionTicks)
    }

    @Test
    fun `in flight final cannot be overtaken by late progress`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)
        p.reportProgress(progress(positionMs = 90_000))

        api.blockStopped = CompletableDeferred()
        api.stoppedEntered = CompletableDeferred()

        // final：Stopped 派发后卡住在途（持有 Mutex）
        val finalJob = launch { p.reportFinalProgress(progress(positionMs = 300_000)) }
        api.stoppedEntered.await()
        assertTrue("Stopped 必须已派发在途", kinds(api).last() == "Stopped")

        // 迟到 report：只能排队等 Mutex，不得在 Stopped 前插入任何请求
        val lateJob = launch { p.reportProgress(progress(positionMs = 60_000)) }
        repeat(4) { yield() }

        api.blockStopped!!.complete(Unit)
        finalJob.join()
        lateJob.join()

        assertEquals(listOf("Playing", "Progress", "Stopped", "Playing", "Progress"), kinds(api))
        val stoppedIndex = api.calls.indexOfFirst { it.kind == "Stopped" }
        val reopenedPlayingIndex = api.calls.indexOfLast { it.kind == "Playing" }
        assertTrue("final 之后才允许新会话", stoppedIndex < reopenedPlayingIndex)
    }

    @Test
    fun `concurrent reports of same item serialize into single playing session`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)

        val jobs = listOf(
            launch { p.reportProgress(progress(positionMs = 90_000)) },
            launch { p.reportProgress(progress(positionMs = 120_000)) },
        )
        jobs.forEach { it.join() }

        val kinds = kinds(api)
        assertEquals("Playing 恰一次", 1, kinds.count { it == "Playing" })
        assertEquals(2, kinds.count { it == "Progress" })
        assertEquals("Playing 必须最先", "Playing", kinds.first())
    }

    @Test
    fun `switch stopped cancellation propagates and blocks new session`() = runBlocking {
        seedSession()
        val api = RecordingApi()
        val p = provider(api)
        p.reportProgress(progress(itemId = "m1", positionMs = 90_000))

        api.cancelSwitchStopped = true
        val thrown = try {
            p.reportProgress(progress(itemId = "m2", positionMs = 30_000, sessionId = "psid-2"))
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue("取消必须原样穿透（实际：$thrown）", thrown is CancellationException)
        assertEquals("取消后不得继续 m2 的 Playing/Progress", listOf("Playing", "Progress"), kinds(api))
        assertTrue(api.calls.none { it.itemId == "m2" })
    }

    // ==================================================================
    // 失败语义（MockWebServer 实请求）
    // ==================================================================

    @Test
    fun `start failure throws mapped error and next report reopens cleanly`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(500).setHeader("Connection", "Close"))
        val p = realProvider()

        try {
            p.reportProgress(progress(positionMs = 90_000))
            fail("必须抛 ProviderException.Http")
        } catch (_: ProviderException.Http) {
        }
        // 消费失败尝试的 Playing 请求（队列对齐）
        assertEquals("/emby/Sessions/Playing", server.takeRequest().requestUrl!!.encodedPath)

        // Playing 失败 → 会话状态未污染 → 下次上报完整重试
        enqueueOk(2)
        p.reportProgress(progress(positionMs = 120_000))
        assertEquals("/emby/Sessions/Playing", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/emby/Sessions/Playing/Progress", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `progress failure keeps session open - next report posts progress only`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(204).setHeader("Connection", "Close")) // Playing OK
        server.enqueue(MockResponse().setResponseCode(500).setHeader("Connection", "Close")) // Progress 失败
        val p = realProvider()

        try {
            p.reportProgress(progress(positionMs = 90_000))
            fail("必须抛 ProviderException.Http")
        } catch (_: ProviderException.Http) {
        }
        // 消费失败尝试的 Playing + Progress 请求（队列对齐）
        assertEquals("/emby/Sessions/Playing", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/emby/Sessions/Playing/Progress", server.takeRequest().requestUrl!!.encodedPath)

        // Playing 已成功 → 会话仍开着：不得重复 Playing，毒化不得扩散
        enqueueOk(1)
        p.reportProgress(progress(positionMs = 120_000))
        val next = server.takeRequest()
        assertEquals("/emby/Sessions/Playing/Progress", next.requestUrl!!.encodedPath)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `progress network failure does not poison later reporting`() = runBlocking {
        seedSession()
        // 请求完整读入后掐断连接（确定性被 MockWebServer 记录；客户端收 IOException）
        server.enqueue(
            MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        )
        val p = realProvider()

        try {
            p.reportProgress(progress(positionMs = 90_000))
            fail("必须抛 ProviderException.Network")
        } catch (_: ProviderException.Network) {
        }
        // 消费失败尝试的 Playing 请求（队列对齐）
        assertEquals("/emby/Sessions/Playing", server.takeRequest().requestUrl!!.encodedPath)

        enqueueOk(2)
        p.reportProgress(progress(positionMs = 120_000))
        assertEquals("/emby/Sessions/Playing", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/emby/Sessions/Playing/Progress", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `progress 401 maps to auth expired and does not clear session`() = runBlocking {
        seedSession()
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Connection", "Close"))

        try {
            realProvider().reportProgress(progress(positionMs = 90_000))
            fail("必须抛 AuthExpired")
        } catch (_: ProviderException.AuthExpired) {
        }

        // §10：一次 Progress 失败不得清理 auth 会话
        assertNotNull(tokenStore.readTokens("srv-1"))
        assertNotNull(sessionStore.read("srv-1"))
    }

    @Test
    fun `final stopped failure is best effort and does not block exit`() = runBlocking {
        seedSession()
        enqueueOk(2) // Playing + Progress
        server.enqueue(MockResponse().setResponseCode(500).setHeader("Connection", "Close")) // Stopped 失败
        val p = realProvider()
        p.reportProgress(progress(positionMs = 90_000))

        // 退出路径不得抛网络异常（coordinator flushFinal 短超时 contract 的 provider 侧配合）
        p.reportFinalProgress(progress(positionMs = 300_000))
        assertEquals(3, server.requestCount)

        // 会话状态仍须重置：下一段播放重新开 Playing
        enqueueOk(2)
        p.reportProgress(progress(positionMs = 60_000))
        assertEquals("/emby/Sessions/Playing", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `missing session throws auth required without any http`() = runBlocking {
        // 不 seed：无 token / 无会话
        try {
            realProvider().reportProgress(progress(positionMs = 90_000))
            fail("必须抛 AuthRequired")
        } catch (_: ProviderException.AuthRequired) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancellation propagates unchanged from network layer`() = runBlocking {
        seedSession()
        val cancellingClient = OkHttpClient.Builder()
            .addInterceptor { throw CancellationException("scope cancelled") }
            .build()
        val thrown = try {
            realProvider(client = cancellingClient).reportProgress(progress(positionMs = 1_000))
            null
        } catch (e: Throwable) {
            e
        }
        assertTrue("取消必须穿透（实际：$thrown）", thrown is CancellationException)
    }

    // ==================================================================
    // 安全（§11）
    // ==================================================================

    @Test
    fun `progress log messages never contain token or authorization`() = runBlocking {
        seedSession()
        val logger = CapturingLogger()
        enqueueOk(2)
        server.enqueue(MockResponse().setResponseCode(500).setHeader("Connection", "Close")) // Stopped 失败 → provider best-effort 日志
        val p = realProvider(logger = logger)
        p.reportProgress(progress(positionMs = 90_000))
        p.reportFinalProgress(progress(positionMs = 300_000))

        assertTrue("必须确实产生过日志", logger.lines.isNotEmpty())
        logger.lines.forEach { line ->
            assertFalse("日志含 token：$line", line.contains("tok-1"))
            assertFalse("日志含 Authorization 值：$line", line.contains("UserId=\"user-1\""))
        }
    }

    @Test
    fun `cross server token isolation`() = runBlocking {
        seedSession("srv-1", token = "tok-A", userId = "user-A")
        seedSession("srv-2", token = "tok-B", userId = "user-B")

        val apiA = RecordingApi()
        val apiB = RecordingApi()
        val providerA = EmbyProgressProvider(mediaServer, apiA, tokenStore, sessionStore, StdoutLogger())
        val providerB = EmbyProgressProvider(mediaServerB, apiB, tokenStore, sessionStore, StdoutLogger())

        providerA.reportProgress(progress(positionMs = 90_000))
        providerB.reportProgress(progress(positionMs = 30_000))

        assertTrue(apiA.calls.isNotEmpty())
        assertTrue(apiB.calls.isNotEmpty())
        assertTrue("server A 请求只带 tok-A", apiA.calls.all { it.token == "tok-A" && it.userId == "user-A" })
        assertTrue("server B 请求只带 tok-B", apiB.calls.all { it.token == "tok-B" && it.userId == "user-B" })
        assertTrue(apiA.calls.none { it.token == "tok-B" })
        assertTrue(apiB.calls.none { it.token == "tok-A" })
    }
}
