package com.mediahub.provider.webdav

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WebDavCallBridge 总并发硬上限回归（A4，B 审查 REPRODUCED：池满后第 17 个请求
 * CallerRuns 在提交线程执行，总在途 = 池 16 + 并发调用者数，复现 21 > 16）。
 *
 * 契约（修复后语义）：
 * - 总在途（已进入网络层的请求）**硬上界 = [MAX_BRIDGE_THREADS] = 16**，
 *   与并发调用者数量无关；超出者在信号量上挂起排队。
 * - 排队中被取消：permit 不占用、任务不执行、零额外网络请求；permit 正确
 *   归还（后续新请求可立即执行，无泄漏饥饿）。
 * - 逐步放行屏障后，全部请求完成（无丢失）。
 *
 * 阻塞中（已进入网络）取消 → 真实 Call.cancel 的契约由既有
 * [WebDavApiCancellationTest]（EventListener.canceled / responseFailed 锚定）覆盖，
 * 此处不重复。
 *
 * 可观察事实：拦截器计数（进入网络层 = 进入拦截器）+ MockWebServer 请求数 +
 * OkHttp EventListener.canceled；等待全部为有界条件等待。
 */
class A4BridgeTotalConcurrencyTest {

    private companion object {
        /** 必须与 WebDavCallBridge.MAX_BRIDGE_THREADS 一致（硬上限断言的锚点）。 */
        const val MAX_BRIDGE_THREADS = 16
        const val TOTAL_REQUESTS = 21
        const val QUEUED = TOTAL_REQUESTS - MAX_BRIDGE_THREADS // 5
    }

    private lateinit var webServer: MockWebServer
    private var scope: CoroutineScope? = null

    /** 已进入拦截器（= 进入网络层）的请求序号集合。 */
    private val enteredIndexes: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** 已完整穿过拦截器（chain.proceed 返回）的请求序号集合。 */
    private val passedIndexes: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** 通行闸门（permits=0 起步）：每放行一个 permit 恰好放行一个请求。 */
    private val gate = Semaphore(0)

    /** OkHttp 真实 Call.cancel 触发计数（EventListener.canceled）。 */
    private val canceledCalls = AtomicInteger(0)

