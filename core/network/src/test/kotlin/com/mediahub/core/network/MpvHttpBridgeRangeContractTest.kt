package com.mediahub.core.network

import java.io.File
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M14-S2：本地字节源的 **RFC 7233 Range 协议契约**（第二切片，纯协议正确性）。
 *
 * 与既有 [MpvHttpBridgeLocalFileTest] 的分工：那个类覆盖"能服务/基本 206"，
 * 本类把**逐字节正确性、边界语义、解析健壮性、多段/If-Range 的保守行为**固定下来，
 * 并补上并发单段 Range 与响应头结束空行检查。
 *
 * 严格约束：
 * - 断言**响应体逐字节等于**样本切片，而不是只看状态码/长度；
 * - 不依赖线程调度随机性：并发用 CountDownLatch 对齐，无 Thread.sleep 参与判定；
 * - 不引入任意路径读入口，全部走会话 token URL。
 */
class MpvHttpBridgeRangeContractTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun bridge() = MpvHttpBridge(OkHttpClient())

    /** 样本内容可预测：(i % 251)，便于按切片逐字节比较。 */
    private fun mediaFile(name: String, size: Int): File {
        val file = temp.newFile(name)
        file.writeBytes(ByteArray(size) { (it % 251).toByte() })
        return file
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val contentLength: Long? get() = headers["content-length"]?.toLongOrNull()
    }

    private fun request(url: String, method: String = "GET", extra: String = ""): Response {
        val uri = URI(url)
        Socket("127.0.0.1", uri.port).use { socket ->
            socket.getOutputStream().apply {
                write(
                    (method + " " + uri.path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n" + extra +
                        "Connection: close\r\n\r\n").toByteArray(),
                )
                flush()
            }
            val raw = socket.getInputStream().readBytes()
            val text = String(raw, Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            assertTrue("响应必须包含完整的头部结束空行（CRLF CRLF）", split > 0)
            val lines = text.substring(0, split).split("\r\n")
            return Response(
                lines.first().split(' ')[1].toInt(),
                lines.drop(1).mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) null
                    else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                }.toMap(),
                raw.copyOfRange(split + 4, raw.size),
            )
        }
    }

    /** 期望的字节切片（与 mediaFile 的生成规则一致）。 */
    private fun expected(start: Long, endInclusive: Long): ByteArray =
        ByteArray((endInclusive - start + 1).toInt()) { ((start + it) % 251).toByte() }

    // ---------- 逐字节正确的单段 Range ----------

    @Test
    fun `a single range is byte exact and self consistent`() {
        val file = mediaFile("clip.mp4", 4096)
        val bridge = bridge()
        try {
            for ((spec, start, end) in listOf(
                Triple("bytes=0-99", 0L, 99L),
                Triple("bytes=100-199", 100L, 199L),
                Triple("bytes=0-0", 0L, 0L),
                Triple("bytes=4095-4095", 4095L, 4095L),
                Triple("bytes=4000-4095", 4000L, 4095L),
                Triple("bytes=2000-", 2000L, 4095L),
            )) {
                val r = request(bridge.startLocalFile(file.absolutePath), extra = "Range: $spec\r\n")
                assertEquals("[$spec] 应为 206", 206, r.status)
                assertEquals(
                    "[$spec] Content-Range 必须精确",
                    "bytes $start-$end/4096",
                    r.headers["content-range"],
                )
                val slice = expected(start, end)
                assertEquals("[$spec] Content-Length 必须等于切片长度", slice.size.toLong(), r.contentLength)
                assertArrayEquals("[$spec] 响应体必须逐字节等于样本切片", slice, r.body)
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a suffix range is byte exact`() {
        val file = mediaFile("clip.mp4", 1000)
        val bridge = bridge()
        try {
            // 后缀 100 字节 => [900, 999]
            val r = request(bridge.startLocalFile(file.absolutePath), extra = "Range: bytes=-100\r\n")
            assertEquals(206, r.status)
            assertEquals("bytes 900-999/1000", r.headers["content-range"])
            assertArrayEquals(expected(900, 999), r.body)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a suffix larger than the file serves the whole file`() {
        val file = mediaFile("clip.mp4", 500)
        val bridge = bridge()
        try {
            val r = request(bridge.startLocalFile(file.absolutePath), extra = "Range: bytes=-9999\r\n")
            assertEquals(206, r.status)
            assertEquals("bytes 0-499/500", r.headers["content-range"])
            assertArrayEquals(expected(0, 499), r.body)
        } finally {
            bridge.stop()
        }
    }

    // ---------- HEAD ----------

    @Test
    fun `head returns the same headers as get but never a body`() {
        val file = mediaFile("clip.mp4", 2048)
        val bridge = bridge()
        try {
            val url = bridge.startLocalFile(file.absolutePath)
            val get = request(url, "GET", "Range: bytes=10-19\r\n")
            val head = request(url, "HEAD", "Range: bytes=10-19\r\n")

            assertEquals(206, head.status)
            assertEquals("HEAD 必须与 GET 声明同样的 Content-Length", get.contentLength, head.contentLength)
            assertEquals(get.headers["content-range"], head.headers["content-range"])
            assertEquals("HEAD 不得返回任何 body", 0, head.body.size)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `head on a zero byte file is consistent and bodyless`() {
        val file = mediaFile("empty.mp4", 0)
        val bridge = bridge()
        try {
            val url = bridge.startLocalFile(file.absolutePath)
            val get = request(url, "GET")
            val head = request(url, "HEAD")
            assertEquals(200, get.status)
            assertEquals("0", get.headers["content-length"])
            assertEquals(0, get.body.size)
            assertEquals(200, head.status)
            assertEquals("0", head.headers["content-length"])
            assertEquals(0, head.body.size)
        } finally {
            bridge.stop()
        }
    }

    // ---------- 416 语义 ----------

    @Test
    fun `an unsatisfiable range returns 416 with the exact unsatisfied content range`() {
        val file = mediaFile("clip.mp4", 1000)
        val bridge = bridge()
        try {
            // RFC 7233 §3.1：**语法合法但起点超出当前表示长度** → 不可满足 → 416。
            // 注意「显式 last < first」（如 bytes=1000-999）属**无效 spec**，按 RFC 必须忽略
            // 并返回完整 200 —— 该情形由下方 invalid-spec 测试覆盖（M14-S1 已固定该契约）。
            // 仅「起点 >= 当前长度」属于不可满足；end 超出 EOF 按 RFC 7233 §3.1 是**夹取**而非 416
            // （该正向行为由 an enormous in-bounds end is clamped to the last byte 固定）。
            for (spec in listOf("bytes=1000-1005", "bytes=5000-", "bytes=1000-")) {
                val r = request(bridge.startLocalFile(file.absolutePath), extra = "Range: $spec\r\n")
                assertEquals("[$spec] 越界必须 416", 416, r.status)
                assertEquals(
                    "[$spec] 416 必须给出 bytes */length",
                    "bytes */1000",
                    r.headers["content-range"],
                )
                assertEquals("[$spec] 416 的 Content-Length 必须为 0", 0L, r.contentLength)
                assertEquals("[$spec] 416 不得有 body", 0, r.body.size)
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a range on a zero byte file is unsatisfiable`() {
        val file = mediaFile("empty.mp4", 0)
        val bridge = bridge()
        try {
            val r = request(bridge.startLocalFile(file.absolutePath), extra = "Range: bytes=0-0\r\n")
            assertEquals(416, r.status)
            assertEquals("bytes */0", r.headers["content-range"])
            assertEquals(0L, r.contentLength)
        } finally {
            bridge.stop()
        }
    }

    // ---------- 无效语法 vs 有效但不可满足 ----------

    @Test
    fun `an invalid range spec is ignored and serves the full 200`() {
        val file = mediaFile("clip.mp4", 1000)
        val bridge = bridge()
        try {
            // 语法无效 => 按 RFC 忽略 Range，返回完整 200（而不是 416）
            for (spec in listOf(
                "bytes=abc-def",
                "bytes=",
                "bytes=-",
                "items=0-10",
                "bytes=0-10-20",
                // 显式 last < first：RFC 7233 §3.1 归为无效 spec（而非不可满足）
                "bytes=1000-999",
                "bytes=10-9",
                "bytes=0--1",
            )) {
                val r = request(bridge.startLocalFile(file.absolutePath), extra = "Range: $spec\r\n")
                assertEquals("[$spec] 无效语法必须忽略 Range 返回 200", 200, r.status)
                assertNull("[$spec] 不得声明 Content-Range", r.headers["content-range"])
                assertArrayEquals("[$spec] 必须返回完整内容", expected(0, 999), r.body)
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a multi range request is conservatively ignored and serves the full 200`() {
        val file = mediaFile("clip.mp4", 1000)
        val bridge = bridge()
        try {
            // 本桥不做 multipart/byteranges，多段必须保守地忽略 Range（不得切片错位）
            for (spec in listOf("bytes=0-9,20-29", "bytes=0-9, 20-29", "bytes=-10,-5")) {
                val r = request(bridge.startLocalFile(file.absolutePath), extra = "Range: $spec\r\n")
                assertEquals("[$spec] 多段必须保守返回完整 200", 200, r.status)
                assertNull("[$spec] 不得声明 Content-Range", r.headers["content-range"])
                assertArrayEquals("[$spec] 必须返回完整内容", expected(0, 999), r.body)
            }
        } finally {
            bridge.stop()
        }
    }

    // ---------- 解析健壮性：空白 / leading zeros / 超大数值 ----------

    @Test
    fun `whitespace and leading zeros in a range are parsed by their value`() {
        val file = mediaFile("clip.mp4", 1000)
        val bridge = bridge()
        try {
            for ((spec, start, end) in listOf(
                Triple("bytes= 0-9", 0L, 9L),
                Triple("bytes=0 - 9", 0L, 9L),
                Triple("bytes=000000010-000000019", 10L, 19L),
                Triple("bytes=000000000-0", 0L, 0L),
            )) {
                val r = request(bridge.startLocalFile(file.absolutePath), extra = "Range: $spec\r\n")
                assertEquals("[$spec] 空白与 leading zeros 必须按其数值解析", 206, r.status)
                assertEquals("[$spec]", "bytes $start-$end/1000", r.headers["content-range"])
                assertArrayEquals("[$spec]", expected(start, end), r.body)
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `an out of Long range value never crashes and never yields a negative length`() {
        val file = mediaFile("clip.mp4", 1000)
        val bridge = bridge()
        try {
            // 数值超出 Long => NumberFormatException 路径，必须被当作无效语法保守处理
            for (spec in listOf(
                "bytes=99999999999999999999-100",
                "bytes=0-99999999999999999999",
                "bytes=0-${Long.MAX_VALUE}9",
                "bytes=-99999999999999999999",
            )) {
                val r = request(bridge.startLocalFile(file.absolutePath), extra = "Range: $spec\r\n")
                assertTrue("[$spec] 必须给出有效 HTTP 状态，实际=${r.status}", r.status == 200 || r.status == 206 || r.status == 416)
                val len = r.contentLength
                assertNotNull("[$spec] 必须声明 Content-Length", len)
                assertTrue("[$spec] Content-Length 不得为负，实际=$len", len!! >= 0L)
                assertEquals("[$spec] 声明的长度必须与实际 body 一致", len, r.body.size.toLong())
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `an enormous in-bounds end is clamped to the last byte`() {
        val file = mediaFile("clip.mp4", 1000)
        val bridge = bridge()
        try {
            val r = request(
                bridge.startLocalFile(file.absolutePath),
                extra = "Range: bytes=990-${Long.MAX_VALUE}\r\n",
            )
            assertEquals(206, r.status)
            assertEquals("超大 end 必须被夹到文件末尾", "bytes 990-999/1000", r.headers["content-range"])
            assertArrayEquals(expected(990, 999), r.body)
        } finally {
            bridge.stop()
        }
    }

    // ---------- If-Range ----------

    @Test
    fun `if range forces a conservative full 200 because the bridge issues no validator`() {
        val file = mediaFile("clip.mp4", 1000)
        val bridge = bridge()
        try {
            val url = bridge.startLocalFile(file.absolutePath)
            // 本桥不签发 ETag / Last-Modified，任何校验器都不匹配 => 保守返回完整 200
            for (validator in listOf(
                "\"etag-never-issued\"",
                "Wed, 21 Oct 2015 07:28:00 GMT",
                "not-a-validator",
            )) {
                val r = request(url, "GET", "If-Range: $validator\r\nRange: bytes=0-9\r\n")
                assertEquals("[$validator] 无有效校验器时必须返回完整 200", 200, r.status)
                assertNull("[$validator] 不得声明 Content-Range", r.headers["content-range"])
                assertArrayEquals("[$validator] 必须返回完整内容", expected(0, 999), r.body)
            }

            // 无 If-Range 时同样的 Range 必须正常切片（证明上面的 200 来自 If-Range 而非 Range 失效）
            val ranged = request(url, "GET", "Range: bytes=0-9\r\n")
            assertEquals(206, ranged.status)
            assertArrayEquals(expected(0, 9), ranged.body)
        } finally {
            bridge.stop()
        }
    }

    // ---------- 并发单段 Range ----------

    @Test
    fun `concurrent single range clients each get their own correct slice`() {
        val file = mediaFile("clip.mp4", 8192)
        val bridge = bridge()
        val url = bridge.startLocalFile(file.absolutePath)
        val clients = 6
        val start = CountDownLatch(1)
        val done = CountDownLatch(clients)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val served = AtomicInteger()

        try {
            val slices = (0 until clients).map { i ->
                Triple(i * 1000L, i * 1000L + 99L, "bytes=${i * 1000}-${i * 1000 + 99}")
            }
            for ((s, e, spec) in slices) {
                Thread {
                    try {
                        start.await()
                        val r = request(url, "GET", "Range: $spec\r\n")
                        if (r.status != 206) failures += "[$spec] status=${r.status}"
                        else if (r.headers["content-range"] != "bytes $s-$e/8192") {
                            failures += "[$spec] content-range=${r.headers["content-range"]}"
                        } else if (!r.body.contentEquals(expected(s, e))) {
                            failures += "[$spec] body mismatch (len=${r.body.size})"
                        } else served.incrementAndGet()
                    } catch (t: Throwable) {
                        failures += "[$spec] threw ${t.javaClass.simpleName}: ${t.message}"
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue("并发客户端必须全部在时限内完成", done.await(30, TimeUnit.SECONDS))
            assertEquals("所有并发切片都必须正确：${failures.joinToString()}", clients, served.get())
            assertTrue(failures.isEmpty())

            // 并发结束后桥仍可用，且能服务完整文件（资源已释放、会话未被破坏）
            val after = request(url)
            assertEquals(200, after.status)
            assertArrayEquals(expected(0, 8191), after.body)
        } finally {
            bridge.stop()
        }
    }
}
