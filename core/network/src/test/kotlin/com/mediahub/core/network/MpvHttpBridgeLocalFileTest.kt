package com.mediahub.core.network

import java.io.File
import java.net.Socket
import java.net.URI
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M9-P0：回环桥的**受限本地字节源**协议测试。
 *
 * 背景：本地视频经 mpv 播放时，桥只支持 HTTP(S) 上游 → mpv 拿到 "End of file"、
 * 无 time-pos（真机 P1）。本测试覆盖本地分支的 HTTP 语义与安全边界。
 */
class MpvHttpBridgeLocalFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun bridge() = MpvHttpBridge(OkHttpClient())

    private fun mediaFile(name: String, size: Int): File {
        val file = temp.newFile(name)
        file.writeBytes(ByteArray(size) { (it % 251).toByte() })
        return file
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

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
            val head = text.substring(0, split)
            val lines = head.split("\r\n")
            val status = lines.first().split(' ')[1].toInt()
            val headers = lines.drop(1)
                .mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                }
                .toMap()
            return Response(status, headers, raw.copyOfRange(split + 4, raw.size))
        }
    }

    @Test
    fun `serves the whole local file with 200 and advertises range support`() {
        val file = mediaFile("clip.mp4", 4096)
        val bridge = bridge()
        val url = bridge.startLocalFile(file.absolutePath)
        try {
            val response = request(url)
            assertEquals(200, response.status)
            assertEquals("4096", response.headers["content-length"])
            assertEquals("bytes", response.headers["accept-ranges"])
            assertEquals("video/mp4", response.headers["content-type"])
            assertTrue(response.body.contentEquals(file.readBytes()))
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `serves a byte range with 206 and content-range`() {
        val file = mediaFile("clip.mp4", 4096)
        val bridge = bridge()
        try {
            val response = request(bridge.startLocalFile(file.absolutePath), extra = "Range: bytes=100-199\r\n")
            assertEquals(206, response.status)
            assertEquals("100", response.headers["content-length"])
            assertEquals("bytes 100-199/4096", response.headers["content-range"])
            assertEquals(100, response.body.size)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `serves an open-ended range to the end of file`() {
        val file = mediaFile("clip.mp4", 1000)
        val bridge = bridge()
        try {
            val response = request(bridge.startLocalFile(file.absolutePath), extra = "Range: bytes=900-\r\n")
            assertEquals(206, response.status)
            assertEquals("100", response.headers["content-length"])
            assertEquals("bytes 900-999/1000", response.headers["content-range"])
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `serves a suffix range`() {
        val file = mediaFile("clip.mp4", 1000)
        val bridge = bridge()
        try {
            val response = request(bridge.startLocalFile(file.absolutePath), extra = "Range: bytes=-50\r\n")
            assertEquals(206, response.status)
            assertEquals("bytes 950-999/1000", response.headers["content-range"])
            assertEquals(50, response.body.size)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `rejects an unsatisfiable range with 416`() {
        val file = mediaFile("clip.mp4", 500)
        val bridge = bridge()
        try {
            val response = request(bridge.startLocalFile(file.absolutePath), extra = "Range: bytes=5000-6000\r\n")
            assertEquals(416, response.status)
            assertEquals("bytes */500", response.headers["content-range"])
            assertEquals(0, response.body.size)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `malformed range falls back to the full entity`() {
        val file = mediaFile("clip.mp4", 300)
        val bridge = bridge()
        try {
            val response = request(bridge.startLocalFile(file.absolutePath), extra = "Range: bytes=abc-def\r\n")
            assertEquals(200, response.status)
            assertEquals("300", response.headers["content-length"])
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `HEAD returns headers without a body`() {
        val file = mediaFile("clip.mp4", 2048)
        val bridge = bridge()
        try {
            val response = request(bridge.startLocalFile(file.absolutePath), method = "HEAD")
            assertEquals(200, response.status)
            assertEquals("2048", response.headers["content-length"])
            assertEquals(0, response.body.size)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a wrong token returns 404 instead of serving bytes`() {
        val file = mediaFile("clip.mp4", 128)
        val bridge = bridge()
        try {
            val url = bridge.startLocalFile(file.absolutePath)
            val forged = url.substringBeforeLast("/media/") + "/media/not-the-token"
            assertEquals(404, request(forged).status)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `an unsupported method returns 405`() {
        val file = mediaFile("clip.mp4", 128)
        val bridge = bridge()
        try {
            assertEquals(405, request(bridge.startLocalFile(file.absolutePath), method = "POST").status)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a missing file returns an explicit 404 rather than a silent close`() {
        val bridge = bridge()
        try {
            val missing = File(temp.root, "gone.mp4")
            val response = request(bridge.startLocalFile(missing.absolutePath))
            assertEquals(404, response.status)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a directory is not served as a file`() {
        val bridge = bridge()
        try {
            assertEquals(404, request(bridge.startLocalFile(temp.root.absolutePath)).status)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `serves a zero byte file and answers HEAD without a body`() {
        val file = mediaFile("empty.mp4", 0)
        val bridge = bridge()
        try {
            val url = bridge.startLocalFile(file.absolutePath)
            val get = request(url)
            assertEquals(200, get.status)
            assertEquals("0", get.headers["content-length"])
            assertEquals(0, get.body.size)
            val head = request(url, method = "HEAD")
            assertEquals(200, head.status)
            assertEquals("0", head.headers["content-length"])
            assertEquals(0, head.body.size)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `serves a single byte range 0-0 on a one byte file`() {
        val file = mediaFile("one.mp4", 1)
        val bridge = bridge()
        try {
            val response = request(bridge.startLocalFile(file.absolutePath), extra = "Range: bytes=0-0\r\n")
            assertEquals(206, response.status)
            assertEquals("bytes 0-0/1", response.headers["content-range"])
            assertEquals("1", response.headers["content-length"])
            assertEquals(1, response.body.size)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `any range on a zero byte file is unsatisfiable`() {
        val file = mediaFile("empty.mp4", 0)
        val bridge = bridge()
        try {
            val response = request(bridge.startLocalFile(file.absolutePath), extra = "Range: bytes=0-\r\n")
            assertEquals(416, response.status)
            assertEquals("bytes */0", response.headers["content-range"])
        } finally {
            bridge.stop()
        }
    }

    /**
     * RFC 7233 §3.1：last-byte-pos < first-byte-pos 属于**无效** byte-range-spec，
     * 服务器 MUST 忽略整个 Range 字段并按完整实体返回 200。首版实现误判为 416 —— 先红后绿。
     */
    @Test
    fun `an invalid range spec with last before first is ignored`() {
        val file = mediaFile("clip.mp4", 512)
        val bridge = bridge()
        try {
            val response = request(bridge.startLocalFile(file.absolutePath), extra = "Range: bytes=400-100\r\n")
            assertEquals(200, response.status)
            assertEquals("512", response.headers["content-length"])
            assertEquals(512, response.body.size)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `restarting the bridge invalidates the previous session token`() {
        val first = mediaFile("a.mp4", 64)
        val second = mediaFile("b.mp4", 128)
        val bridge = bridge()
        try {
            val firstUrl = bridge.startLocalFile(first.absolutePath)
            val secondUrl = bridge.startLocalFile(second.absolutePath)
            assertNotEquals(firstUrl, secondUrl)
            assertEquals(128, request(secondUrl).body.size)
            // 旧 token 属于已停止的会话：连接要么被拒，要么返回 404，绝不返回旧文件字节
            val stale = runCatching { request(firstUrl).status }.getOrDefault(404)
            assertTrue("旧 token 不得继续提供字节", stale == 404 || stale == 400)
        } finally {
            bridge.stop()
        }
    }
}
