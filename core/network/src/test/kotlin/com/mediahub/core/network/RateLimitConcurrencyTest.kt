package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M15-S3 测试 2：并发与 barrier（确定性竞态）。
 *
 * 要点：
 * - 竞态成立**只**依赖 CountDownLatch 屏障，绝不使用 Thread.sleep；
 * - 断言"两个冷请求必须同时到达服务端" —— 这会**证伪**任何"用全局锁/串行化包住
 *   chain.proceed 来掩盖并发问题"的实现（那种实现下屏障永远填不满 → 超时）；
 * - 断言作用域隔离在并发下依然成立，且冷却表不是"单个共享槽位"（后者会让
 *   两个作用域读到同一份截止时间）。
 */
class RateLimitConcurrencyTest {

    /** 受控屏障：arriveAndAwait() 阻塞到全部参与方到齐后再统一放行。 */
    private class Barrier(parties: Int) {
        private val ready = CountDownLatch(parties)
        private val go = CountDownLatch(1)

        fun arriveAndAwait(timeoutSec: Long = 10) {
            ready.countDown()
            if (!go.await(timeoutSec, TimeUnit.SECONDS)) {
                throw AssertionError("屏障未在 ${timeoutSec}s 内集齐 —— 并发请求被串行化了")
            }
        }

        /** 等待到齐（用于"必须确实有两个并发请求"的断言）。 */
        fun awaitArrivals(timeoutSec: Long = 10): Boolean = ready.await(timeoutSec, TimeUnit.SECONDS)

        fun release() = go.countDown()
    }

    /** 非阻塞发起 + 手动等待结果，便于在断言点之间保持多个请求同时"在途"。 */
    private class PendingCall(call: Call) : Callback {
        private val done = CountDownLatch(1)

        @Volatile
        var code: Int? = null

        @Volatile
        var failure: Throwable? = null

        init {
            call.enqueue(this)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use { code = it.code }
            done.countDown()
        }

        override fun onFailure(call: Call, e: IOException) {
            failure = e
            done.countDown()
        }

        fun await(timeoutSec: Long = 20): Boolean = done.await(timeoutSec, TimeUnit.SECONDS)

        val describe: String
            get() = failure?.let { "failure:${it.javaClass.simpleName}(${it.message})" } ?: "code=$code"
    }

    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer
    private lateinit var serverC: MockWebServer

    @Before
    fun setUp() {
        serverA = MockWebServer().apply { start() }
        serverB = MockWebServer().apply { start() }
        serverC = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        for (s in listOf(serverA, serverB, serverC)) runCatching { s.shutdown() }
    }

    private fun originOf(server: MockWebServer) = "http://${server.hostName}:${server.port}"

    private fun factory(cooldown: RateLimitCooldown = RateLimitCooldown()) =
        HttpClientFactory(logger = StdoutLogger(), rateLimitCooldown = cooldown)

    private fun call(client: OkHttpClient, server: MockWebServer, path: String = "/Items") =
        client.newCall(Request.Builder().url(server.url(path)).build())