    @Before
    fun setUp() {
        webServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(200).setBody("{}")
            }
            start()
        }
    }

    @After
    fun tearDown() {
        // 放行一切可能仍阻塞在闸门的拦截器线程（已取消的 execute 会以 IOException 结束）
        gate.release(TOTAL_REQUESTS + 16)
        scope?.cancel()
        try {
            webServer.shutdown()
        } catch (ignored: Exception) {
        }
    }

    /** 屏障客户端：拦截器内先登记序号，再在闸门上等放行；canceled 计数锚定真实取消。 */
    private fun barrierClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .addInterceptor { chain: Interceptor.Chain ->
            val index = chain.request().header("X-Test-Index")!!.toInt()
            enteredIndexes.add(index)
            check(gate.tryAcquire(15, TimeUnit.SECONDS)) { "闸门 15s 未放行 index=$index" }
            // 不归还 permit：闸门通行权由测试侧精确控制，保证阶段推进的确定性
            chain.proceed(chain.request()).also { passedIndexes.add(index) }
        }
        .eventListener(object : EventListener() {
            override fun canceled(call: Call) {
                canceledCalls.incrementAndGet()
            }
        })
        .build()

    /** 以调用方协程发起一次桥接调用（与生产 awaitCancellable 相同入口）。 */
    private fun CoroutineScope.launchBridged(client: OkHttpClient, index: Int): Deferred<Int> =
        async {
            client.newCall(request(index)).awaitCancellable { response ->
                response.use { it.code }
            }
        }

    private fun request(index: Int): Request = Request.Builder()
        .url(webServer.url("/dav/item/$index"))
        .header("X-Test-Index", index.toString())
        .build()

    @Test
    fun `21 concurrent calls keep exactly 16 in flight and 5 queued`() = runBlocking {
        val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = s
        val client = barrierClient()
        val jobs = (0 until TOTAL_REQUESTS).map { s.launchBridged(client, it) }

        assertTrue(
            "必须有 ${MAX_BRIDGE_THREADS} 个请求进入网络层（实际 ${enteredIndexes.size}）",
            awaitCondition(5_000) { enteredIndexes.size >= MAX_BRIDGE_THREADS },
        )
        // 硬上限：总在途恰好 16 —— 旧实现（CallerRuns 在提交线程就地执行）此处为 21
        assertEquals(
            "总在途必须恰好 = $MAX_BRIDGE_THREADS（B 复现：池满后 CallerRuns 使总在途=16+调用者数）",
            MAX_BRIDGE_THREADS,
            enteredIndexes.size,
        )
        // 5 个排队者停留在挂起状态：稳定窗口内不进入网络层，服务端零请求
        assertTrue(
            "排队中的 $QUEUED 个请求不得进入网络层（稳定窗口后 entered=${enteredIndexes.size}）",
            awaitStable(400) { enteredIndexes.size == MAX_BRIDGE_THREADS },
        )
        assertEquals("屏障未放行前服务端必须零请求", 0, webServer.requestCount)
        assertEquals("尚无任何调用完成", 0, passedIndexes.size)
        // 清理：取消全部（在途 16 + 排队 5），闸门在 tearDown 放行
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `cancelling queued waiters sends no extra request and releases permits`() = runBlocking {
        val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = s
        val client = barrierClient()
        val jobs = (0 until TOTAL_REQUESTS).map { s.launchBridged(client, it) }

        assertTrue(awaitCondition(5_000) { enteredIndexes.size >= MAX_BRIDGE_THREADS })
        assertEquals(MAX_BRIDGE_THREADS, enteredIndexes.size)

        // 排队者 = 未进入网络层的序号；逐个取消（排队中被取消 → 不执行、不占 permit）
        val queuedIndexes = (0 until TOTAL_REQUESTS).filter { it !in enteredIndexes }
        assertEquals("排队者必须恰好 $QUEUED 个", QUEUED, queuedIndexes.size)
        queuedIndexes.forEach { jobs[it].cancel() }
        queuedIndexes.forEach { index ->
            val outcome = runCatching { jobs[index].await() }.exceptionOrNull()
            assertTrue("排队者取消必须以 CancellationException 收尾，实际 $outcome", outcome is CancellationException)
        }

        // 零额外请求：取消后稳定窗口内 entered 不增长、服务端仍零请求；
        // 排队者从未创建真实 Call，因此也不产生任何 Call.cancel 事件
        assertTrue(
            "取消排队者不得引发任何额外网络请求（entered=${enteredIndexes.size}）",
            awaitStable(400) { enteredIndexes.size == MAX_BRIDGE_THREADS },
        )
        assertEquals(0, webServer.requestCount)
        assertEquals("排队者取消不得触发真实 Call.cancel（其任务未执行）", 0, canceledCalls.get())

        // permit 已正确归还：放行 16 个在途 → 完成后，新请求必须立即取得 permit 执行
        gate.release(MAX_BRIDGE_THREADS)
        assertTrue(
            "16 个在途必须全部完成",
            awaitCondition(5_000) { jobs.withIndex().all { it.value.isCompleted || it.value.isCancelled } },
        )
        val followUp = s.launchBridged(client, TOTAL_REQUESTS) // 第 22 个
        gate.release(1)
        val code = runCatching { followUp.await() }.getOrNull()
        assertEquals("新请求必须立即执行并成功（permit 无泄漏）", 200, code)
        assertEquals(
            "最终恰好 $MAX_BRIDGE_THREADS + 1 个请求进入网络层",
            MAX_BRIDGE_THREADS + 1,
            enteredIndexes.size,
        )
        assertEquals("服务端恰见 ${MAX_BRIDGE_THREADS + 1} 个请求", MAX_BRIDGE_THREADS + 1, webServer.requestCount)
    }

    @Test
    fun `staged release completes all 21 calls with no loss`() = runBlocking {
        val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = s
        val client = barrierClient()
        val jobs = (0 until TOTAL_REQUESTS).map { s.launchBridged(client, it) }

        assertTrue(awaitCondition(5_000) { enteredIndexes.size >= MAX_BRIDGE_THREADS })
        assertEquals(MAX_BRIDGE_THREADS, enteredIndexes.size)

        // 第一阶段：放行 16 → 全部完成并归还 permit → 排队 5 依次进入网络层
        gate.release(MAX_BRIDGE_THREADS)
        assertTrue(
            "剩余 $QUEUED 个排队者必须全部进入网络层",
            awaitCondition(5_000) { enteredIndexes.size == TOTAL_REQUESTS },
        )
        assertTrue(awaitCondition(5_000) { passedIndexes.size == MAX_BRIDGE_THREADS })
        assertEquals("第二阶段放行前，服务端只见首批 ${MAX_BRIDGE_THREADS} 个", MAX_BRIDGE_THREADS, webServer.requestCount)

        // 第二阶段：放行最后 5 → 21 个全部完成，无丢失
        gate.release(QUEUED)
        val codes = jobs.map { runCatching { it.await() }.getOrNull() }
        assertEquals("21 个调用必须全部成功返回 200", List(TOTAL_REQUESTS) { 200 }, codes)
        assertEquals("服务端恰见 $TOTAL_REQUESTS 个请求", TOTAL_REQUESTS, webServer.requestCount)
        assertEquals(TOTAL_REQUESTS, passedIndexes.size)
    }

    /** 有界条件等待（20ms 轮询），不靠裸 sleep 伪造竞态。 */
    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(20)
        }
        return true
    }

    /** 条件在窗口内持续成立（稳定性观察）。 */
    private fun awaitStable(windowMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (!condition()) return false
            Thread.sleep(20)
        }
        return condition()
    }
}
