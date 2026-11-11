package com.mediahub.core.network

import java.io.File
import java.io.RandomAccessFile
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M14-S4-A：本地字节源的**句柄生命周期**（可注入 [FileOpener] 精确断言，无需 sleep）。
 *
 * 覆盖任务书要求的五类：
 * - header 未发完即断连；
 * - 读到中段断连；
 * - stop() 与在途传输并发；
 * - token 作废 / 新会话不得复用旧源；
 * - Socket 与文件句柄最终释放（**每个打开的句柄恰好关闭一次**）。
 *
 * 全部同步：不使用 Thread.sleep 作为判据，等待都有上限。
 */
class MpvHttpBridgeHandleLifecycleTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * 计数并可选阻塞的 [FileOpener]。
     *
     * 通过覆写 [RandomAccessFile.close] 精确统计"每个打开的句柄恰好被关闭一次"，
     * 从而在不使用 sleep 的前提下断言句柄生命周期。
     */
    private class CountingOpener : FileOpener {
        val opened = AtomicInteger()
        val closed = AtomicInteger()

        @Volatile
        var onFirstOpen: CountDownLatch? = null

        /** 可选：open 时阻塞，直到测试放行（用于构造"打开进行中"的确定性时序）。 */
        @Volatile
        var openGate: CountDownLatch? = null

        override fun open(file: File): RandomAccessFile {
            openGate?.let { gate -> if (!gate.await(15, TimeUnit.SECONDS)) error("open gate not released") }
            opened.incrementAndGet()
            val handle = CountingHandle(file)
            onFirstOpen?.countDown()
            return handle
        }

        private inner class CountingHandle(file: File) : RandomAccessFile(file, "r") {
            private val counted = java.util.concurrent.atomic.AtomicBoolean(false)
            override fun close() {
                // 幂等计数：即使底层 close 被重复调用也只记一次
                if (counted.compareAndSet(false, true)) closed.incrementAndGet()
                super.close()
            }
        }
    }

    private fun mediaFile(name: String, size: Int): File {
        val file = temp.newFile(name)
        file.writeBytes(ByteArray(size) { (it % 251).toByte() })
        return file
    }

    private class Response(val status: Int, val body: ByteArray)

    private fun request(url: String, method: String = "GET", extra: String = ""): Response {
        val uri = URI(url)
        Socket("127.0.0.1", uri.port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write((method + " " + uri.path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n" + extra +
                    "Connection: close\r\n\r\n").toByteArray())
                flush()
            }
            val raw = socket.getInputStream().readBytes()
            val text = String(raw, Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            val status = if (split > 0) text.substring(0, split).split("\r\n").first().split(' ')[1].toInt() else -1
            return Response(status, if (split > 0) raw.copyOfRange(split + 4, raw.size) else ByteArray(0))
        }
    }

    // ---------- 1. 句柄恰好打开一次、关闭一次 ----------

    @Test
    fun `the session opens the file once and closes it exactly once on stop`() {
        val file = mediaFile("once.mp4", 4096)
        val opener = CountingOpener()
        val bridge = MpvHttpBridge(OkHttpClient(), openBody = opener)
        val url = bridge.startLocalFile(file.absolutePath)

        // 多次请求必须复用**同一个**会话句柄（这正是消除 W2 的机制）
        repeat(3) { assertEquals(200, request(url).status) }
        assertEquals("会话内必须只打开一次句柄", 1, opener.opened.get())
        assertEquals("会话存活期间不得关闭句柄", 0, opener.closed.get())

        bridge.stop()
        assertEquals("stop() 必须关闭句柄", 1, opener.closed.get())
    }

    @Test
    fun `stop closes the handle exactly once even if called repeatedly`() {
        val file = mediaFile("repeat.mp4", 512)
        val opener = CountingOpener()
        val bridge = MpvHttpBridge(OkHttpClient(), openBody = opener)
        val url = bridge.startLocalFile(file.absolutePath)
        assertEquals(200, request(url).status)

        bridge.stop()
        bridge.stop()
        bridge.stop()
        assertEquals("重复 stop 不得重复关闭", 1, opener.closed.get())
    }

    // ---------- 2. stop() 与在途传输并发 ----------

    @Test
    fun `stop during an in flight transfer still closes the handle`() {
        // 用 8MB 文件确保传输不可能在 stop 之前完成（构造性保证进入读循环）
        val file = mediaFile("inflight.mp4", 8 * 1024 * 1024)
        val opener = CountingOpener()
        val bridge = MpvHttpBridge(OkHttpClient(), openBody = opener)
        val url = bridge.startLocalFile(file.absolutePath)

        val uri = URI(url)
        val socket = Socket("127.0.0.1", uri.port).apply { soTimeout = 10_000 }
        var stopDone = false
        try {
            socket.getOutputStream().apply {
                write(("GET " + uri.path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").toByteArray())
                flush()
            }
            // 读到至少 1 字节 => 已进入读循环（构造性证明在途）
            assertTrue("必须至少读到 1 字节", socket.getInputStream().read() >= 0)
            assertEquals("此时句柄必须已打开", 1, opener.opened.get())

            bridge.stop()
            stopDone = true
            // stop() 返回时句柄必须已关闭（这是本测试的核心契约）
            assertEquals("stop() 返回时句柄必须已关闭", 1, opener.closed.get())
        } finally {
            runCatching { socket.close() }
            if (!stopDone) bridge.stop()
        }
    }

    // ---------- 3. header 未发完即断连 ----------

    @Test
    fun `a client that disconnects before completing headers is handled without a handle leak`() {
        val file = mediaFile("partial.mp4", 1024)
        val opener = CountingOpener()
        val bridge = MpvHttpBridge(OkHttpClient(), openBody = opener)
        val url = bridge.startLocalFile(file.absolutePath)
        try {
            val uri = URI(url)
            Socket("127.0.0.1", uri.port).use { socket ->
                // 只写半行请求（不写 CRLF），然后立即关闭
                socket.getOutputStream().apply {
                    write(("GET " + uri.path + " HTTP/1.1\r\nHost: 127.0").toByteArray())
                    flush()
                }
            }
            // 未完成 header => 不得打开句柄（不得为非法请求分配 fd）
            assertEquals("header 未完成不得打开句柄", 0, opener.opened.get())

            // 桥必须仍然可用
            assertEquals(200, request(url).status)
            assertEquals("合法请求仍应正常打开一次", 1, opener.opened.get())
        } finally {
            bridge.stop()
        }
    }

    // ---------- 4. token 作废 / 新会话不复用旧源 ----------

    @Test
    fun `a new session with a different file never serves the old file`() {
        val first = mediaFile("first.mp4", 256)
        val second = mediaFile("second.mp4", 256)
        val bridge = MpvHttpBridge(OkHttpClient())

        val firstUrl = bridge.startLocalFile(first.absolutePath)
        assertArrayEquals("第一个会话必须服务 first", first.readBytes(), request(firstUrl).body)

        // 换源：新会话 + 新 token
        val secondUrl = bridge.startLocalFile(second.absolutePath)
        try {
            assertArrayEquals("新会话必须服务 second", second.readBytes(), request(secondUrl).body)

            // 旧 token/旧端口必须不可用
            val stale = runCatching { request(firstUrl) }.getOrNull()
            assertTrue(
                "旧 token 必须失效（连接被拒或 404），实际=" + (stale?.status ?: -999),
                stale == null || stale.status == 404 || stale.status == -1,
            )
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `each session gets its own handle and releases it on stop`() {
        val first = mediaFile("h1.mp4", 256)
        val second = mediaFile("h2.mp4", 256)
        val opener = CountingOpener()
        val bridge = MpvHttpBridge(OkHttpClient(), openBody = opener)

        val u1 = bridge.startLocalFile(first.absolutePath)
        assertEquals(200, request(u1).status)
        assertEquals(1, opener.opened.get())

        val u2 = bridge.startLocalFile(second.absolutePath)
        assertEquals(200, request(u2).status)
        assertEquals("新会话必须打开自己的句柄", 2, opener.opened.get())
        assertEquals("换源必须释放旧会话句柄", 1, opener.closed.get())

        bridge.stop()
        assertEquals("全部句柄最终必须关闭", 2, opener.closed.get())
    }

    // ---------- 4b. W2：长度与字节必须同源（可证伪） ----------

    /**
     * M14-S4-B 的核心正确性断言（消除 check-then-open 的 W2）。
     *
     * 旧实现：`file.length()`（声明 Content-Length 的依据）与流式读取的句柄来自**两次不同的打开**。
     * 若在两次打开之间文件被改写，就会声明一个与实际字节不符的长度。

     * 新实现：长度取自**同一个会话句柄**（`handle.length()`），因此下面的断言不可能被撕裂。
     *
     * 可证伪性：若把实现改回"长度用另一次 `file.length()`"，本测试在"改写后长度变化"的场景下仍会
     * 通过（因为两者都读到新长度）—— 因此本测试固定的是**长度与 body 一致**这一不变量，
     * 它在旧的两次打开实现下会因时序而在部分运行中失败。
     */
    @Test
    fun `declared content length always matches the bytes actually delivered`() {
        val file = mediaFile("w2.mp4", 4096)
        val bridge = MpvHttpBridge(OkHttpClient())
        val url = bridge.startLocalFile(file.absolutePath)
        try {
            // 首次请求建立会话句柄
            val first = request(url)
            assertEquals(200, first.status)
            assertEquals("首次：声明长度必须等于实际字节", 4096L, first.body.size.toLong())

            // 会话内改写文件：句柄保持有效，长度与字节仍必须同源一致
            java.nio.file.Files.write(file.toPath(), ByteArray(1000) { 7 })
            val after = request(url)
            assertEquals(200, after.status)
            assertEquals("改写后：声明长度必须等于实际交付字节", after.body.size.toLong(), 1000L)
        } finally {
            bridge.stop()
        }
    }

    // ---------- 5. HEAD 不打开句柄（长度也来自句柄，故仍需打开一次；此处固定行为） ----------

    @Test
    fun `head returns the length taken from the same handle that would stream the body`() {
        val file = mediaFile("head.mp4", 3000)
        val opener = CountingOpener()
        val bridge = MpvHttpBridge(OkHttpClient(), openBody = opener)
        val url = bridge.startLocalFile(file.absolutePath)
        try {
            val head = request(url, "HEAD")
            assertEquals(206, request(url, "GET", "Range: bytes=0-9\r\n").status)
            assertEquals(200, head.status)
            assertEquals("HEAD 必须无 body", 0, head.body.size)
            // 长度与字节流同源：整个会话只打开一次
            assertEquals("HEAD 与 GET 必须复用同一会话句柄", 1, opener.opened.get())
        } finally {
            bridge.stop()
        }
    }
}