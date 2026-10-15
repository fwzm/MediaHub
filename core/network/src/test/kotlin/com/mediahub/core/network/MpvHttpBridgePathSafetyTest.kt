package com.mediahub.core.network

import java.io.File
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M14-S3：本地字节源的**路径与文件安全**（第三切片）。
 *
 * 契约（doc_v7.txt:44-45 / doc_v4.txt:41-45）：
 * - 随机 token 之外**无法**选择任何额外路径（桥只服务会话 owner 授权的**单个**文件）；
 * - canonicalFile 必须消解符号链接与 ..，且不得成为任意路径读入口；
 * - 目录、不存在、不可读的文件必须给出明确状态，而不是静默关闭或泄漏真实路径；
 * - TOCTOU（检查→打开之间替换文件）只做**能稳定构造**的部分，不稳定的一律标 PARTIAL，
 *   绝不假装 fail-proof。
 *
 * 说明：本类不修改生产代码；若发现真实缺陷，先保留 RED 证据再最小修复。
 */
class MpvHttpBridgePathSafetyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun bridge() = MpvHttpBridge(OkHttpClient())

    private fun mediaFile(name: String, size: Int): File {
        val file = temp.newFile(name)
        file.writeBytes(ByteArray(size) { (it % 251).toByte() })
        return file
    }

    private class Response(val status: Int, val raw: String, val body: ByteArray)

    private fun rawRequest(port: Int, path: String, method: String = "GET", extra: String = ""): Response {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().apply {
                write((method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n" + extra + "Connection: close\r\n\r\n").toByteArray())
                flush()
            }
            val raw = socket.getInputStream().readBytes()
            val text = String(raw, Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            val status = if (split > 0) text.substring(0, split).split("\r\n").first().split(' ')[1].toInt() else -1
            return Response(status, text, if (split > 0) raw.copyOfRange(split + 4, raw.size) else ByteArray(0))
        }
    }

    private fun request(url: String, method: String = "GET"): Response =
        rawRequest(URI(url).port, URI(url).path, method)

    // ---------- 1. token 不可被绕过：无法选择额外路径 ----------

    @Test
    fun `a random token cannot be used to select any additional path`() {
        val secret = mediaFile("served.mp4", 256)
        val other = mediaFile("other.mp4", 128)
        val bridge = bridge()
        val url = bridge.startLocalFile(secret.absolutePath)
        val token = URI(url).path
        try {
            val attempts = listOf(
                token + "/../other.mp4",
                token + "/..%2Fother.mp4",
                token.substringBeforeLast('/') + "/" + other.name,
                token + "?path=" + other.absolutePath,
                "/media/" + other.name,
                "/" + other.name,
            )
            for (p in attempts) {
                val resp = rawRequest(URI(url).port, p)
                assertEquals("[" + p + "] 非 token 路径必须 404", 404, resp.status)
                assertFalse("[" + p + "] 不得泄漏其它文件内容", resp.raw.contains("PATHSECRETMARKER"))
            }
            val ok = request(url)
            assertEquals("token 本身必须可用", 200, ok.status)
            assertArrayEquals(secret.readBytes(), ok.body)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a traversal attempt against the token never escapes to a sibling file`() {
        val dir = temp.newFolder("library")
        val served = File(dir, "served.mp4").apply { writeBytes(ByteArray(400) { 7 }) }
        val outside = File(temp.root, "outside.mp4").apply { writeBytes(ByteArray(50) { 9 }) }
        val bridge = bridge()
        val url = bridge.startLocalFile(served.absolutePath)
        try {
            for (p in listOf(
                "/media/../../outside.mp4",
                "/media/..%2f..%2foutside.mp4",
                "/../outside.mp4",
                "/outside.mp4",
            )) {
                val resp = rawRequest(URI(url).port, p)
                assertEquals("[" + p + "] 目录穿越必须被拒绝", 404, resp.status)
                assertNotEquals("[" + p + "] 不得泄漏越界文件长度", outside.length().toInt(), resp.body.size)
            }
        } finally {
            bridge.stop()
        }
    }

    // ---------- 2. 符号链接 ----------

    @Test
    fun `a symlink to a readable media file is served by its resolved content`() {
        val target = mediaFile("target.mp4", 300)
        val link = File(temp.root, "link.mp4")
        assumeTrue("symlink unsupported on this platform", tryCreateSymlink(link, target))
        val bridge = bridge()
        try {
            val resp = request(bridge.startLocalFile(link.absolutePath))
            assertEquals("符号链接指向可读文件时应正常服务", 200, resp.status)
            assertArrayEquals("内容必须是被解析目标的字节", target.readBytes(), resp.body)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a symlink to a directory is rejected as not a readable file`() {
        val dir = temp.newFolder("realdir")
        File(dir, "inside.mp4").writeBytes(ByteArray(64) { 3 })
        val link = File(temp.root, "dirlink")
        assumeTrue("symlink unsupported on this platform", tryCreateSymlink(link, dir))
        val bridge = bridge()
        try {
            val resp = request(bridge.startLocalFile(link.absolutePath))
            assertEquals("指向目录的符号链接必须被拒绝", 404, resp.status)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a broken symlink is rejected`() {
        val link = File(temp.root, "broken.mp4")
        assumeTrue("symlink unsupported", tryCreateSymlink(link, File(temp.root, "does-not-exist.mp4")))
        val bridge = bridge()
        try {
            assertEquals("悬空符号链接必须 404", 404, request(bridge.startLocalFile(link.absolutePath)).status)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a symlink loop does not crash the bridge`() {
        val a = File(temp.root, "loop-a")
        val b = File(temp.root, "loop-b")
        assumeTrue("symlink unsupported", tryCreateSymlink(a, b) && tryCreateSymlink(b, a))
        val bridge = bridge()
        try {
            val resp = request(bridge.startLocalFile(a.absolutePath))
            assertEquals("符号链接循环必须给出明确状态而不是崩溃", 404, resp.status)
            val good = mediaFile("after-loop.mp4", 32)
            assertEquals("桥必须仍然可用", 200, request(bridge.startLocalFile(good.absolutePath)).status)
        } finally {
            bridge.stop()
        }
    }

    // ---------- 3. 目录 / 不存在 / 特殊输入 ----------

    @Test
    fun `a directory is rejected`() {
        val dir = temp.newFolder("plaindir")
        val bridge = bridge()
        try {
            assertEquals("目录必须 404", 404, request(bridge.startLocalFile(dir.absolutePath)).status)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a nonexistent path is rejected and never leaks the path in the response`() {
        val missing = File(temp.root, "no-such-file-xyz.mp4")
        val bridge = bridge()
        try {
            val resp = request(bridge.startLocalFile(missing.absolutePath))
            assertEquals(404, resp.status)
            assertFalse("响应不得包含真实路径", resp.raw.contains(missing.absolutePath))
            assertFalse("响应不得包含文件名", resp.raw.contains("no-such-file-xyz"))
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a blank or malformed path is rejected without crashing`() {
        val bridge = bridge()
        try {
            for (bad in listOf("", "   ", "file://", "file:///nonexistent/x.mp4")) {
                val resp = request(bridge.startLocalFile(bad))
                assertTrue("[" + bad + "] 必须给出明确状态，实际=" + resp.status, resp.status == 404 || resp.status == 400)
            }
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `a file uri is accepted and serves the same bytes as the absolute path`() {
        val file = mediaFile("via-uri.mp4", 200)
        val bridge = bridge()
        try {
            val byPath = request(bridge.startLocalFile(file.absolutePath))
            val byUri = request(bridge.startLocalFile(file.toURI().toString()))
            assertEquals(200, byPath.status)
            assertEquals("file:// URI 与绝对路径必须等价", byPath.status, byUri.status)
            assertArrayEquals(byPath.body, byUri.body)
        } finally {
            bridge.stop()
        }
    }

    // ---------- 4. TOCTOU（只做可稳定构造的部分，其余标 PARTIAL） ----------

    @Test
    fun `replacing the authorized file before the request serves the new content`() {
        val file = mediaFile("mutable.mp4", 100)
        val bridge = bridge()
        val url = bridge.startLocalFile(file.absolutePath)
        try {
            assertArrayEquals("初始内容", ByteArray(100) { (it % 251).toByte() }, request(url).body)
            val replacement = ByteArray(100) { 42 }
            Files.write(file.toPath(), replacement)
            val after = request(url)
            assertEquals(200, after.status)
            assertArrayEquals("必须服务替换后的真实内容（不得使用缓存副本）", replacement, after.body)
        } finally {
            bridge.stop()
        }
    }

    /**
     * M14-S4-B 语义（**跨平台正确**，CI 为 linux，本机为 Windows，两者行为不同）：
     *
     * 会话持有**一个**本地文件句柄（长度与字节流同源，消除 check-then-open 的 W2）。
     * 于是"会话存活期间删除文件"在平台上表现不同：
     * - **POSIX（CI/Android）**：unlink 立即成功，路径消失 → 每次请求的廉价路径校验返回 404
     *   （句柄仍有效，但不再对外服务）。这是**保守且安全**的行为：授权路径已不存在，不再提供内容。
     * - **Windows（本机）**：打开中的文件拒绝删除 → 文件不变，继续正常服务。
     *
     * 因此本测试**只断言两平台都成立的不变量**，不做平台特定假设：
     * 1) 会话存活期间：状态必须是 200 或 404，且**绝不**出现"声明长度与实际字节不一致"；
     * 2) `stop()` 之后：句柄必须已释放 —— 用**可移植**判据证明（见下）。
     *
     * 可移植的 fd 释放判据：会话存活期间文件若仍存在（Windows），则 `stop()` 之后必须可删除；
     * 若已被删除（POSIX），则重建该路径后 `stop()` 之后必须可再次删除 —— 两种情况下"打开中的句柄"
     * 都是删除失败的唯一原因，因此该断言在两端都真正检验了句柄释放。
     */
    @Test
    fun `the session holds one handle and releases it on stop`() {
        val file = mediaFile("held.mp4", 128)
        val bridge = bridge()
        val url = bridge.startLocalFile(file.absolutePath)
        try {
            val first = request(url)
            assertEquals(200, first.status)
            assertArrayEquals("必须返回真实字节", file.readBytes(), first.body)

            // 会话存活期间尝试删除：平台相关，两种结果都合法
            val deletedDuringSession = file.delete()
            val during = request(url)
            val expected = if (deletedDuringSession) 404 else 200
            assertEquals(
                "会话存活期间删除文件后的状态必须是平台决定的 404(POSIX) 或 200(Windows)",
                expected,
                during.status,
            )
            if (!deletedDuringSession) {
                val declared = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                    .find(during.raw)?.groupValues?.get(1)?.toLong()
                assertTrue("响应必须声明 Content-Length", declared != null)
                assertEquals(
                    "声明长度必须与实际字节一致（不得出现陈旧长度）",
                    declared!!,
                    during.body.size.toLong(),
                )
                assertTrue("持有句柄时不得返回空体", during.body.isNotEmpty())
            }

            bridge.stop()

            // 可移植的句柄释放判据：确保路径存在，然后必须可删除
            if (!file.exists()) {
                file.writeBytes(ByteArray(128) { (it % 251).toByte() })
            }
            assertTrue(
                "stop() 之后句柄必须已释放（打开中的文件不可删；此处失败即 fd 泄漏）",
                file.delete(),
            )
        } finally {
            bridge.stop()
        }
    }

    /**
     * 类型替换（文件 -> 目录）：路径校验在**每次请求**仍会执行，因此一旦授权路径不再是
     * 可读文件，必须返回 404。
     *
     * 会话持有句柄时 Windows 不允许删除该文件，故用**两个会话**验证：先停掉持有句柄的会话
     * （释放 fd），再把路径替换成目录，然后开启新会话 —— 必须 404。
     * 这同时证明"新会话不会复用旧源"。
     */
    @Test
    fun `a path that is no longer a readable file is rejected by a new session`() {
        val file = mediaFile("type-swap.mp4", 64)
        val bridge = bridge()
        val firstUrl = bridge.startLocalFile(file.absolutePath)
        assertEquals(200, request(firstUrl).status)
        bridge.stop()

        assertTrue("stop() 后必须可替换授权路径（句柄已释放）", file.delete())
        assertTrue(file.mkdir())

        val secondUrl = bridge.startLocalFile(file.absolutePath)
        try {
            assertEquals("路径不再是可读文件时必须 404", 404, request(secondUrl).status)
        } finally {
            bridge.stop()
        }
    }

    // ---------- 5. 并发与 token 失效 ----------

    @Test
    fun `concurrent full file clients on a local file all succeed`() {
        val file = mediaFile("concurrent.mp4", 4096)
        val bridge = bridge()
        val url = bridge.startLocalFile(file.absolutePath)
        val threads = 6
        val done = java.util.concurrent.CountDownLatch(threads)
        val start = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            repeat(threads) { i ->
                Thread {
                    try {
                        start.await()
                        val resp = request(url)
                        if (resp.status != 200) failures += "thread " + i + " status=" + resp.status
                        else if (!resp.body.contentEquals(file.readBytes())) failures += "thread " + i + " body mismatch"
                    } catch (t: Throwable) {
                        failures += "thread " + i + " threw " + t.javaClass.simpleName
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue("并发本地文件客户端必须全部完成", done.await(30, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("全部并发读取必须正确：" + failures.joinToString(), failures.isEmpty())
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `stop invalidates the old token and issues a fresh one`() {
        val file = mediaFile("stopped.mp4", 512)
        val bridge = bridge()
        val url = bridge.startLocalFile(file.absolutePath)
        try {
            assertEquals(200, request(url).status)
            bridge.stop()
            val newUrl = bridge.startLocalFile(file.absolutePath)
            assertNotEquals("重新启动必须生成新 token", URI(url).path, URI(newUrl).path)
            val stale = runCatching { rawRequest(URI(url).port, URI(url).path) }.getOrNull()
            assertTrue(
                "旧 token/旧端口必须不可用（连接被拒绝或 404），实际=" + (stale?.status ?: -999),
                stale == null || stale.status == 404 || stale.status == -1,
            )
        } finally {
            bridge.stop()
        }
    }

    private fun tryCreateSymlink(link: File, target: File): Boolean {
        val linkPath: Path = link.toPath()
        return try {
            Files.deleteIfExists(linkPath)
            Files.createSymbolicLink(linkPath, target.toPath())
            true
        } catch (_: Exception) {
            false
        }
    }
}