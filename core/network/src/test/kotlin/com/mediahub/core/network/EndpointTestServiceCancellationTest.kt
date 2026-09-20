package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
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
import kotlinx.coroutines.cancel
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
import okhttp3.mockwebserver.SocketPolicy
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
    private lateinit var testScope: CoroutineScope
    private lateinit var watchdog: ScheduledExecutorService
    private lateinit var callbacks: ExecutorService
    private val timedOut = AtomicBoolean(false)
    private val calls = CopyOnWriteArrayList<Call>()
    private val clients = CopyOnWriteArrayList<OkHttpClient>()
    private val releases = CopyOnWriteArrayList<() -> Unit>()
    private val extraDispatchers = CopyOnWriteArrayList<kotlinx.coroutines.ExecutorCoroutineDispatcher>()
    private val cleanupFailures = CopyOnWriteArrayList<Throwable>()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        events.reset()
        testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        callbacks = Executors.newSingleThreadExecutor()
        watchdog = Executors.newSingleThreadScheduledExecutor()
        // 在任何被测代码之前建立独立逃生通道：同步 execute / 丢失取消绑定也必须有界退出。
        watchdog.schedule({
            timedOut.set(true)
            releaseAndCancel()
        }, WATCHDOG_SECONDS, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        try {
            releaseAndCancel()
            cleanup {
                runBlocking {
                    withTimeout(5_000) { testScope.coroutineContext[kotlinx.coroutines.Job]!!.join() }
                }
            }
            shutdownExecutor(callbacks, "回调执行器")
            extraDispatchers.forEach { dispatcher ->
                cleanup { dispatcher.close() }
                shutdownExecutor(dispatcher.executor as ExecutorService, "调用方执行器")
            }
            clients.distinctBy { it.dispatcher }.forEach { client ->
                cleanup { client.dispatcher.cancelAll() }
                cleanup { client.connectionPool.evictAll() }
                shutdownExecutor(client.dispatcher.executorService, "OkHttp 执行器")
            }
            cleanup { server.shutdown() }
        } finally {
            cleanup { watchdog.shutdownNow() }
            cleanup { check(watchdog.awaitTermination(5, TimeUnit.SECONDS)) { "watchdog 执行器未终止" } }
        }
        if (timedOut.get()) cleanupFailures += AssertionError("独立 watchdog 被触发，被测实现未及时终止")
        if (cleanupFailures.isNotEmpty()) {
            throw AssertionError("测试资源清理失败").apply { cleanupFailures.forEach(::addSuppressed) }
        }
    }

    // ---- 1. 调用方调度器不被阻塞 ----

    @Test
    fun `api layer does not block the caller dispatcher`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val caller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        extraDispatchers += caller
        val callerJob = SupervisorJob(testScope.coroutineContext[kotlinx.coroutines.Job])
        val scope = CoroutineScope(caller + callerJob)
        try {
            val subject = service(caller)
            runBlocking {
                val job = scope.launch { runCatching { subject.test(baseUrl(), "/probe") } }
                awaitTrue("API 请求已发出") { server.requestCount == 1 }

                // 同一调度器上的标记任务：旧实现（阻塞 execute 占用调用方线程）会饿死它
                val marker = AtomicBoolean(false)
                scope.launch { marker.set(true) }
                withContext(Dispatchers.Default) {
                    withTimeout(2_000) { while (!marker.get()) delay(20) }
                }
                assertTrue("API 等待响应头期间调用方调度器必须仍可执行其它任务", marker.get())
                job.cancel()
                job.join()
            }
        } finally {
            callerJob.cancel()
            calls.forEach { call -> cleanup { call.cancel() } }
            cleanup { runBlocking { withTimeout(5_000) { callerJob.join() } } }
        }
    }

    // ---- 2. 开始前已取消 ----

    @Test
    fun `cancellation before request start sends no request`() = runBlocking {
        val gate = GatedDispatcher(Dispatchers.IO)
        val subject = service(gate)
        gate.hold = true
        releases += { gate.release.countDown() }

        val job = testScope.launch {
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
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        var outcome: Result<EndpointTestResult>? = null
        val subject = service(Dispatchers.IO)
        val job = testScope.launch {
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

    // ---- 4. Media 等待响应头 / 读体停滞时取消 ----

    @Test
    fun `cancel while awaiting media headers aborts the media call`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val subject = service(Dispatchers.IO)
        var outcome: Result<EndpointTestResult>? = null
        val job = testScope.launch { outcome = runCatching { subject.test(baseUrl(), "/probe") } }
        awaitTrue("目标 Media 请求已到达、服务端不交付响应头") { server.requestCount == 2 }
        val apiCall = calls.single { it.request().header("Range") == null }
        val mediaCall = calls.single { it.request().header("Range") != null }

        job.cancel()
        awaitTrue("目标 Media Call 的底层等待已终止") { mediaCall in events.failedCalls }
        withTimeout(5_000) { job.join() }

        assertTrue("必须取消对应 Media Call", mediaCall in events.canceledCalls)
        assertTrue("API 响应已释放", apiCall in events.bodyEndedCalls)
        assertTrue("调用方必须收到 CancellationException", outcome!!.exceptionOrNull() is CancellationException)
        assertEquals("服务端未提供 Media 响应，不得误入读体阶段", 0, events.bodyEndedCalls.count { it === mediaCall })
    }

    @Test
    fun `cancel during stalled media body read aborts the call`() = runBlocking {
        val apiCall = ControlledCall(requestFor())
        val mediaCall = ControlledCall(requestFor())
        val stalledBody = StalledBody()
        releases += { stalledBody.releaseForCleanup() }
        mediaCall.onCancel = { stalledBody.cancelRead() }
        val subject = scripted(listOf(apiCall, mediaCall))
        var outcome: Result<EndpointTestResult>? = null
        val job = testScope.launch { outcome = runCatching { subject.test(baseUrl(), "/probe") } }
        awaitTrue("API Call 已 enqueue") { apiCall.enqueued }
        apiCall.deliver(response(apiCall, 200, RecordingBody("{}".toByteArray())))
        awaitTrue("目标 Media Call 已 enqueue") { mediaCall.enqueued }
        // 兼容在回调内消费响应的实现；异常通过 Future 回收到测试线程，不能悄悄丢失。
        val delivery = callbacks.submit { mediaCall.deliver(response(mediaCall, 206, stalledBody)) }
        awaitLatch("目标 Media 响应已进入 source.read", stalledBody.entered)

        job.cancel()
        awaitLatch("取消使目标 Media 的 source.read 退出", stalledBody.exited)
        withTimeout(5_000) { job.join() }
        awaitCallback(delivery)

        assertTrue("必须取消对应 Media Call，不能由 API 的正常收尾满足断言", mediaCall.cancelled)
        assertTrue("读取必须因对应 Call.cancel 退出", stalledBody.cancelledRead.get())
        assertTrue("Media 响应必须关闭", stalledBody.closed.get())
        assertTrue("调用方必须收到 CancellationException", outcome!!.exceptionOrNull() is CancellationException)
        assertTrue("取消不得交付普通结果", outcome!!.isFailure)
    }

    // ---- 5. 取消引起的底层失败不被降级 ----

    @Test
    fun `underlying failure caused by cancellation is not surfaced as plain result`() = runBlocking {
        val apiCall = ControlledCall(requestFor())
        val subject = scripted(listOf(apiCall))

        var outcome: Result<EndpointTestResult>? = null
        val job = testScope.launch {
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
        val job = testScope.launch {
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
        calls += listOf(apiCall, mediaCall)
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
        val job = testScope.launch {
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

    private val client: OkHttpClient by lazy { OkHttpClient().also { clients += it } }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun requestFor(): Request =
        Request.Builder().url("http://127.0.0.1/placeholder").build()

    private fun service(dispatcher: CoroutineDispatcher): EndpointTestService {
        val factory = HttpClientFactory(StdoutLogger())
        return object : EndpointTestService(factory, clock = { 0L }, ioDispatcher = dispatcher) {
            override fun createApiClient(): OkHttpClient =
                factory.apiClient().newBuilder().eventListener(events).build().also { clients += it }

            override fun createMediaClient(): OkHttpClient =
                factory.mediaClient().newBuilder().eventListener(events).build().also { clients += it }

            override fun newCall(client: OkHttpClient, request: Request): Call =
                super.newCall(client, request).also { calls += it }
        }
    }

    private fun scripted(script: List<ControlledCall>): EndpointTestService {
        calls += script
        return object : EndpointTestService(HttpClientFactory(StdoutLogger()), clock = { 0L }) {
            private val queue = java.util.ArrayDeque<Call>(script)
            override fun createApiClient(): OkHttpClient = client
            override fun createMediaClient(): OkHttpClient = client
            override fun newCall(client: OkHttpClient, request: Request): Call = queue.removeFirst()
        }
    }

    private fun response(call: Call, code: Int, body: ResponseBody?): Response =
        Response.Builder()
            .request(call.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body)
            .build()

    private suspend fun awaitTrue(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        withContext(Dispatchers.Default) {
            try {
                withTimeout(timeoutMs) { while (!cond()) delay(20) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("等待超时：$what", e)
            }
        }
    }

    /** 不抛超时的等待：把「实现是否提前失败」纳入断言消息，便于定位。 */
    private suspend fun awaitAny(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean =
        withContext(Dispatchers.Default) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (!cond() && System.nanoTime() < deadline) delay(20)
            val ok = cond()
            if (!ok) println("[await] 超时未满足: $what")
            ok
        }

    private suspend fun awaitLatch(what: String, latch: CountDownLatch) = withContext(Dispatchers.Default) {
        assertTrue("等待超时：$what", latch.await(5, TimeUnit.SECONDS))
    }

    private suspend fun awaitCallback(delivery: Future<*>) = withContext(Dispatchers.Default) {
        delivery.get(5, TimeUnit.SECONDS)
    }

    private fun cleanup(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            cleanupFailures += error
        }
    }

    private fun shutdownExecutor(executor: ExecutorService, label: String) {
        cleanup {
            // cancel 已中止网络请求；允许现有回调和工厂的短暂重试收尾自行退出。
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupFailures += AssertionError("$label 未在宽限期内终止，执行强制回收")
                executor.shutdownNow()
                check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "$label 强制回收后仍未终止" }
            }
        }
    }

    private fun releaseAndCancel() {
        // 不把「清理释放」冒充取消证据：StalledBody 会另外记录读取是否真因 Call.cancel 退出。
        releases.forEach { release -> cleanup(release) }
        cleanup { testScope.cancel() }
        calls.forEach { call -> cleanup { call.cancel() } }
    }

    /** 记录 OkHttp 取消事件：证明取消穿透到底层 Call。 */
    private class RecordingEventListener : EventListener() {
        val canceled = AtomicInteger(0)
        val canceledCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
        val failedCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
        val bodyEndedCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

        fun reset() {
            canceled.set(0)
            canceledCalls.clear()
            failedCalls.clear()
            bodyEndedCalls.clear()
        }

        override fun canceled(call: Call) {
            canceled.incrementAndGet()
            canceledCalls += call
        }

        override fun callFailed(call: Call, ioe: IOException) { failedCalls += call }
        override fun responseBodyEnd(call: Call, byteCount: Long) { bodyEndedCalls += call }
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
        var onCancel: (() -> Unit)? = null

        override fun request(): Request = req

        override fun execute(): Response = throw UnsupportedOperationException("controlled call")

        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
            enqueued = true
        }

        override fun cancel() {
            if (cancelled) return
            cancelled = true
            onCancel?.invoke()
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

    /** read 没有自然完成路径；只有目标 Call.cancel 或失败清理可以释放屏障。 */
    private class StalledBody : ResponseBody() {
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val cancelledRead = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        private val release = CountDownLatch(1)
        private val callCancelled = AtomicBoolean(false)
        private val bufferedSource = object : okio.Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                entered.countDown()
                try {
                    release.await()
                    if (callCancelled.get()) {
                        cancelledRead.set(true)
                        throw IOException("Canceled during media body read")
                    }
                    throw IOException("Released by failure cleanup")
                } finally {
                    exited.countDown()
                }
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()

        fun cancelRead() {
            callCancelled.set(true)
            release.countDown()
        }

        fun releaseForCleanup() = release.countDown()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = 1L
        override fun source(): BufferedSource = bufferedSource
        override fun close() {
            closed.set(true)
            super.close()
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
        const val WATCHDOG_SECONDS = 20L
    }
}
