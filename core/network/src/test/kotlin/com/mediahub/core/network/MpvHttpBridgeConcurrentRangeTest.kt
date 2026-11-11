package com.mediahub.core.network

import java.io.File
import java.io.RandomAccessFile
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * G6 · M14 残余风险收口：**并发 Range 共享同一会话句柄**的互斥正确性。
 *
 * 既有覆盖（MpvHttpBridgeHandleLifecycleTest）已证明：会话内只打开一次句柄、
 * stop() 恰好关闭一次、声明长度=实际字节（W2 已消除）、stop() 与在途传输并发时 fd 仍释放。
 *
 * 但那里**没有**覆盖「多个并发 Range 在同一句柄上 seek+read 是否互相破坏」。
 * 句柄是会话级共享的，seek 与 read 之间存在天然竞态；生产代码用
 * synchronized(handle) { seek; while(...) read } 保护整段传输。本测试固定该契约 ——
 * 若拿掉同步，不同区间的并发 Range 必然读到错位字节。
 *
 * 说明：这里**不**声称解决 W1（check-then-open）。W1 需平台原子性保证，
 * 本进程内无法确定性构造，按 AGENTS.md §7 保持 UNPROVABLE-IN-PROCESS。
 */
class MpvHttpBridgeConcurrentRangeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class CountingOpener : FileOpener {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        override fun open(file: File): RandomAccessFile {
            opened.incrementAndGet()
            return object : RandomAccessFile(file, "r") {
                private val counted = java.util.concurrent.atomic.AtomicBoolean(false)
                override fun close() {
                    if (counted.compareAndSet(false, true)) closed.incrementAndGet()
                    super.close()
                }
            }
        }
    }

    private class Response(val status: Int, val body: ByteArray)

    private fun request(url: String, range: String?): Response {
        val uri = URI(url)
        Socket("127.0.0.1", uri.port).use { socket ->
            socket.soTimeout = 15000
            val headers = StringBuilder()
            headers.append("GET ").append(uri.path).append(" HTTP/1.1\r\n")
            headers.append("Host: 127.0.0.1\r\n")
            if (range != null) headers.append("Range: ").append(range).append("\r\n")
            headers.append("Connection: close\r\n\r\n")
            socket.getOutputStream().apply { write(headers.toString().toByteArray()); flush() }
            val raw = socket.getInputStream().readBytes()
            val text = String(raw, Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            val status = if (split > 0) text.substring(0, split).split("\r\n").first().split(' ')[1].toInt() else -1
            return Response(status, if (split > 0) raw.copyOfRange(split + 4, raw.size) else ByteArray(0))
        }
    }

    private fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun `concurrent distinct range requests on one session handle each get their own exact slice`() {
        val size = 4 * 1024 * 1024
        val file = temp.newFile("concurrent-range.mp4")
        file.writeBytes(pattern(size))
        val opener = CountingOpener()
        val bridge = MpvHttpBridge(OkHttpClient(), openBody = opener)
        val url = bridge.startLocalFile(file.absolutePath)

        try {
            val windows = listOf(
                0L to 262143L,
                1048576L to 1310719L,
                2097152L to 2359295L,
                3145728L to 4194303L,
            )
            val ready = CountDownLatch(1)
            val results = arrayOfNulls<Response>(windows.size)
            val threads = windows.mapIndexed { index, window ->
                thread(start = true) {
                    ready.await(10, TimeUnit.SECONDS)
                    results[index] = runCatching { request(url, "bytes=" + window.first + "-" + window.second) }
                        .getOrElse { Response(-1, ByteArray(0)) }
                }
            }
            ready.countDown()
            threads.forEach { it.join(30000) }

            assertEquals("并发 Range 必须复用同一个会话句柄（打开次数）", 1, opener.opened.get())

            val expectedAll = pattern(size)
            windows.forEachIndexed { index, window ->
                val response = results[index]!!
                assertEquals("区间 " + window.first + "-" + window.second + " 必须是 206", 206, response.status)
                val expected = expectedAll.copyOfRange(window.first.toInt(), window.second.toInt() + 1)
                assertArrayEquals(
                    "区间 " + window.first + "-" + window.second + " 的字节必须逐字节正确（并发 seek+read 不得互相破坏）",
                    expected,
                    response.body,
                )
            }
        } finally {
            bridge.stop()
        }
        assertEquals("stop() 之后句柄必须恰好关闭一次", 1, opener.closed.get())
    }

    @Test
    fun `stop returns only after the session handle is already closed`() {
        val file = temp.newFile("stop-order.mp4")
        file.writeBytes(pattern(2048))
        val opener = CountingOpener()
        val bridge = MpvHttpBridge(OkHttpClient(), openBody = opener)
        val url = bridge.startLocalFile(file.absolutePath)

        assertEquals(200, request(url, null).status)
        assertEquals(1, opener.opened.get())
        assertEquals("会话存活期间不得关闭", 0, opener.closed.get())

        bridge.stop()
        assertEquals("stop() 返回时句柄必须已关闭（同步契约，不是最终一致）", 1, opener.closed.get())

        bridge.stop()
        assertEquals("重复 stop 不得重复关闭", 1, opener.closed.get())
    }

    @Test
    fun `concurrent range requests never violate the declared content length`() {
        val size = 2 * 1024 * 1024
        val file = temp.newFile("declared.mp4")
        file.writeBytes(pattern(size))
        val opener = CountingOpener()
        val bridge = MpvHttpBridge(OkHttpClient(), openBody = opener)
        val url = bridge.startLocalFile(file.absolutePath)

        try {
            val latch = CountDownLatch(1)
            val outcomes = java.util.Collections.synchronizedList(mutableListOf<LongArray>())
            val threads = (0 until 4).map { i ->
                thread(start = true) {
                    latch.await(10, TimeUnit.SECONDS)
                    val start = i * 524288L
                    val end = start + 262143L
                    val r = runCatching { request(url, "bytes=" + start + "-" + end) }.getOrNull()
                    if (r != null) outcomes.add(longArrayOf(r.status.toLong(), r.body.size.toLong()))
                }
            }
            latch.countDown()
            threads.forEach { it.join(30000) }

            assertEquals("四个并发请求都应完成", 4, outcomes.size)
            outcomes.forEach { outcome ->
                assertEquals(206L, outcome[0])
                assertEquals("每个 206 必须精确 262144 字节（声明长度=实际字节）", 262144L, outcome[1])
            }
        } finally {
            bridge.stop()
        }
    }
}
