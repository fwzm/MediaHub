package com.mediahub.player.mpv

import android.net.Uri
import com.mediahub.core.logging.StdoutLogger
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A4 轮 scopeKey 缓存隔离回归（**全新重建**，非从 B 接回——同名旧复现源码未归档，
 * SOURCE_NOT_ARCHIVED；语义对应 B 轮"同名碰撞"审查线索）。
 *
 * 契约：缓存文件名 = sha256(scopeKey) 前 16 hex + "_" + 原始文件名。
 * scopeKey 是媒体版本指纹（调用方传入，非空）：
 * - 不同 scopeKey（不同目录/不同服务器/不同媒体版本）的同名字幕**各自独立内容**，
 *   互不复用——否则 A 服务器 movie.zh.srt 的内容会挂到 B 服务器同名字幕的播放上；
 * - 同 scopeKey 重复请求仍复用缓存文件（缓存命中语义保留）；
 * - scopeKey 空串违反契约，必须显式失败（require），不得静默落到共享键。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class A4SubtitleCacheScopeIsolationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer

    @Before
    fun setUp() {
        serverA = MockWebServer().apply { start() }
        serverB = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        serverA.shutdown()
        serverB.shutdown()
    }

    /** 注入式 SAF 拷贝：按调用序返回预排内容，记录调用次数。 */
    private class ScriptedCopy(vararg payloads: ByteArray) : SubtitleCache.ContentCopy {
        private val script = payloads.toList()
        var calls = 0
            private set
        override fun copy(uri: Uri, target: File): Boolean {
            val payload = script.getOrNull(calls) ?: error("unexpected copy call #${calls}")
            calls++
            target.outputStream().use { it.write(payload) }
            return true
        }
    }

    private fun cache(copy: SubtitleCache.ContentCopy) = SubtitleCache(
        cacheDir = tmp.newFolder(),
        client = OkHttpClient(),
        contentResolver = copy,
        logger = StdoutLogger(),
    )

    // ---- content://：不同 scopeKey 的同名字幕各自独立内容 ----

    @Test
    fun `different scope keys same subtitle name keep independent contents`() = runBlocking {
        val contentA = "scope-a 字幕内容".toByteArray()
        val contentB = "scope-b 完全不同的字幕内容（更长）".toByteArray()
        val copy = ScriptedCopy(contentA, contentB)
        val subject = cache(copy)
        val uri = "content://imported/movie.zh.srt"
        val media = "https://media.example/stream.mkv"

        val pathA = subject.localPathFor(uri, media, "serverA/dirs/x", emptyMap())
        val pathB = subject.localPathFor(uri, media, "serverB/dirs/y", emptyMap())

        assertNotNull(pathA)
        assertNotNull(pathB)
        assertNotEquals("不同 scopeKey 必须落在不同缓存文件", pathA, pathB)
        assertEquals(contentA.size.toLong(), File(pathA!!).length())
        assertEquals(contentB.size.toLong(), File(pathB!!).length())
        assertEquals("两次都是真实拷贝（各自落地）", 2, copy.calls)
    }

    @Test
    fun `same scope key repeat request reuses cached file without copy`() = runBlocking {
        val copy = ScriptedCopy("only-once".toByteArray())
        val subject = cache(copy)
        val uri = "content://imported/movie.zh.srt"

        val first = subject.localPathFor(uri, "https://m/x.mkv", "same-scope", emptyMap())
        val second = subject.localPathFor(uri, "https://m/x.mkv", "same-scope", emptyMap())

        assertEquals("同 scopeKey 重复请求命中同一缓存文件", first, second)
        assertEquals("缓存命中：不发生第二次拷贝", 1, copy.calls)
    }

    // ---- http：不同服务器（不同 scopeKey）的同名字幕独立；同 scope 复用 ----

    @Test
    fun `different servers same subtitle filename isolate by scope key`() = runBlocking {
        serverA.enqueue(MockResponse().setBody("from-server-a"))
        serverB.enqueue(MockResponse().setBody("body-from-server-b-longer"))
        val subject = SubtitleCache(
            cacheDir = tmp.newFolder(),
            client = OkHttpClient(),
            contentResolver = { _, _ -> false },
            logger = StdoutLogger(),
        )

        val pathA = subject.localPathFor(
            uri = serverA.url("/dav/movie.vtt").toString(),
            mediaUrl = serverA.url("/dav/movie.mkv").toString(),
            scopeKey = "serverA",
            sessionHeaders = emptyMap(),
        )
        val pathB = subject.localPathFor(
            uri = serverB.url("/dav/movie.vtt").toString(),
            mediaUrl = serverB.url("/dav/movie.mkv").toString(),
            scopeKey = "serverB",
            sessionHeaders = emptyMap(),
        )

        assertNotEquals(pathA, pathB)
        assertEquals("from-server-a", File(pathA!!).readText())
        assertEquals("body-from-server-b-longer", File(pathB!!).readText())

        // 同 scope 重复请求：缓存命中，服务端不应再收到请求（A、B 各自只被请求过一次）
        val again = subject.localPathFor(
            uri = serverA.url("/dav/movie.vtt").toString(),
            mediaUrl = serverA.url("/dav/movie.mkv").toString(),
            scopeKey = "serverA",
            sessionHeaders = emptyMap(),
        )
        assertEquals(pathA, again)
        assertEquals(1, serverA.requestCount)
        assertEquals(1, serverB.requestCount)
    }

    // ---- 契约：scopeKey 非空（调用方始终传） ----

    @Test
    fun `blank scope key violates contract and must fail explicitly`() {
        val subject = cache(ScriptedCopy("x".toByteArray()))
        val thrown = runCatching {
            runBlocking { subject.localPathFor("content://imported/a.srt", "https://m/x.mkv", "  ", emptyMap()) }
        }
        assertTrue("空 scopeKey 必须 require 失败，不得静默共享缓存键", thrown.isFailure)
    }
}
