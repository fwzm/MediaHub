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
                    // Headers/a partial body may already be buffered; drain to EOF with a socket deadline.
                    while (local.getInputStream().read() != -1) { }
                    assertTrue(canceled.await(3, TimeUnit.SECONDS))
                    assertTrue(target.get().isCanceled())
                    assertTrue("target I/O terminates before server gate opens", terminal.await(3, TimeUnit.SECONDS))
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
