package com.mediahub.provider.emby.progress

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerType
import com.mediahub.player.engine.ProgressSyncCoordinator
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.session.EmbySession
import com.mediahub.provider.emby.session.EmbySessionStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SLOW-FINAL 回归（Phase 1H）：播放退出的 final 上报在慢网络下的确定性生产链回归。
 *
 * 真实生产退出链（ADR-023/039）：PlayerViewModel.stopAndFlush() →
 * engine.stop() → coordinator.stop() → **coordinator.flushFinal(finalProgress)**
 * （localSave 先落库，远端走 withTimeoutOrNull(2000) 预算）→ engine.release()。
 * 本套件组合**真实** ProgressSyncCoordinator + **真实** EmbyProgressProvider +
 * 真实 EmbyApiClient/ApiClient + MockWebServer（Dispatcher + CompletableDeferred 屏障，
 * 不用 sleep 伪造慢网络；屏障保证响应在断言点之前不可能到达）。
 *
 * 退出预算语义（ADR-017）：REMOTE_FLUSH_TIMEOUT_MS = 2000——"短超时保证退出不被
 * 慢网络阻塞"。本回归用真实阻塞在途请求验证该承诺：
 * - final POST 被服务端屏障挡住 > 预算 → flushFinal 必须在预算内返回（本地已落库、
 *   恰一次远端尝试、无异常冒出、在途 Call 被终止）；
 * - 屏障释放后的迟到响应 → 不得伪报成功（无二次请求/无二次本地写）、不得崩溃；
 * - final 2xx（预算内）→ 远端收到 Stopped、响应释放；
 * - final 5xx / 网络错误 → 恰一次尝试（POST 不参与 RetryOnce）、不重试风暴、
 *   会话状态一致。
 *
 * 无 sleep：所有等待都锚定确定性信号（屏障、OkHttp EventListener 终止事件、
 * MockWebServer dispatch 信号）；计时断言只界定退出预算本身（≥/＜ 2000ms）。
 */
class SlowFinalExitRegressionTest {

    // ---- 合成服务：MockWebServer + 屏障 Dispatcher ----

    /** Stopped 路径的行为（Playing/Progress 恒为快速 204）。 */
    private sealed interface StopSpec {
        /** 立即 204 + Connection: Close。 */
        object Fast : StopSpec

        /** 响应 204 + Connection: Close，但先等 [gate]（确定性慢网络屏障）。 */
        class Barrier(val gate: CompletableDeferred<Unit>) : StopSpec

        /** 立即 500。 */
        object ServerError : StopSpec

        /** 请求读入后掐断连接（确定性网络错误）。 */
        object Disconnect : StopSpec
    }

    private class SlowFinalDispatcher : Dispatcher() {
        @Volatile var stopSpec: StopSpec = StopSpec.Fast

        /** 服务端已收到的请求路径（到达序）。 */
        val received = CopyOnWriteArrayList<String>()

        /** 各路径最近一次请求体（每请求只读一次）。 */
        val bodies = ConcurrentHashMap<String, String>()

        /** Stopped 请求已到达服务端（屏障置位前），确定性锚点。每用例重置。 */
        @Volatile var finalArrived = CompletableDeferred<Unit>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            received += path
            return when {
                path.endsWith("/Sessions/Playing/Stopped") -> {
                    bodies[path] = request.body.readUtf8()
                    finalArrived.complete(Unit)
                    respondStopped()
                }
                else -> {
                    bodies[path] = request.body.readUtf8()
                    ok()
                }
            }
        }

        private fun respondStopped(): MockResponse = when (val spec = stopSpec) {
            is StopSpec.Barrier -> {
                // 阻塞 MockWebServer 工作线程直到屏障释放（确定性"慢服务端"）。
                kotlinx.coroutines.runBlocking { spec.gate.await() }
                ok()
            }
            StopSpec.ServerError -> MockResponse().setResponseCode(500).setHeader("Connection", "Close")
            StopSpec.Disconnect -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            StopSpec.Fast -> ok()
        }

