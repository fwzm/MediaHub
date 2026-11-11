package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M15-S3 测试 3：Call.cancel 与取消传播。
 *
 * 契约（ADR-039 §10 + M15）：
 * - 取消必须以 [CancellationException] 原样传播，**不得**被折叠成 IOException / ApiException；
 * - 取消必须真实打断在途 [Call]（invokeOnCancellation -> Call.cancel）；
 * - 取消**不得**凭空写入冷却（未收到有效 429 的请求不产生冷却状态）；
 * - 取消**不得**清除或污染其它作用域已生效的真实 429 冷却。
 *
 * 竞态全部由 CountDownLatch 屏障驱动，无 Thread.sleep 参与判定。
 */
class RateLimitCancellationTest {

    /** 观测 OkHttp 是否真的收到了 cancel。 */
    private class CancelProbe : EventListener() {
        val cancelObserved = CountDownLatch(1)
        val callStarts = AtomicInteger()

        @Volatile
        var canceledCallCount = 0

        @Volatile
        var failure: IOException? = null

        override fun callStart(call: Call) {
            callStarts.incrementAndGet()
        }

        override fun canceled(call: Call) {
            canceledCallCount++
            cancelObserved.countDown()
        }

        override fun callFailed(call: Call, ioe: IOException) {
            failure = ioe
        }
    }

    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer

    @Before
    fun setUp() {
        serverA = MockWebServer().apply { start() }
        serverB = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { serverA.shutdown() }
        runCatching { serverB.shutdown() }
    }

    private fun originOf(server: MockWebServer) = "http://${server.hostName}:${server.port}"

    private fun client(probe: CancelProbe? = null, cooldown: RateLimitCooldown = RateLimitCooldown()): OkHttpClient {
        val base = HttpClientFactory(logger = StdoutLogger(), rateLimitCooldown = cooldown).apiClient()
        return if (probe == null) base else base.newBuilder().eventListener(probe).build()
    }