    /**
     * 同一作用域内两个请求**同时**在途（服务端直到两个都到达才返回）。
     * 这是"429 到达 vs 放行"竞态的基础；同时证伪"全局锁串行化"的伪修复。
     */
    @Test
    fun `two cold requests on the same scope reach the server concurrently`() {
        val arrivals = AtomicInteger()
        val barrier = Barrier(2)
        serverA.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrivals.incrementAndGet()
                barrier.arriveAndAwait()
                return MockResponse().setResponseCode(200)
            }
        }
        val client = factory().apiClient()

        val first = PendingCall(call(client, serverA, "/Items?n=1"))
        val second = PendingCall(call(client, serverA, "/Items?n=2"))

        // 两个请求都必须在对方返回前抵达服务端 —— 否则说明有一个被全局串行化挡住了
        assertTrue(
            "两个同源冷请求必须能同时在途（被串行化则屏障填不满）",
            barrier.awaitArrivals(),
        )
        barrier.release()

        assertTrue("请求1 未完成: ${first.describe}", first.await())
        assertTrue("请求2 未完成: ${second.describe}", second.await())
        assertEquals("请求1 应放行", 200, first.code)
        assertEquals("请求2 应放行", 200, second.code)
        assertEquals("两个请求都必须真实出网", 2, arrivals.get())
    }

    /**
     * 同一作用域内两个请求**同时**收到 429：两个响应都必须原样返回给调用方，
     * 冷却只记录一次且窗口内不得再出网（429 不得被放大）。
     */
    @Test
    fun `two simultaneous 429 responses in one scope are both delivered and recorded once`() {
        val arrivals = AtomicInteger()
        val barrier = Barrier(2)
        serverA.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrivals.incrementAndGet()
                barrier.arriveAndAwait()
                return MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
            }
        }
        val cooldown = RateLimitCooldown()
        val client = factory(cooldown).apiClient()

        val first = PendingCall(call(client, serverA, "/Items?n=1"))
        val second = PendingCall(call(client, serverA, "/Items?n=2"))
        assertTrue("两个 429 请求必须同时在途", barrier.awaitArrivals())
        barrier.release()

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals("第一个 429 必须原样送达", 429, first.code)
        assertEquals("第二个 429 必须原样送达", 429, second.code)
        assertEquals("并发 429 不得被放大成更多出网", 2, arrivals.get())

        // 屏障后作用域已进入冷却：后续请求必须以可识别错误快速失败且零出网
        val blocked = runCatching { call(client, serverA, "/Items?n=3").execute() }
        assertTrue("冷却窗口内必须以可识别错误快速失败: $blocked", blocked.isFailure)
        assertTrue(
            "冷却失败必须是 RateLimitedException，实际=${blocked.exceptionOrNull()}",
            blocked.exceptionOrNull() is RateLimitedException,
        )
        assertEquals("冷却窗口内不得再出网", 2, arrivals.get())
        assertNotNull("冷却必须被记录", cooldown.remainingMs(originOf(serverA)))
    }

    /**
     * 不同作用域**同时**收到 429：各自独立记录，冷却表不得是"单个共享槽位"。
     * 单槽实现会让两个 origin 读到同一份截止时间 → 断言失败。
     */
    @Test
    fun `simultaneous 429s on two scopes keep independent cooldowns`() {
        val barrier = Barrier(2)
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                barrier.arriveAndAwait()
                return MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
            }
        }
        serverA.dispatcher = dispatcher
        serverB.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                barrier.arriveAndAwait()
                return MockResponse().setResponseCode(429).setHeader("Retry-After", "120")
            }
        }
        val cooldown = RateLimitCooldown()
        val client = factory(cooldown).apiClient()

        val a = PendingCall(call(client, serverA))
        val b = PendingCall(call(client, serverB))
        assertTrue("两个不同作用域的请求必须同时在途", barrier.awaitArrivals())
        barrier.release()
        assertTrue(a.await())
        assertTrue(b.await())
        assertEquals(429, a.code)
        assertEquals(429, b.code)

        val remainingA = cooldown.remainingMs(originOf(serverA))
        val remainingB = cooldown.remainingMs(originOf(serverB))
        assertNotNull("A 作用域必须有自己的冷却", remainingA)
        assertNotNull("B 作用域必须有自己的冷却", remainingB)
        assertTrue("A 冷却应约为 60s，实际=$remainingA", remainingA!! in 50_000L..60_000L)
        assertTrue("B 冷却应约为 120s，实际=$remainingB", remainingB!! in 110_000L..120_000L)
    }

    /**
     * A 作用域进入冷却后，B 作用域的**并发**请求必须全部正常放行。
     * 覆盖"同 host 不同端口隔离"与"A 冷却不阻断 B"的并发形态。
     */
    @Test
    fun `a cooling scope does not block a concurrent burst to another port on the same host`() {
        serverA.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        repeat(BURST) { serverB.enqueue(MockResponse().setResponseCode(200)) }
        serverC.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))

        val cooldown = RateLimitCooldown()
        val client = factory(cooldown).apiClient()

        // A 与 C 进入冷却（同 host、不同端口，互不影响）
        call(client, serverA).execute().close()
        call(client, serverC).execute().close()

        // A 已冷却：并发冲击 A 必须全部失败且零出网
        val blockedBurst = (1..BURST).map { PendingCall(call(client, serverA, "/Items?a=$it")) }
        blockedBurst.forEach { assertTrue(it.await()) }
        assertTrue(
            "A 冷却期间不得有任何请求出网",
            blockedBurst.all { it.failure is RateLimitedException },
        )
        assertEquals("A 冷却期间不得再出网", 1, serverA.requestCount)

        // B 未冷却：并发冲击 B 必须全部成功并真实出网
        val allowedBurst = (1..BURST).map { PendingCall(call(client, serverB, "/Items?b=$it")) }
        allowedBurst.forEach { assertTrue(it.await()) }
        assertEquals(
            "B 必须全部放行: ${allowedBurst.map { it.describe }}",
            BURST,
            allowedBurst.count { it.code == 200 },
        )
        assertEquals("B 必须真实出网 $BURST 次", BURST, serverB.requestCount)
    }

    /**
     * 并发写入 + 读取冷却状态：多作用域并发 429 之后，每个作用域的截止时间
     * 都必须各自正确（不得互相覆盖），且读取不抛异常、不无限增长。
     */
    @Test
    fun `concurrent writes and reads across many scopes stay consistent`() {
        val servers = (1..CONCURRENCY).map { MockWebServer().apply { start() } }
        try {
            val seconds = servers.indices.associateWith { 5 + it * 5 }
            servers.forEachIndexed { i, s ->
                s.enqueue(
                    MockResponse().setResponseCode(429)
                        .setHeader("Retry-After", seconds.getValue(i).toString()),
                )
            }
            val cooldown = RateLimitCooldown()
            val client = factory(cooldown).apiClient()

            // 并发写：每个 origin 各自 429（各自冷却时长不同）
            val writers = servers.mapIndexed { i, s ->
                PendingCall(call(client, s, "/Items?i=$i"))
            }
            writers.forEach { assertTrue(it.await()) }
            assertEquals("全部 429 必须送达", CONCURRENCY, writers.count { it.code == 429 })

            // 并发读：每个 origin 读到的必须是自己的冷却
            servers.forEachIndexed { i, s ->
                val remaining = cooldown.remainingMs(originOf(s))
                assertNotNull("origin $i 必须有冷却", remaining)
                val expected = seconds.getValue(i) * 1000L
                assertTrue(
                    "origin $i 冷却应约为 ${expected}ms，实际=$remaining（并发写入互相覆盖？）",
                    remaining!! in (expected - 5_000L)..expected,
                )
            }

            // 冷却中的并发读/写不得让表无限增长，也不得互相污染
            val stress = (1..BURST * 4).map { n ->
                Thread {
                    val origin = originOf(servers[n % CONCURRENCY])
                    cooldown.remainingMs(origin)
                    cooldown.apply(origin, 1_000L)
                }.also { it.start() }
            }
            stress.forEach { it.join(10_000) }
            servers.forEachIndexed { i, s ->
                assertNotNull("并发读写后 origin $i 仍须有冷却", cooldown.remainingMs(originOf(s)))
            }
        } finally {
            servers.forEach { runCatching { it.shutdown() } }
        }
    }

    private companion object {
        const val BURST = 8
        const val CONCURRENCY = 4
    }
}
