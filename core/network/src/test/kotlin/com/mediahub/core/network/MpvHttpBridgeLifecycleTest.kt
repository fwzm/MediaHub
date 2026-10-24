package com.mediahub.core.network

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
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
    private fun client(readTimeoutSeconds: Long = 3) =
        OkHttpClient.Builder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()

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

    @Test fun `stop cancels target upstream and closes accepted socket before server responds`() = proveStop()

    private fun proveStop() {
        MockWebServer().use { upstream ->
            upstream.start()
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            upstream.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody("late")
                }
            }
            val canceled = CountDownLatch(1)
            val terminal = CountDownLatch(1)
            val target = AtomicReference<Call>()
            // A generous read timeout keeps "terminated" attributable to cancel alone: a read
            // timeout cannot fire inside the terminal budget asserted below.
            val http = client(readTimeoutSeconds = 10).newBuilder().eventListenerFactory { call ->
                target.set(call)
                object : EventListener() {
                    override fun canceled(eventCall: Call) { assertSame(call, eventCall); canceled.countDown() }
                    override fun callFailed(eventCall: Call, ioe: IOException) { assertSame(call, eventCall); terminal.countDown() }
                }
            }.build()
            val bridge = MpvHttpBridge(http)
            try {
                val url = bridge.start(upstream.url("/media").toString(), emptyMap())
                socket(url, "GET ${URI(url).path} HTTP/1.1").use { local ->
                    assertTrue(arrived.await(3, TimeUnit.SECONDS))
                    bridge.stop()
                    // Headers/a partial body may already be buffered; drain to EOF with a short socket
                    // deadline so the drain cannot consume the terminal budget that follows it.
                    local.soTimeout = 1_000
                    while (local.getInputStream().read() != -1) { }
                    assertTrue(canceled.await(3, TimeUnit.SECONDS))
                    assertTrue(target.get().isCanceled())
                    assertTrue("target I/O terminates before server gate opens", terminal.await(5, TimeUnit.SECONDS))
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

    /**
     * Raw loopback upstream that promises more body bytes than it sends and then reports the
     * client hang-up. MockWebServer cannot observe 'the client closed the connection', and the
     * OkHttp callFailed event is not a reliable witness: when bridge.stop() wins the race against
     * the bridge thread's first write to the already-closed local socket, the failure surfaces
     * from that write instead of from OkHttp, so callFailed never fires (CI flake on
     * MpvHttpBridgeLifecycleTest.kt:95). The peer-side hang-up is raised by cancel()/close() on
     * every interleaving, so it is the scheduling-independent witness for this contract.
     */
    private class RawUpstream : java.io.Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private var peer: Socket? = null
        val mediaUrl: String get() = "http://127.0.0.1:" + server.localPort + "/media"
        val requestReceived = CountDownLatch(1)
        val clientHungUp = CountDownLatch(1)
        private val worker = Thread({
            try {
                val accepted = server.accept()
                peer = accepted
                accepted.use {
                    val input = it.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        if (byte == -1) return@use
                        head.append(byte.toChar())
                    }
                    requestReceived.countDown()
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 500\r\n\r\n".toByteArray())
                    it.getOutputStream().flush()
                    it.soTimeout = 8_000
                    val hungUp = try {
                        while (input.read() != -1) { }
                        true
                    } catch (_: SocketTimeoutException) {
                        false
                    } catch (_: IOException) {
                        true
                    }
                    if (hungUp) clientHungUp.countDown()
                }
            } catch (_: IOException) {
            } finally {
                runCatching { peer?.close() }
            }
        }, "raw-upstream").apply { isDaemon = true; start() }
        override fun close() {
            runCatching { server.close() }
            runCatching { peer?.close() }
        }
    }

    @Test fun `stop cancels target upstream while response body cannot finish`() {
        RawUpstream().use { upstream ->
            val bodyRead = CountDownLatch(1)
            val canceled = CountDownLatch(1)
            val target = AtomicReference<Call>()
            val http = client(readTimeoutSeconds = 10).newBuilder().eventListenerFactory { call ->
                target.set(call)
                object : EventListener() {
                    override fun responseBodyStart(eventCall: Call) { assertSame(call, eventCall); bodyRead.countDown() }
                    override fun canceled(eventCall: Call) { assertSame(call, eventCall); canceled.countDown() }
                }
            }.build()
            val bridge = MpvHttpBridge(http)
            try {
                val url = bridge.start(upstream.mediaUrl, emptyMap())
                socket(url, "GET " + URI(url).path + " HTTP/1.1").use { local ->
                    assertTrue("bridge forwarded the request upstream", upstream.requestReceived.await(3, TimeUnit.SECONDS))
                    assertTrue("real upstream body read must be reached before stop", bodyRead.await(3, TimeUnit.SECONDS))
                    bridge.stop()
                    local.soTimeout = 1_000
                    while (local.getInputStream().read() != -1) { }
                    assertTrue(canceled.await(3, TimeUnit.SECONDS))
                    assertTrue(target.get().isCanceled())
                    assertTrue("upstream connection must be closed after stop",
                        upstream.clientHungUp.await(5, TimeUnit.SECONDS))
                }
                MockWebServer().use { fresh ->
                    fresh.start()
                    fresh.enqueue(MockResponse().setBody("new-session"))
                    val newer = bridge.start(fresh.url("/new").toString(), emptyMap())
                    client().newCall(Request.Builder().url(newer).build()).execute().use {
                        assertEquals("new-session", it.body!!.string())
                    }
                }
            } finally { bridge.stop() }
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