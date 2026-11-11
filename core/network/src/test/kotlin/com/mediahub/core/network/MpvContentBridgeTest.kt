package com.mediahub.core.network

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class MpvContentBridgeTest {
    private val bytes = "fixture-content-0123456789".toByteArray()
    private class Source(val bytes: ByteArray, val length: Long? = bytes.size.toLong()) : ContentByteSource {
        val opens = AtomicInteger(); val streamCloses = AtomicInteger(); val closes = AtomicInteger()
        var blocked = false
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        override fun info() = ContentByteSource.Info(length, "video/mp4")
        override fun open(): InputStream {
            opens.incrementAndGet()
            return object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (blocked) { entered.countDown(); check(released.await(5, TimeUnit.SECONDS)) }
                    return super.read(b, off, len)
                }
                override fun close() { streamCloses.incrementAndGet(); released.countDown(); super.close() }
            }
        }
        override fun close() { closes.incrementAndGet(); released.countDown() }
    }
    private data class Reply(val head: String, val body: ByteArray)
    private fun fetch(url: String, method: String = "GET", headers: String = ""): Reply {
        val uri = URI(url)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(("$method ${uri.rawPath} HTTP/1.1\r\nHost: localhost\r\n$headers\r\n").toByteArray())
            socket.getOutputStream().flush()
            val all = socket.getInputStream().readBytes()
            val separator = byteArrayOf(13, 10, 13, 10)
            val at = (0..all.size - 4).firstOrNull { i -> separator.indices.all { all[i + it] == separator[it] } }
            assertNotNull("Bridge must return an HTTP response", at)
            return Reply(all.copyOfRange(0, at!!).toString(Charsets.US_ASCII), all.copyOfRange(at + 4, all.size))
        }
    }
    private fun start(bridge: MpvHttpBridge, source: Source): String {
        val result = runCatching { bridge.startContent(source) }
        assertTrue("Document bytes must be served without an HTTP upstream", result.isSuccess)
        return result.getOrThrow()
    }

    @Test fun contentGetAndHeadHaveTheSelectedBytesAndLength() {
        val bridge = MpvHttpBridge(OkHttpClient()); val source = Source(bytes)
        try {
            val url = start(bridge, source)
            val head = fetch(url, "HEAD")
            assertTrue(head.head.startsWith("HTTP/1.1 200")); assertTrue(head.head.contains("Content-Length: ${bytes.size}"))
            assertEquals(0, source.opens.get()); assertEquals(0, head.body.size)
            val get = fetch(url)
            assertArrayEquals(bytes, get.body); assertEquals(1, source.opens.get()); assertEquals(1, source.streamCloses.get())
        } finally { bridge.stop() }
        assertEquals(1, source.closes.get())
    }

    @Test fun contentRangeSuffixAndUnsatisfiableRequestsAreBounded() {
        val bridge = MpvHttpBridge(OkHttpClient()); val source = Source(bytes)
        try {
            val url = start(bridge, source)
            val range = fetch(url, headers = "Range: bytes=3-7\r\n")
            assertTrue(range.head.startsWith("HTTP/1.1 206")); assertArrayEquals(bytes.copyOfRange(3, 8), range.body)
            assertTrue(range.head.contains("Content-Range: bytes 3-7/${bytes.size}"))
            assertArrayEquals(bytes.takeLast(4).toByteArray(), fetch(url, headers = "Range: bytes=-4\r\n").body)
            val bad = fetch(url, headers = "Range: bytes=999-\r\n")
            assertTrue(bad.head.startsWith("HTTP/1.1 416")); assertEquals(2, source.opens.get())
            assertArrayEquals(bytes, fetch(url, headers = "Range: bytes=3-7\r\nIf-Range: fixture\r\n").body)
        } finally { bridge.stop() }
    }

    @Test fun unknownLengthStreamsDoNotAdvertiseRandomAccess() {
        val bridge = MpvHttpBridge(OkHttpClient()); val source = Source(bytes, null)
        try {
            val result = fetch(start(bridge, source), headers = "Range: bytes=2-5\r\n")
            assertTrue(result.head.startsWith("HTTP/1.1 200")); assertFalse(result.head.contains("Content-Length:"))
            assertFalse(result.head.contains("Accept-Ranges: bytes")); assertArrayEquals(bytes, result.body)
        } finally { bridge.stop() }
    }

    @Test fun stopClosesTheActiveDocumentStreamAndSourceExactlyOnce() {
        val bridge = MpvHttpBridge(OkHttpClient()); val source = Source(bytes).also { it.blocked = true }
        val url = start(bridge, source); val uri = URI(url)
        val socket = Socket(uri.host, uri.port)
        try {
            socket.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            assertTrue(source.entered.await(5, TimeUnit.SECONDS))
            bridge.stop(); bridge.stop()
            assertEquals(1, source.streamCloses.get()); assertEquals(1, source.closes.get())
        } finally { bridge.stop(); socket.close() }
    }

    @Test fun replacementRevokesTheOldCapabilityAndClosesItsSource() {
        val bridge = MpvHttpBridge(OkHttpClient()); val a = Source(bytes); val b = Source("next".toByteArray())
        try {
            val old = start(bridge, a); val next = start(bridge, b)
            assertEquals(1, a.closes.get()); assertNotEquals(old, next)
            assertArrayEquals("next".toByteArray(), fetch(next).body)
            val wrong = URI(next).let { "http://${it.host}:${it.port}/media/fixture-wrong-token" }
            assertTrue(fetch(wrong).head.startsWith("HTTP/1.1 404")); assertEquals(1, b.opens.get())
        } finally { bridge.stop() }
        assertEquals(1, b.closes.get())
    }
}
