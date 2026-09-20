package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * EndpointTestService 取消契约回归（Phase 1I 2C）。
 *
 * 覆盖：调用方调度器不被阻塞、开始前取消不发请求、等待响应头时取消真正中止 Call、
 * 读体停滞时取消中止 Call、取消引起的底层失败不被降级为普通失败、迟到响应被关闭、
 * 媒体采样严格限制在 1 MiB。
 *
 * 断言全部基于可观察事实（MockWebServer 请求数、OkHttp EventListener canceled 事件、
 * 受控 Call 的响应释放与消费字节），不依赖固定 sleep 伪造并发；等待为有界条件轮询。
 */
class EndpointTestServiceCancellationTest {

    private lateinit var server: MockWebServer
    private val events = RecordingEventListener()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        events.reset()
    }

    @After
    fun tearDown() {
        // 断言失败时同样要释放端口与后台任务：服务端可能仍在延迟写入已取消的连接
        runCatching { server.shutdown() }
    }

    // ---- 1. 调用方调度器不被阻塞 ----

    @Test
    fun `api layer does not block the caller dispatcher`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}")
            .setHeadersDelay(RESPONSE_DELAY_MS, TimeUnit.MILLISECONDS))

        val caller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val scope = CoroutineScope(caller + SupervisorJob())
            val subject = service(caller)
            runBlocking {
                val job = scope.launch { runCatching { subject.test(baseUrl(), "/probe") } }
                awaitTrue("API 请求已发出") { server.requestCount == 1 }

                // 同一调度器上的标记任务：旧实现（阻塞 execute 占用调用方线程）会饿死它
                val marker = AtomicBoolean(false)
                scope.launch { marker.set(true) }
                withContext(Dispatchers.Default) {
                    withTimeout(RESPONSE_DELAY_MS / 2) { while (!marker.get()) delay(20) }
                }
                assertTrue("API 等待响应头期间调用方调度器必须仍可执行其它任务", marker.get())
                job.cancel()
                job.join()
            }
        } finally {
            caller.close()
        }
    }

    // ---- 2. 开始前已取消 ----

    @Test
    fun `cancellation before request start sends no request`() = runBlocking {
        val gate = GatedDispatcher(Dispatchers.IO)
        val subject = service(gate)
        gate.hold = true

        val job = launch(Dispatchers.Default) {
            runCatching { subject.test(baseUrl(), "/probe") }
        }
        withContext(Dispatchers.Default) {
            assertTrue("协程已进入 test 但被闸门扣住", gate.entered.await(5, TimeUnit.SECONDS))
        }
        job.cancel()
        gate.release.countDown()
        withTimeout(10_000) { job.join() }

        assertTrue("取消必须以 CancellationException 结束", job.isCancelled)
        assertEquals("开始前已取消不得发出任何请求", 0, server.requestCount)
        assertEquals(0, events.canceled.get())
    }

    // ---- 3. 等待响应头时取消 ----

    @Test
    fun `cancel while awaiting api headers aborts the call and skips media`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}")
            .setHeadersDelay(RESPONSE_DELAY_MS, TimeUnit.MILLISECONDS))

        var outcome: Result<EndpointTestResult>? = null
        val subject = service(Dispatchers.IO)
        val job = launch(Dispatchers.Default) {
            outcome = runCatching { subject.test(baseUrl(), "/probe") }
        }
        awaitTrue("API 请求已到达 MockWebServer") { server.requestCount == 1 }
        job.cancel()
        withTimeout(10_000) { job.join() }

        assertTrue("取消不得返回普通结果", job.isCancelled)
        assertTrue("底层 Call 必须被真正取消", events.canceled.get() >= 1)
        assertEquals("取消后不得进入 Media 阶段", 1, server.requestCount)
        assertTrue(outcome!!.isFailure)
        assertTrue(
            "取消必须原样传播，不得被换成普通失败",
            outcome!!.exceptionOrNull() is CancellationException,
        )
    }

    // ---- 4. 读体停滞时取消 ----

    @Test
    fun `cancel during stalled media body read aborts the call`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setBody(Buffer().write(ByteArray(4 * 1024 * 1024)))
                .setBodyDelay(RESPONSE_DELAY_MS, TimeUnit.MILLISECONDS)
                .addHeader("Accept-Ranges", "bytes")
        )

        val subject = service(Dispatchers.IO)
        val job = launch(Dispatchers.Default) {
            runCatching { subject.test(baseUrl(), "/probe") }
        }
        awaitTrue("Media 响应头已返回、响应体停滞") { server.requestCount == 2 }
        job.cancel()
        withTimeout(15_000) { job.join() }

        assertTrue(job.isCancelled)
        assertTrue("停滞读体必须随取消被中止", events.canceled.get() >= 1)
    }

    // ---- 5. 取消引起的底层失败不被降级 ----

    @Test
    fun `underlying failure caused by cancellation is not surfaced as plain result`() = runBlocking {
        val apiCall = ControlledCall(requestFor())
        val subject = scripted(listOf(apiCall))

        var outcome: Result<EndpointTestResult>? = null
        val job = launch(Dispatchers.Default) {
            outcome = runCatching { subject.test(baseUrl(), "/probe") }
        }
        awaitAny("受控 API Call 已 enqueue 或实现已提前结束") { apiCall.enqueued || outcome != null }
        assertTrue(
            "受控 API Call 必须被使用；实现提前失败=${outcome?.exceptionOrNull() ?: outcome}",
            apiCall.enqueued,
        )
        job.cancel()
        // 取消触发的底层失败在取消之后才回调：必须被忽略而不是转成 EndpointTestResult
        apiCall.fail(IOException("Canceled"))
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled)
        assertTrue(
            "取消引起的 IOException 不得成为普通失败结果",
            outcome!!.exceptionOrNull() is CancellationException,
        )
    }

    // ---- 6. 取消后的迟到响应被关闭 ----

    @Test
    fun `late response delivered after cancellation is closed and ignored`() = runBlocking {
        val apiCall = ControlledCall(requestFor())
        val body = RecordingBody(ByteArray(16))
        val subject = scripted(listOf(apiCall))

        var outcome: Result<EndpointTestResult>? = null
        val job = launch(Dispatchers.Default) {
            outcome = runCatching { subject.test(baseUrl(), "/probe") }
        }
        awaitAny("受控 API Call 已 enqueue 或实现已提前结束") { apiCall.enqueued || outcome != null }
        assertTrue(
            "受控 API Call 必须被使用；实现提前失败=${outcome?.exceptionOrNull()}",
            apiCall.enqueued,
        )
        job.cancel()
        withTimeout(5_000) { job.join() }

        apiCall.deliver(response(apiCall, 200, body)) // 迟到响应
        assertEquals("迟到响应不得被消费", 0L, body.consumed)
        assertTrue("迟到响应必须被关闭", body.closed)
        assertTrue("不得向调用方交付普通结果", outcome!!.isFailure)
    }

    // ---- 7. 媒体采样上限 ----

    @Test
    fun `media sampling is capped at one mebibyte when server ignores range`() = runBlocking {
        val apiCall = ControlledCall(requestFor())
        val mediaCall = ControlledCall(requestFor())
        val oversized = RecordingBody(ByteArray(4 * 1024 * 1024))

        var tick = 0L
        val subject = object : EndpointTestService(
            HttpClientFactory(StdoutLogger()), clock = { tick += 1_000L; tick },
        ) {
            private val queue = java.util.ArrayDeque<Call>(listOf(apiCall, mediaCall))
            override fun createApiClient(): OkHttpClient = client
            override fun createMediaClient(): OkHttpClient = client
            override fun newCall(client: OkHttpClient, request: Request): Call = queue.removeFirst()
        }

        var result: EndpointTestResult? = null
        val job = launch(Dispatchers.Default) {
            result = subject.test(baseUrl(), "/probe")
        }
        awaitTrue("API Call 已 enqueue") { apiCall.enqueued }
        apiCall.deliver(response(apiCall, 200, RecordingBody("{}".toByteArray())))
        awaitTrue("Media Call 已 enqueue") { mediaCall.enqueued }
        mediaCall.deliver(response(mediaCall, 200, oversized)) // 200：服务端忽略 Range
        withTimeout(10_000) { job.join() }

        assertEquals(
            "应用层消费不得超过 1 MiB",
            EndpointTestService.MAX_MEDIA_BYTES, oversized.consumed,
        )
        assertTrue("响应必须被关闭", oversized.closed)
        assertNotNull("有实际消费即应给出吞吐", result!!.mediaThroughputMbps)
        assertEquals(200, result!!.httpCode)
        assertTrue("无 206 也无 Accept-Ranges 时不得声明支持 Range", !result!!.supportsRange)
    }

    // ---- helpers ----

    private val client: OkHttpClient by lazy { OkHttpClient() }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun requestFor(): Request =
        Request.Builder().url("http://127.0.0.1/placeholder").build()

    private fun service(dispatcher: CoroutineDispatcher): EndpointTestService {
        val factory = HttpClientFactory(StdoutLogger())
        return object : EndpointTestService(factory, clock = { 0L }, ioDispatcher = dispatcher) {
            override fun createApiClient(): OkHttpClient =
                factory.apiClient().newBuilder().eventListener(events).build()

            override fun createMediaClient(): OkHttpClient =
                factory.mediaClient().newBuilder().eventListener(events).build()
        }
    }

    private fun scripted(calls: List<ControlledCall>): EndpointTestService =
        object : EndpointTestService(HttpClientFactory(StdoutLogger()), clock = { 0L }) {
            private val queue = java.util.ArrayDeque<Call>(calls)
            override fun createApiClient(): OkHttpClient = client
            override fun createMediaClient(): OkHttpClient = client
            override fun newCall(client: OkHttpClient, request: Request): Call = queue.removeFirst()
        }

    private fun response(call: Call, code: Int, body: ResponseBody?): Response =
        Response.Builder()
            .request(call.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body)
            .build()

    private suspend fun awaitTrue(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        withContext(Dispatchers.Default) {
            try {
                withTimeout(timeoutMs) { while (!cond()) delay(20) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("等待超时：$what", e)
            }
        }
    }

    /** 不抛超时的等待：把「实现是否提前失败」纳入断言消息，便于定位。 */
    private suspend fun awaitAny(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean): Boolean =
        withContext(Dispatchers.Default) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!cond() && System.nanoTime() < deadline) delay(20)
            val ok = cond()
            if (!ok) println("[await] 超时未满足: $what")
            ok
        }

    /** 记录 OkHttp 取消事件：证明取消穿透到底层 Call。 */
    private class RecordingEventListener : EventListener() {
        val canceled = AtomicInteger(0)

        fun reset() {
            canceled.set(0)
        }

        override fun canceled(call: Call) {
            canceled.incrementAndGet()
        }
    }

    /** 受控 Call：由测试决定何时交付响应/失败，用于构造取消竞态。 */
    private class ControlledCall(private val req: Request) : Call {
        @Volatile
        var enqueued = false
            private set

        @Volatile
        var cancelled = false
            private set

        private var callback: Callback? = null

        override fun request(): Request = req

        override fun execute(): Response = throw UnsupportedOperationException("controlled call")

        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
            enqueued = true
        }

        override fun cancel() {
            cancelled = true
        }

        override fun isExecuted(): Boolean = enqueued

        override fun isCanceled(): Boolean = cancelled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = ControlledCall(req)

        fun deliver(response: Response) {
            callback?.onResponse(this, response)
        }

        fun fail(error: IOException) {
            callback?.onFailure(this, error)
        }
    }

    /** 可观测响应体：记录应用层实际消费字节数与关闭状态。 */
    private class RecordingBody(private val payload: ByteArray) : ResponseBody() {
        private val backing = Buffer().write(payload)

        @Volatile
        var consumed = 0L
            private set

        @Volatile
        var closed = false
            private set

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = payload.size.toLong()

        override fun source(): BufferedSource =
            object : ForwardingSource(backing) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0) consumed += n
                    return n
                }
            }.buffer()

        override fun close() {
            closed = true
            super.close()
        }
    }

    /** 受控调度器：把首次执行扣在闸门上，用于构造「进入协程前已取消」窗口。 */
    private class GatedDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        @Volatile
        var hold = false

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                if (hold) {
                    entered.countDown()
                    release.await(15, TimeUnit.SECONDS)
                }
                block.run()
            }
        }
    }

    private companion object {
        /** 服务端延迟：够长以构造在途窗口，够短以便 tearDown 能正常回收 MockWebServer。 */
        const val RESPONSE_DELAY_MS = 2_000L
    }
}
