package com.mediahub.player.mpv

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.PlaybackSource
import com.mediahub.player.engine.ExternalSubtitle
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.SubtitleCapabilities
import java.io.File
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * mpv 外挂字幕（P2 字幕中心切片一）：
 * - 能力矩阵（externalLoad=true, offsetAdjust=true）；
 * - sub-add select 直挂本地路径；http 先落地 cache（同 origin 才携带凭据头）；
 * - 落地失败如实返回 false；偏移写入 sub-delay 并做 ±60s 钳制。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MpvSubtitleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var webServer: MockWebServer
    private lateinit var fixture: SubtitleFixture

    @Before
    fun setUp() {
        webServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        webServer.shutdown()
    }

    private class RecordingCache(private val result: String?) : SubtitleCache(
        cacheDir = File("/nonexistent"),
        client = okhttp3.OkHttpClient(),
        contentResolver = { _, _ -> false },
        logger = NoLogger,
    ) {
        val calls = mutableListOf<Array<String>>()
        override suspend fun localPathFor(
            uri: String,
            mediaUrl: String,
            scopeKey: String,
            sessionHeaders: Map<String, String>,
        ): String? {
            calls.add(arrayOf(uri, mediaUrl, scopeKey))
            return result
        }
    }

    private class SubtitleFixture(scope: kotlinx.coroutines.CoroutineScope, cache: SubtitleCache) {
        val instances = mutableListOf<FakeInstance>()
        val deferred = ArrayDeque<() -> Unit>()
        val engine = MpvPlaybackEngine(
            logger = NoLogger,
            scope = scope,
            bridgeFactory = { FakeBridge },
            instanceFactory = { FakeInstance().also { instances.add(it) } },
            elapsedRealtime = { 0L },
            currentTimeMillis = { 0L },
            deferNative = { deferred.addLast(it) },
            subtitleCacheFactory = { cache },
        )
        fun flush() { while (deferred.isNotEmpty()) deferred.removeFirst().invoke() }
    }

    private object FakeBridge : MpvBridge {
        override fun start(url: String, headers: Map<String, String>) = "http://127.0.0.1/fake"
        override fun stop() = Unit
    }

    private class FakeInstance : MpvInstance {
        lateinit var observer: MpvInstance.Observer
        val commands = mutableListOf<Array<String>>()
        val doubles = mutableListOf<Pair<String, Double>>()
        /** A4 成功确认回读状态：轨道数与新轨类型可编程（默认模拟成功加载）。 */
        var trackCount = 0
        var newTrackType: String? = "sub"
        var subDelayValue: Double? = null
        /** 模拟 mpv 拒绝 sub-delay 写入：set 不生效（回读保持旧值）。 */
        var rejectSubDelay = false
        override fun addObserver(observer: MpvInstance.Observer) { this.observer = observer }
        override fun setOptionString(name: String, value: String) = Unit
        override fun init() = Unit
        override fun attachSurface(surface: android.view.Surface) = Unit
        override fun detachSurface() = Unit
        override fun observeProperty(name: String, format: MpvInstance.Format) = Unit
        override fun command(args: Array<String>) {
            commands.add(args)
            if (args.first() == "sub-add") trackCount++
        }
        override fun setPropertyBoolean(name: String, value: Boolean) = Unit
        override fun setPropertyDouble(name: String, value: Double) {
            doubles.add(name to value)
            if (name == "sub-delay" && !rejectSubDelay) subDelayValue = value
        }
        override fun getPropertyBoolean(name: String): Boolean = false
        override fun getPropertyDouble(name: String): Double = when (name) {
            "track-list/count" -> trackCount.toDouble()
            "sub-delay" -> subDelayValue ?: 0.0
            else -> 0.0
        }
        override fun getPropertyString(name: String): String? =
            if (name.startsWith("track-list/") && name.endsWith("/type")) newTrackType else null
        override fun destroy() = Unit
    }

    private object NoLogger : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    private fun session(url: String) = PlaybackSession(
        serverId = "srv", itemId = "m1", itemTitle = "t",
        source = PlaybackSource(
            url = url,
            headers = mapOf("Authorization" to "Basic dXNlcjpwYXNz"),
        ),
    )

    private fun localSub() = ExternalSubtitle(
        id = "/data/subs/movie.zh.srt",
        name = "movie.zh",
        uri = "/data/subs/movie.zh.srt",
        mimeType = "application/x-subrip",
        language = "zh",
    )

    // ---- 能力矩阵 ----

    @Test
    fun `capability matrix externalLoad true offsetAdjust true`() = runTest {
        val f = SubtitleFixture(backgroundScope, RecordingCache("/x"))
        assertTrue(f.engine.subtitleCapabilities == SubtitleCapabilities(externalLoad = true, offsetAdjust = true))
    }

    // ---- sub-add select 直挂 ----

    @Test
    fun `local path goes straight to sub-add select`() = runTest {
        val recording = RecordingCache("/data/subs/movie.zh.srt")
        val f = SubtitleFixture(backgroundScope, recording)
        f.engine.play(session("https://media.example/movie.mkv"))
        runCurrent()

        val ok = f.engine.loadExternalSubtitle(localSub())

        assertTrue(ok)
        val subAdd = f.instances.single().commands.first { it.first() == "sub-add" }
        assertEquals(listOf("sub-add", "/data/subs/movie.zh.srt", "select"), subAdd.toList())
        // A4 契约：引擎传给缓存的 scopeKey（媒体版本指纹）必须非空
        assertTrue("引擎必须传入非空 scopeKey", recording.calls.all { it[2].isNotBlank() })
    }

    @Test
    fun `cache failure returns false without touching native`() = runTest {
        val f = SubtitleFixture(backgroundScope, RecordingCache(null))
        f.engine.play(session("https://media.example/movie.mkv"))
        runCurrent()

        val ok = f.engine.loadExternalSubtitle(localSub())

        assertFalse(ok)
        assertFalse(f.instances.single().commands.any { it.first() == "sub-add" })
    }

    // ---- A4：成功状态确认（命令完成 ≠ 加载成功） ----

    @Test
    fun `sub-add without new sub track in track-list reports failure`() = runTest {
        val f = SubtitleFixture(backgroundScope, RecordingCache("/data/subs/movie.zh.srt"))
        f.engine.play(session("https://media.example/movie.mkv"))
        runCurrent()
        // 模拟 mpv 拒绝加载：轨道数不增长（或新轨非 sub）
        f.instances.single().newTrackType = "audio"

        val ok = f.engine.loadExternalSubtitle(localSub())

        assertFalse("轨道表无新 sub 轨时不得报成功（上游错误码不回传，以回读为准）", ok)
    }

    @Test
    fun `offset value mismatch on readback reports failure`() = runTest {
        val f = SubtitleFixture(backgroundScope, RecordingCache("/x"))
        f.engine.play(session("https://media.example/movie.mkv"))
        runCurrent()
        // 模拟 mpv 拒绝 sub-delay 写入：set 不生效，回读保持 0（与请求 1.2s 不符）
        f.instances.single().rejectSubDelay = true

        val ok = f.engine.setSubtitleOffset(1_200)

        assertFalse("偏移回读不符时不得报成功", ok)
    }

    @Test
    fun `offset writes sub-delay with clamping`() = runTest {
        val f = SubtitleFixture(backgroundScope, RecordingCache("/x"))
        f.engine.play(session("https://media.example/movie.mkv"))
        runCurrent()
        f.flush()

        assertTrue(f.engine.setSubtitleOffset(1_500L))
        assertTrue(f.engine.setSubtitleOffset(999_000L)) // 钳到 +60s

        val delays = f.instances.single().doubles.filter { it.first == "sub-delay" }
        assertEquals(1.5, delays[0].second, 0.0001)
        assertEquals(60.0, delays[1].second, 0.0001)
        assertEquals(60_000L, f.engine.subtitleOffsetMs)
    }

    @Test
    fun `offset before any session is rejected`() = runTest {
        val f = SubtitleFixture(backgroundScope, RecordingCache("/x"))
        assertFalse(f.engine.setSubtitleOffset(500L))
        assertEquals(0L, f.engine.subtitleOffsetMs)
    }

    // ---- SubtitleCache 真实落地（http 下载 + 凭据作用域 + content 拷贝） ----

    @Test
    fun `http subtitle same origin media downloads with credentials`() = kotlinx.coroutines.runBlocking {
        webServer.enqueue(MockResponse().setBody("WEBVTT\n\n00:00:01.000 --> 00:00:02.000 hi"))
        val cache = SubtitleCache(
            cacheDir = tmp.newFolder(),
            client = okhttp3.OkHttpClient(),
            contentResolver = { _, _ -> false },
            logger = NoLogger,
        )
        val subtitleUrl = webServer.url("/dav/movie.vtt").toString()

        val path = cache.localPathFor(
            uri = subtitleUrl,
            mediaUrl = webServer.url("/dav/movie.mkv").toString(),
            scopeKey = "test-scope",
            sessionHeaders = mapOf("Authorization" to "Basic dXNlcjpwYXNz"),
        )

        val request = webServer.takeRequest()
        assertEquals("Basic dXNlcjpwYXNz", request.getHeader("Authorization"))
        assertTrue(path!!.endsWith(".vtt"))
        assertTrue(File(path).length() > 0)
        // 二次落地命中缓存，不再产生新请求
        webServer.enqueue(MockResponse().setResponseCode(500))
        val again = cache.localPathFor(
            subtitleUrl,
            webServer.url("/dav/movie.mkv").toString(),
            "test-scope",
            emptyMap(),
        )
        assertEquals(path, again)
    }

    @Test
    fun `http subtitle cross origin media never carries credentials`() = kotlinx.coroutines.runBlocking {
        webServer.enqueue(MockResponse().setResponseCode(500)) // 落地必须失败
        val cache = SubtitleCache(
            cacheDir = tmp.newFolder(),
            client = okhttp3.OkHttpClient(),
            contentResolver = { _, _ -> false },
            logger = NoLogger,
        )

        val path = cache.localPathFor(
            uri = webServer.url("/dav/movie.srt").toString(),
            mediaUrl = "https://other-server.example/movie.mkv",
            scopeKey = "test-scope",
            sessionHeaders = mapOf("Authorization" to "Basic dXNlcjpwYXNz"),
        )

        assertNull(path) // 401/500 落地失败，如实返回 null
        val request = webServer.takeRequest()
        assertNull(request.getHeader("Authorization")) // 凭据绝不发往异 origin
    }

    @Test
    fun `file and local paths pass through without cache touch`() = kotlinx.coroutines.runBlocking {
        val cache = SubtitleCache(
            cacheDir = tmp.newFolder(),
            client = okhttp3.OkHttpClient(),
            contentResolver = { _, _ -> false },
            logger = NoLogger,
        )
        // file:// 与 POSIX 绝对路径：纯字符串解析，不触碰文件系统
        assertEquals("/data/subs/a.srt", cache.localPathFor("/data/subs/a.srt", "https://m/x.mkv", "test-scope", emptyMap()))
        assertEquals("/data/subs/a.srt", cache.localPathFor("file:///data/subs/a.srt", "https://m/x.mkv", "test-scope", emptyMap()))
        // 相对路径不可直挂（mpv 无法解析），如实返回 null
        assertNull(cache.localPathFor("subs/a.srt", "https://m/x.mkv", "test-scope", emptyMap()))
    }
}