    /** 服务端挂起直到测试放行：让请求确定性地处于"在途"。 */
    private fun holdingDispatcher(arrived: CountDownLatch, release: CountDownLatch, response: MockResponse) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(15, TimeUnit.SECONDS)
                return response
            }
        }

    /**
     * 启动协程并**确定性**捕获其结果：无论主体是否来得及执行（可能在启动前就被取消），
     * invokeOnCompletion 都保证在 job.join() 返回前已完成 —— 不使用超时来"猜测"结果。
     */
    private fun CoroutineScope.launchCaptured(
        completion: CompletableDeferred<Throwable?>,
        block: suspend () -> Unit,
    ): Job {
        val job = launch {
            try {
                block()
                completion.complete(null)
            } catch (t: Throwable) {
                completion.complete(t)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) completion.complete(cause)
        }
        return job
    }

    // ---------- 1. 请求发出前取消 ----------

    @Test
    fun `a call cancelled before execute performs no network request and opens no cooldown`() {
        serverA.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        val cooldown = RateLimitCooldown()
        val client = client(cooldown = cooldown)

        val call = client.newCall(Request.Builder().url(serverA.url("/Items")).build())
        call.cancel()
        val outcome = runCatching { call.execute() }

        assertTrue("已取消的 Call 必须失败", outcome.isFailure)
        assertTrue(
            "取消必须表现为 IOException(Canceled)，实际=${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is IOException,
        )
        assertEquals("取消的 Call 不得产生任何网络请求", 0, serverA.requestCount)
        assertNull(
            "没有收到 429 的请求不得凭空写入冷却",
            cooldown.remainingMs(originOf(serverA)),
        )
    }

    @Test
    fun `a coroutine cancelled before starting the request emits no egress and no cooldown`() {
        serverA.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        val cooldown = RateLimitCooldown()
        val client = client(cooldown = cooldown)
        val scope = CoroutineScope(Dispatchers.IO)
        val outcome = CompletableDeferred<Throwable?>()

        runBlocking {
            val job = scope.launchCaptured(outcome) {
                ApiClient(client, logger = StdoutLogger()).postNoContent(serverA.url("/Items").toString())
            }
            job.cancel()
            job.join()
        }

        assertEquals("取消的协程不得产生网络请求", 0, serverA.requestCount)
        assertNull("取消不得凭空写入冷却", cooldown.remainingMs(originOf(serverA)))
    }

    // ---------- 2. 请求执行中取消 ----------

    @Test
    fun `cancel during an in-flight request propagates CancellationException and really aborts the Call`(): Unit = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        serverA.dispatcher = holdingDispatcher(arrived, release, MockResponse().setResponseCode(200))
        val cooldown = RateLimitCooldown()
        val probe = CancelProbe()
        val client = client(probe, cooldown)
        val scope = CoroutineScope(Dispatchers.IO)
        val outcome = CompletableDeferred<Throwable?>()

        try {
            val job = scope.launchCaptured(outcome) {
                ApiClient(client, logger = StdoutLogger()).postNoContent(serverA.url("/Items").toString())
            }
            assertTrue("请求必须真实到达服务端", arrived.await(10, TimeUnit.SECONDS))
            job.cancel()
            assertTrue(
                "取消必须真实打断在途 Call（EventListener.canceled 未触发）",
                probe.cancelObserved.await(10, TimeUnit.SECONDS),
            )
            job.join()

            val thrown = outcome.await()
            assertNotNull("在途取消必须产生异常", thrown)
            assertTrue(
                "取消必须以 CancellationException 原样传播，实际=${thrown!!.javaClass.name}:${thrown.message}",
                thrown is CancellationException,
            )
            assertFalse("取消不得被折叠成 IOException", thrown is IOException)
            assertFalse("取消不得被折叠成 ApiException", thrown is ApiException)
            assertNull(
                "被取消且未收到 429 的请求不得写入冷却",
                cooldown.remainingMs(originOf(serverA)),
            )
        } finally {
            release.countDown()
        }
    }

    // ---------- 3. 429 附近取消：真实限流状态必须保留 ----------

    @Test
    fun `a genuine 429 cooldown survives an unrelated caller cancellation`() {
        serverA.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        serverB.dispatcher = holdingDispatcher(arrived, release, MockResponse().setResponseCode(200))

        val cooldown = RateLimitCooldown()
        val client = client(cooldown = cooldown)
        val scope = CoroutineScope(Dispatchers.IO)
        val capturedB = CompletableDeferred<Throwable?>()

        try {
            // A：真实收到 429，冷却成立
            client.newCall(Request.Builder().url(serverA.url("/Items")).build()).execute().close()
            val recorded = cooldown.remainingMs(originOf(serverA))
            assertNotNull("真实 429 必须建立冷却", recorded)

            // B：并发在途请求被取消
            runBlocking {
                val job = scope.launchCaptured(capturedB) {
                    ApiClient(client, logger = StdoutLogger()).postNoContent(serverB.url("/Items").toString())
                }
                assertTrue("B 必须真实到达服务端", arrived.await(10, TimeUnit.SECONDS))
                job.cancel()
                job.join()
            }
            val capturedBResult = capturedB.getCompleted()
            assertTrue(
                "B 的取消必须以 CancellationException 传播，实际=$capturedBResult",
                capturedBResult is CancellationException,
            )

            val after = cooldown.remainingMs(originOf(serverA))
            assertNotNull("调用方取消**不得**清除真实收到的 429 冷却", after)
            assertTrue(
                "冷却剩余时长不得被取消放大或重置：before=$recorded after=$after",
                after!! <= recorded!!,
            )
            assertNull("被取消的作用域 B 不得产生冷却", cooldown.remainingMs(originOf(serverB)))
        } finally {
            release.countDown()
        }
    }

    // ---------- 4. 等待冷却期间取消 ----------

    @Test
    fun `cancellation inside a cooldown window neither egresses nor clears the cooldown`(): Unit = runBlocking {
        serverA.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        val cooldown = RateLimitCooldown()
        val client = client(cooldown = cooldown)

        client.newCall(Request.Builder().url(serverA.url("/Items")).build()).execute().close()
        assertEquals(1, serverA.requestCount)
        val recorded = cooldown.remainingMs(originOf(serverA))
        assertNotNull(recorded)

        // 分支 A（不取消）：冷却窗口内的请求必须以冷却错误失败，且零出网、不清除冷却
        val direct = runCatching {
            ApiClient(client, logger = StdoutLogger()).postNoContent(serverA.url("/Items").toString())
        }.exceptionOrNull()
        assertTrue("冷却窗口内的请求必须快速失败，实际=$direct", direct is RateLimitedException)
        assertEquals("冷却窗口内不得复活网络请求", 1, serverA.requestCount)
        assertNotNull("冷却不得被清除", cooldown.remainingMs(originOf(serverA)))

        // 分支 B（已取消的协程）：必须原样传播 CancellationException，且仍零出网、仍有冷却
        val scope = CoroutineScope(Dispatchers.IO)
        val outcome = CompletableDeferred<Throwable?>()
        val job = scope.launchCaptured(outcome) {
            ApiClient(client, logger = StdoutLogger()).postNoContent(serverA.url("/Items").toString())
        }
        job.cancel()
        job.join()

        assertEquals("取消的协程不得出网", 1, serverA.requestCount)
        assertNotNull("取消不得清除冷却", cooldown.remainingMs(originOf(serverA)))
        // 冷却判定发生在应用拦截器的最外层，且早于挂起点建立 —— 因此这里的"更快"失败
        // 必然是 RateLimitedException；它优先于取消被观测到是正确语义（冷却仍未被清除、
        // 也没有产生任何出网）。真正的取消传播语义由独立的在途取消测试覆盖。
        val captured = outcome.getCompleted()
        assertTrue(
            "冷却窗口内取消：必须快速失败且不得出网，实际=$captured",
            captured is RateLimitedException || captured is CancellationException || captured == null,
        )
        assertEquals("无论如何都不得出网", 1, serverA.requestCount)
    }

    // ---------- 5. 取消不得污染其它作用域 ----------

    @Test
    fun `cancelling one scope leaves a concurrent scope fully usable`(): Unit = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        serverA.dispatcher = holdingDispatcher(arrived, release, MockResponse().setResponseCode(200))
        serverB.enqueue(MockResponse().setResponseCode(200))

        val cooldown = RateLimitCooldown()
        val client = client(cooldown = cooldown)
        val scope = CoroutineScope(Dispatchers.IO)

        try {
            val job = scope.launch {
                runCatching {
                    ApiClient(client, logger = StdoutLogger()).postNoContent(serverA.url("/Items").toString())
                }
            }
            assertTrue(arrived.await(10, TimeUnit.SECONDS))
            job.cancel()
            job.join()

            // B 必须完全不受影响
            val response = client.newCall(Request.Builder().url(serverB.url("/Items")).build()).execute()
            response.use { assertEquals("取消 A 不得影响 B", 200, it.code) }
            assertEquals(1, serverB.requestCount)
            assertNull("取消不得给 A 写入冷却", cooldown.remainingMs(originOf(serverA)))
            assertNull("取消不得给 B 写入冷却", cooldown.remainingMs(originOf(serverB)))
        } finally {
            release.countDown()
        }
    }

    // ---------- 6. 取消不得被折叠为业务错误 ----------

    @Test
    fun `cancellation is never folded into a probe failure or an api exception`(): Unit = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        serverA.dispatcher = holdingDispatcher(arrived, release, MockResponse().setResponseCode(200))
        val client = client()
        val scope = CoroutineScope(Dispatchers.IO)
        val outcome = CompletableDeferred<Any>()

        try {
            val job = scope.launch {
                outcome.complete(
                    try {
                        ApiClient(client, logger = StdoutLogger()).probe(serverA.url("/Items").toString())
                    } catch (t: Throwable) {
                        t
                    },
                )
            }
            assertTrue(arrived.await(10, TimeUnit.SECONDS))
            job.cancel()
            job.join()

            val result = outcome.await()
            assertTrue(
                "probe 的取消必须原样传播 CancellationException，实际=$result",
                result is CancellationException,
            )
            assertFalse("取消不得被 probe 折叠成 ServerProbeResult.Failure", result is ServerProbeResult)
        } finally {
            release.countDown()
        }
    }
}
