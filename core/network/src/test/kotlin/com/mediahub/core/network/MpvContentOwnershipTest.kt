package com.mediahub.core.network

import java.io.ByteArrayInputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class MpvContentOwnershipTest {
    @Test fun aDocumentOpenedAfterStopIsClosedWithoutEscapingTheSession() {
        val entered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val streamClosed = CountDownLatch(1)
        val streamCloses = AtomicInteger()
        val sourceCloses = AtomicInteger()
        val source = object : ContentByteSource {
            override fun info() = ContentByteSource.Info(3, "video/mp4")
            override fun open(): ByteArrayInputStream {
                entered.countDown()
                check(releaseOpen.await(5, TimeUnit.SECONDS))
                return object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                    override fun close() { streamCloses.incrementAndGet(); streamClosed.countDown() }
                }
            }
            override fun close() { sourceCloses.incrementAndGet() }
        }
        val bridge = MpvHttpBridge(OkHttpClient())
        val uri = URI(bridge.startContent(source))
        Socket(uri.host, uri.port).use { socket ->
            try {
                socket.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                bridge.stop()
                assertEquals(1, sourceCloses.get())
                releaseOpen.countDown()
                assertTrue(streamClosed.await(5, TimeUnit.SECONDS))
                assertEquals(1, streamCloses.get())
            } finally { releaseOpen.countDown(); bridge.stop() }
        }
    }

    @Test fun aRevokedDocumentReturnsAnEmptyFixedErrorWithoutItsUriOrException() {
        val secret = "content://fixture.documents/tree/private-diagnostic"
        val source = object : ContentByteSource {
            override fun info(): ContentByteSource.Info = throw SecurityException(secret)
            override fun open(): ByteArrayInputStream = error("Denied metadata must not open")
            override fun close() = Unit
        }
        val bridge = MpvHttpBridge(OkHttpClient())
        val uri = URI(bridge.startContent(source))
        try {
            Socket(uri.host, uri.port).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                val response = socket.getInputStream().readBytes().toString(Charsets.US_ASCII)
                assertTrue(response.startsWith("HTTP/1.1 404"))
                assertTrue(response.contains("Content-Length: 0"))
                assertFalse(response.contains(secret))
                assertTrue(response.endsWith("\r\n\r\n"))
            }
        } finally { bridge.stop() }
    }
}
