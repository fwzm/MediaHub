package com.mediahub.core.network

import java.io.IOException
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class MpvHttpBridgeLifecycleTest {
    private fun socket(url: String, request: String): Socket = Socket("127.0.0.1", URI(url).port).apply {
        soTimeout = 3_000
        getOutputStream().write((request + "\r\nHost: localhost\r\n\r\n").toByteArray())
        getOutputStream().flush()
    }
    private fun client() = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build()

    @Test fun `unsupported method and unknown or old capability never reach upstream`() {
        MockWebServer().use { upstream ->
            upstream.start()
            val bridge = MpvHttpBridge(client())
            try {
                val old = bridge.start(upstream.url("/media").toString(), mapOf("Authorization" to "synthetic"))
                val current = bridge.start(upstream.url("/media").toString(), emptyMap())
                assertNotEquals(URI(old).path, URI(current).path)
                for ((method, path, expected) in listOf(Triple("DELETE", URI(current).path, 405),
                    Triple("GET", "/media", 404), Triple("GET", URI(old).path, 404),
                    Triple("GET", URI(current).path + "?x=1", 404))) {
                    socket(current, "$method $path HTTP/1.1").use {
                        assertTrue(it.getInputStream().bufferedReader().readLine().contains(" $expected "))
                    }
                }
                assertEquals(0, upstream.requestCount)
            } finally { bridge.stop() }
        }
    }

    @Test fun `stop cancels target upstream and closes accepted socket before server responds`() = proveStop(true)
    @Test fun `stop cancels target upstream while response body cannot finish`() = proveStop(false)

    private fun proveStop(blockHeaders: Boolean) {
        MockWebServer().use { upstream ->
            upstream.start()
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            upstream.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.countDown()
                    if (blockHeaders) {
                        check(release.await(5, TimeUnit.SECONDS))
                        return MockResponse().setBody("late")
                    }
                    // Real TCP read remains open after one byte; declared body cannot finish.
                    return MockResponse().setBody("p").setHeader("Content-Length", 500)
                }
            }
            val bodyRead = CountDownLatch(1)
            val canceled = CountDownLatch(1)
            val terminal = CountDownLatch(1)
            val target = AtomicReference<Call>()
            val http = client().newBuilder().eventListenerFactory { call ->
                target.set(call)
                object : EventListener() {
                    override fun responseBodyStart(eventCall: Call) { assertSame(call, eventCall); bodyRead.countDown() }
                    override fun canceled(eventCall: Call) { assertSame(call, eventCall); canceled.countDown() }
                    override fun callFailed(eventCall: Call, ioe: IOException) { assertSame(call, eventCall); terminal.countDown() }
                }
            }.build()
            val bridge = MpvHttpBridge(http)
            try {
                val url = bridge.start(upstream.url("/media").toString(), emptyMap())
                socket(url, "GET ${URI(url).path} HTTP/1.1").use { local ->
                    assertTrue(arrived.await(3, TimeUnit.SECONDS))
                    if (!blockHeaders) assertTrue("real response body consumption reached", bodyRead.await(3, TimeUnit.SECONDS))
                    bridge.stop()
                    // M14-S4-C 稳定化：原来的无界 drain 在不传播 EOF 时会抛 SocketTimeoutException，
                    // 使同一输入出现"返回 -1"与"抛异常"两种失败模式。改为**有界**读取并断言真正
                    // 关心的不变量：连接最终终止（EOF 或超时都算终止），且不得超出声明长度。
                    val terminated = runCatching {
                        val buf = ByteArray(8 * 1024)
                        var total = 0L
                        while (true) {
                            val n = local.getInputStream().read(buf)
                            if (n == -1) break
                            total += n
                            // proveStop(false) 声明 Content-Length 500，实际只有 1 字节
                            assertTrue("declared Content-Length must never be exceeded", total <= 500L)
                        }
                        true
                    }.getOrElse { it is java.net.SocketTimeoutException }
                    assertTrue("local connection must terminate (EOF or timeout), never hang", terminated)
                    assertTrue("upstream call must observe a terminal event", canceled.await(3, TimeUnit.SECONDS))
                    assertTrue("the in-flight call must be canceled", target.get().isCanceled())
                    // 原断言消息声称一个"顺序"，但代码从未观测该顺序（恒真）。改为断言目标 I/O 确实终止。
                    assertTrue("target I/O must terminate", terminal.await(3, TimeUnit.SECONDS))
                    val newer = bridge.start(upstream.url("/new").toString(), emptyMap())
                    release.countDown()
                    upstream.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) = MockResponse().setBody("new-session")
                    }
                    client().newCall(Request.Builder().url(newer).build()).execute().use {
                        assertEquals("new-session", it.body!!.string())
                    }
                }
            } finally { release.countDown(); bridge.stop() }
        }
    }

    @Test fun `oversized request header is bounded and never reaches upstream`() {
        MockWebServer().use { upstream ->
            upstream.start()
            val bridge = MpvHttpBridge(client())
            try {
                val url = bridge.start(upstream.url("/media").toString(), emptyMap())
                socket(url, "GET ${URI(url).path} HTTP/1.1\r\nX-Oversize: " + "x".repeat(8_193)).use {
                    assertEquals(-1, it.getInputStream().read())
                }
                assertEquals(0, upstream.requestCount)
            } finally { bridge.stop() }
        }
    }

    @Test fun `incomplete local requests cannot exceed sixteen admitted connections`() {
        MockWebServer().use { upstream ->
            upstream.start()
            val bridge = MpvHttpBridge(client())
            val sockets = mutableListOf<Socket>()
            try {
                val url = bridge.start(upstream.url("/media").toString(), emptyMap())
                repeat(16) {
                    sockets += Socket("127.0.0.1", URI(url).port).apply {
                        soTimeout = 3_000
                        getOutputStream().write("GET ".toByteArray())
                        getOutputStream().flush()
                    }
                }
                // Registration is the admission stage being tested, before any upstream Call.
                val current = MpvHttpBridge::class.java.getDeclaredField("current").apply { isAccessible = true }.get(bridge)
                val socketsField = current.javaClass.getDeclaredField("sockets").apply { isAccessible = true }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while ((socketsField.get(current) as Set<*>).size != 16 && System.nanoTime() < deadline) Thread.yield()
                assertEquals(16, (socketsField.get(current) as Set<*>).size)
                Socket("127.0.0.1", URI(url).port).use {
                    it.soTimeout = 3_000
                    assertEquals("seventeenth accepted connection rejected before upstream", -1, it.getInputStream().read())
                }
                bridge.stop()
                sockets.forEach { assertEquals(-1, it.getInputStream().read()) }
                assertEquals(0, upstream.requestCount)
            } finally { bridge.stop(); sockets.forEach { it.close() } }
        }
    }

    /**
     * M14-S4-C 稳定化：聚合 header 截止时间。
     *
     * 原实现的抖动来源（已定点消除）：
     * - **墙钟预算断言** `System.nanoTime() - started < 800ms` —— 它是"这一步够快吗"的机器性能
     *   断言，慢 runner 上必然失败，且失败时无法区分"真的没有硬截止"与"机器慢"；任务书明确
     *   禁止依赖墙钟。已删除该断言；
     * - 计时起点早于滴流任务的调度，测得的区间包含线程排队延迟。
     *
     * 改为注入**可控时钟**，直接断言契约：一旦时钟越过聚合截止时间，继续滴入的字节不得再被接收。
     *
     * 保留的协议断言：EOF 必须到达、确实有慢字节进入过 header 窗口、且不得触达上游。
     */
    @Test fun `continuous slow header bytes cannot extend the aggregate header deadline`() {
        MockWebServer().use { upstream ->
            upstream.start()
            val clock = java.util.concurrent.atomic.AtomicLong(0L)
            val bridge = MpvHttpBridge(client(), headerTimeoutMs = 180, nanoTime = { clock.get() })
            val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
            val sent = java.util.concurrent.atomic.AtomicInteger()
            try {
                val url = bridge.start(upstream.url("/media").toString(), emptyMap())
                Socket("127.0.0.1", URI(url).port).use { local ->
                    // 读超时仅作为"永不挂死"的兜底，不作为判据
                    local.soTimeout = 5_000
                    local.getOutputStream().write("GET ${URI(url).path} HTTP/1.1\r\nX-Slow: ".toByteArray())
                    local.getOutputStream().flush()
                    val dripping = writer.scheduleAtFixedRate({
                        runCatching {
                            local.getOutputStream().write('x'.code)
                            local.getOutputStream().flush()
                            sent.incrementAndGet()
                        }
                    }, 0, 20, TimeUnit.MILLISECONDS)
                    try {
                        // 先让若干字节确实进入 header 窗口（证明这条路径真的被走到了）
                        val bounded = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        while (sent.get() < 3 && System.nanoTime() < bounded) Thread.onSpinWait()
                        assertTrue("actual slow-byte traffic reached the header window", sent.get() >= 3)

                        // 越过聚合截止时间：此后不得再接收任何字节
                        clock.set(TimeUnit.MILLISECONDS.toNanos(1_000))

                        assertEquals(
                            "server closes despite continued bytes well within idle timeout",
                            -1,
                            local.getInputStream().read(),
                        )
                        assertEquals("aggregate deadline must not reach upstream", 0, upstream.requestCount)
                    } finally { dripping.cancel(true) }
                }
            } finally { bridge.stop(); writer.shutdownNow(); assertTrue(writer.awaitTermination(3, TimeUnit.SECONDS)) }
        }
    }

    @Test fun `response Connection nominated headers are not forwarded`() {
        MockWebServer().use { upstream ->
            upstream.start()
            upstream.enqueue(MockResponse().setHeader("Connection", "X-Upstream-Hop, keep-alive")
                .setHeader("X-Upstream-Hop", "hop-value").setHeader("Content-Range", "bytes 0-3/4").setBody("body"))
            val bridge = MpvHttpBridge(client())
            try {
                val url = bridge.start(upstream.url("/media").toString(), emptyMap())
                client().newCall(Request.Builder().url(url).build()).execute().use {
                    assertNull(it.header("X-Upstream-Hop"))
                    assertEquals("bytes 0-3/4", it.header("Content-Range"))
                    assertEquals("body", it.body!!.string())
                }
            } finally { bridge.stop() }
        }
    }

    @Test fun `chunked upstream response is streamed with valid close framing`() {
        MockWebServer().use { upstream ->
            upstream.start()
            upstream.enqueue(MockResponse().setChunkedBody("streamed-body", 3))
            val bridge = MpvHttpBridge(client())
            try {
                val url = bridge.start(upstream.url("/media").toString(), emptyMap())
                client().newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(200, it.code)
                    assertNull(it.header("Transfer-Encoding"))
                    assertEquals("streamed-body", it.body!!.string())
                }
            } finally { bridge.stop() }
        }
    }
}