        private fun ok(): MockResponse =
            MockResponse().setResponseCode(204).setHeader("Connection", "Close")
    }

    // ---- OkHttp Call 生命周期观测（确定性"响应释放/Call 终止"证据） ----

    private class CallTracker : EventListener() {
        val ended = AtomicInteger(0)
        val failed = AtomicInteger(0)

        /** 任一 Call 首次到达终态（成功结束或失败）的信号。 */
        var terminal = CompletableDeferred<Unit>()

        fun reset() {
            ended.set(0)
            failed.set(0)
            terminal = CompletableDeferred()
        }

        override fun callEnd(call: Call) {
            ended.incrementAndGet()
            terminal.complete(Unit)
        }

        override fun callFailed(call: Call, ioe: IOException) {
            failed.incrementAndGet()
            terminal.complete(Unit)
        }
    }

    // ---- fakes / helpers（镜像 EmbyProgressProviderTest 既有约定） ----

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

    private lateinit var server: MockWebServer
    private lateinit var dispatcher: SlowFinalDispatcher
    private lateinit var tracker: CallTracker
    private lateinit var tokenStore: TokenStore
    private lateinit var sessionStore: EmbySessionStore

    @Before
    fun setUp() {
        dispatcher = SlowFinalDispatcher()
        tracker = CallTracker()
        server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        tokenStore = TokenStore(FakeSecretStorage())
        sessionStore = EmbySessionStore(FakeSessionStorage())
    }

    /** 与 EmbyProgressProviderTest.seedSession 同款：会话/token 真值落 store。 */
    private suspend fun seedSession() {
        tokenStore.saveTokens("srv-1", StoredToken(accessToken = "tok-1"))
        sessionStore.save(EmbySession("srv-1", "remote-1", "user-1", "Alice"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val mediaServer = MediaServer(
        id = "srv-1", name = "Emby", type = ServerType.EMBY,
        baseUrl = "http://localhost", createdAtEpochMs = 0,
    )

    private fun progress(positionMs: Long, itemId: String = "m1", sessionId: String? = "psid-1") =
        PlaybackProgress(
            serverId = "srv-1", itemId = itemId, positionMs = positionMs, durationMs = 600_000,
            isPaused = false, updatedAtEpochMs = 0, sessionId = sessionId,
            mode = PlaybackMode.DIRECT_STREAM,
        )

    /** 真实生产客户端（超时/RetryOnceInterceptor 与线上一致）+ Call 生命周期观测。 */
    private fun productionClient(): OkHttpClient =
        HttpClientFactory(StdoutLogger()).apiClient()
            .newBuilder()
            .eventListenerFactory(EventListener.Factory { tracker })
            .build()

    /** 真实 EmbyProgressProvider（无任何测试 seam 替身）。 */
    private fun realProvider(client: OkHttpClient): EmbyProgressProvider {
        val api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(client, logger = StdoutLogger()),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(
                ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
            ),
            logger = StdoutLogger(),
        )
        return EmbyProgressProvider(mediaServer, api, tokenStore, sessionStore, StdoutLogger())
    }

    /**
     * 真实生产退出链（镜像 PlayerViewModel.stopAndFlush 的 coordinator 侧：
     * localSave 落库 + remoteFinalReport=Provider.reportFinalProgress）。
     * warmup 与 final 必须共用同一 Provider 实例（生产中 = 同一 ProviderHandle）。
     */
    private fun exitChain(
        client: OkHttpClient,
        localSink: CopyOnWriteArrayList<PlaybackProgress>,
    ): Pair<EmbyProgressProvider, ProgressSyncCoordinator> {
        val provider = realProvider(client)
        val coordinator = ProgressSyncCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            localSave = { localSink.add(it) },
            remoteReport = { provider.reportProgress(it) },
            remoteFinalReport = { provider.reportFinalProgress(it) },
        )
        return provider to coordinator
    }

    private fun paths(): List<String> = dispatcher.received.toList()

    private val playingPath = "/emby/Sessions/Playing"
    private val progressPath = "/emby/Sessions/Playing/Progress"
    private val stoppedPath = "/emby/Sessions/Playing/Stopped"

    // ==================================================================
    // 场景 1：final POST 被服务端屏障挡住超过退出预算
    // ==================================================================

    @Test
    fun `slow final beyond exit budget - local persisted, exit gated at budget, call terminated`() {
        val final = progress(300_000)
        val local = CopyOnWriteArrayList<PlaybackProgress>()
        val gate = CompletableDeferred<Unit>()
        dispatcher.stopSpec = StopSpec.Barrier(gate)
        val client = productionClient()
        val (provider, coordinator) = exitChain(client, local)

        runBlocking {
            seedSession()
            // 前导周期上报（Playing + Progress 快速 204），会话 active → final 恰一个 Stopped
            provider.reportProgress(progress(90_000))
            dispatcher.finalArrived = CompletableDeferred() // 重置锚点到本次 final

            // 镜像 stopAndFlush：stop() → flushFinal(explicit final)
            coordinator.stop()
            val start = System.nanoTime()
            val exitJob = launch { coordinator.flushFinal(final) }
            try {
                // 确定性锚点：final 请求已在服务端、且被屏障挡住（响应不可能已到达）
                dispatcher.finalArrived.await()
                assertEquals("屏障释放前不允许其他请求", listOf(playingPath, progressPath, stoppedPath), paths())
                assertTrue(
                    "本地进度必须在远端 final 完成之前已落库（ADR-023）",
                    local.map { it.positionMs } == listOf(300_000L),
                )
                val warmEnded = tracker.ended.get()
                val warmFailed = tracker.failed.get()

                // 预算语义：flushFinal 必须在 2000ms 退出预算内返回
                val joinedInBudget = runCatching {
                    withTimeout(BUDGET_MS + JOIN_SLACK_MS) { exitJob.join() }
                }.isSuccess
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                assertTrue(
                    "SLOW-FINAL 缺陷：flushFinal 在 ${elapsedMs}ms 后仍未返回" +
                        "（退出预算 ${BUDGET_MS}ms 被阻塞 execute 击穿）",
                    joinedInBudget,
                )
                assertTrue(
                    "预算必须真的起作用（实测 ${elapsedMs}ms < ${BUDGET_MS - 200}ms，疑未阻塞）",
                    elapsedMs >= BUDGET_MS - 200,
                )
                assertTrue("flushFinal 必须正常返回（不得向退出路径抛异常）", exitJob.isCompleted)

                // 恰一次远端尝试：POST 不重试
                assertEquals("final 恰一次 Stopped 请求", listOf(playingPath, progressPath, stoppedPath), paths())

                // 取消必须真正终止在途 Call（不再泄漏到 readTimeout）
                withTimeout(JOIN_SLACK_MS * 2) { tracker.terminal.await() }
                assertTrue(
                    "超时后取消必须终止在途 Call（callFailed 未触发：Call 泄漏）",
                    tracker.failed.get() > warmFailed,
                )
                assertEquals("在途 Call 取消后不得伪完成", warmEnded, tracker.ended.get())
            } finally {
                // 释放屏障 + 排空残余在途协程，不把阻塞留给下一条测试
                gate.complete(Unit)
                exitJob.cancel()
                withTimeoutOrNull(5_000) { runCatching { exitJob.join() } }
            }
        }
    }

    // ==================================================================
    // 场景 2：final 在预算内完成（正常 2xx）
    // ==================================================================

    @Test
    fun `final within budget - remote receives stopped and response released`() {
        val final = progress(300_000)
        val local = CopyOnWriteArrayList<PlaybackProgress>()
        dispatcher.stopSpec = StopSpec.Fast
        val client = productionClient()
        val (provider, coordinator) = exitChain(client, local)

        runBlocking {
            seedSession()
            provider.reportProgress(progress(90_000))
            tracker.reset()
            coordinator.stop()

            val start = System.nanoTime()
            coordinator.flushFinal(final)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertTrue(
                "预算内 final 必须快速完成（实测 ${elapsedMs}ms ≥ ${BUDGET_MS}ms）",
                elapsedMs < BUDGET_MS,
            )
            assertEquals(listOf(playingPath, progressPath, stoppedPath), paths())
            val stoppedBody = dispatcher.bodies[stoppedPath].orEmpty()
            assertTrue(stoppedBody.contains("\"ItemId\":\"m1\""))
            assertTrue("final 必须带 PlaybackInfo 真值 PlaySessionId", stoppedBody.contains("\"PlaySessionId\":\"psid-1\""))
            assertTrue("final 位置必须以 PositionTicks 上报（300s → 3e9）", stoppedBody.contains("\"PositionTicks\":3000000000"))
            assertEquals("本地恰好落库一次（仅 flushFinal，无重复写）", listOf(300_000L), local.map { it.positionMs })
            // 响应释放证据：final Call 正常终态（callEnd），无失败
            withTimeout(5_000) { tracker.terminal.await() }
            assertEquals("无网络层失败", 0, tracker.failed.get())
            assertEquals("final 响应已消费并释放", 1, tracker.ended.get())
        }
    }

    // ==================================================================
    // 场景 3：final 失败（5xx / 网络错误）——不重试风暴、不冒充成功、状态一致
    // ==================================================================

    @Test
    fun `final 5xx - single attempt, no retry storm, session state consistent`() {
        val final = progress(300_000)
        val local = CopyOnWriteArrayList<PlaybackProgress>()
        dispatcher.stopSpec = StopSpec.ServerError
        val client = productionClient()
        val (provider, coordinator) = exitChain(client, local)

        runBlocking {
            seedSession()
            provider.reportProgress(progress(90_000))
            coordinator.stop()

            val start = System.nanoTime()
            coordinator.flushFinal(final)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertTrue("失败 final 不得阻塞退出（实测 ${elapsedMs}ms）", elapsedMs < BUDGET_MS)
            assertEquals("恰一次 Stopped 尝试（POST 不参与 RetryOnce）", listOf(playingPath, progressPath, stoppedPath), paths())
            assertEquals("错误不得吞掉本地落库", listOf(300_000L), local.map { it.positionMs })

            // 会话状态一致：final（即使 Stopped 失败）后同条目再上报 → 重新开 Playing
            dispatcher.stopSpec = StopSpec.Fast
            provider.reportProgress(progress(60_000))
            assertEquals(
                "final 失败后必须重开会话（不得残留旧会话裸 Progress）",
                listOf(playingPath, progressPath, stoppedPath, playingPath, progressPath),
                paths(),
            )
        }
    }

    @Test
    fun `final network failure - single attempt, no retry storm, local state consistent`() {
        val final = progress(300_000)
        val local = CopyOnWriteArrayList<PlaybackProgress>()
        dispatcher.stopSpec = StopSpec.Disconnect
        val client = productionClient()
        val (provider, coordinator) = exitChain(client, local)

        runBlocking {
            seedSession()
            provider.reportProgress(progress(90_000))
            coordinator.stop()

            coordinator.flushFinal(final)

            assertEquals("恰一次 Stopped 尝试", listOf(playingPath, progressPath, stoppedPath), paths())
            assertEquals("网络错误不得吞掉本地落库", listOf(300_000L), local.map { it.positionMs })
            // 网络层以失败终态收口（不悬挂）
            withTimeout(5_000) { tracker.terminal.await() }
            assertEquals("在途 Call 必须以失败终态收口", 1, tracker.failed.get())
        }
    }

    // ==================================================================
    // 场景 4：退出后迟到响应——不得伪报成功、不得崩溃
    // ==================================================================

    @Test
    fun `late response after exit - no fake success and no crash`() {
        val final = progress(300_000)
        val local = CopyOnWriteArrayList<PlaybackProgress>()
        val gate = CompletableDeferred<Unit>()
        dispatcher.stopSpec = StopSpec.Barrier(gate)
        val client = productionClient()
        val (provider, coordinator) = exitChain(client, local)

        runBlocking {
            seedSession()
            provider.reportProgress(progress(90_000))
            dispatcher.finalArrived = CompletableDeferred()

            coordinator.stop()
            val exitJob = launch { coordinator.flushFinal(final) }
            try {
                dispatcher.finalArrived.await()

                // 退出先于响应完成（预算内返回；屏障仍挡住服务端）
                withTimeout(BUDGET_MS + JOIN_SLACK_MS) { exitJob.join() }
                assertTrue(exitJob.isCompleted)

                // 退出瞬间的世界状态
                val localAtExit = local.toList()
                val requestsAtExit = paths()
                val endedAtExit = tracker.ended.get()
                val failedAtExit = tracker.failed.get()

                // 迟到响应：屏障释放，服务端此时才回复 204
                gate.complete(Unit)
                withTimeoutOrNull(5_000) { tracker.terminal.await() }

                assertEquals("迟到响应不得触发二次本地写（伪报成功）", localAtExit, local.toList())
                assertEquals("迟到响应不得触发二次远端请求", requestsAtExit, paths())
                assertEquals(
                    "迟到的 204 不得把已取消的 Call 翻转为成功终态",
                    endedAtExit,
                    tracker.ended.get(),
                )
                assertEquals("取消失败计数不得因迟到响应增长", failedAtExit, tracker.failed.get())
            } finally {
                gate.complete(Unit)
                exitJob.cancel()
                withTimeoutOrNull(5_000) { runCatching { exitJob.join() } }
            }
        }
    }

    private companion object {
        const val BUDGET_MS = 2_000L

        /** join/等待的调度余量（不参与预算语义断言）。 */
        const val JOIN_SLACK_MS = 1_500L
    }
}
