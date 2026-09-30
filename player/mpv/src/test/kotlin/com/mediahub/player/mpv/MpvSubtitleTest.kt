package com.mediahub.player.mpv

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.PlaybackSource
import com.mediahub.player.engine.ExternalSubtitle
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.SubtitleCapabilities
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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

        /** A4 终审：真实轨道表模型——每轨 (type, external-filename, selected)。 */
        class Track(val type: String, val externalFilename: String?, val selected: Boolean)

        /** sub-add 成功后追加的轨道（可编程：默认目标 sub 轨已选中）。 */
        var tracksToAppendOnSubAdd = mutableListOf(Track("sub", null, true))
        /** sub-add 时可编程插入的无关轨道（模拟并发轨道添加，插在目标轨之前/之后）。 */
        val extraTracksBefore = mutableListOf<Track>()
        val extraTracksAfter = mutableListOf<Track>()
        var subDelayValue: Double? = null
        /** 模拟 mpv 拒绝 sub-delay 写入：set 不生效（回读保持旧值）。 */
        var rejectSubDelay = false

        private val tracks = mutableListOf<Track>()

        override fun addObserver(observer: MpvInstance.Observer) { this.observer = observer }
        override fun setOptionString(name: String, value: String) = Unit
        override fun init() = Unit
        override fun attachSurface(surface: android.view.Surface) = Unit
        override fun detachSurface() = Unit
        override fun observeProperty(name: String, format: MpvInstance.Format) = Unit
        override fun command(args: Array<String>) {
            commands.add(args)
            if (args.first() == "sub-add") {
                tracks += extraTracksBefore
                val targetFilename = args.getOrNull(1)
                tracksToAppendOnSubAdd.forEach {
                    tracks += Track(it.type, it.externalFilename ?: targetFilename, it.selected)
                }
                tracks += extraTracksAfter
            }
        }
        override fun setPropertyBoolean(name: String, value: Boolean) = Unit
        override fun setPropertyDouble(name: String, value: Double) {
            doubles.add(name to value)
            if (name == "sub-delay" && !rejectSubDelay) subDelayValue = value
        }
        private fun trackIndexed(name: String): Pair<Int, String>? {
            val m = Regex("track-list/(\\d+)/(.+)").find(name) ?: return null
            return m.groupValues[1].toInt() to m.groupValues[2]
        }
        override fun getPropertyBoolean(name: String): Boolean {
            val (index, field) = trackIndexed(name) ?: return false
            if (field != "selected") return false
            return tracks.getOrNull(index)?.selected == true
        }
        override fun getPropertyDouble(name: String): Double = when (name) {
            "track-list/count" -> tracks.size.toDouble()
            "sub-delay" -> subDelayValue ?: 0.0
            else -> 0.0
        }
        override fun getPropertyString(name: String): String? {
            val (index, field) = trackIndexed(name) ?: return null
            val track = tracks.getOrNull(index) ?: return null
            return when (field) {
                "type" -> track.type
                "external-filename" -> track.externalFilename
                else -> null
            }
        }
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
        // 模拟 mpv 拒绝加载：轨道数不增长
        f.instances.single().tracksToAppendOnSubAdd.clear()

        val ok = f.engine.loadExternalSubtitle(localSub())

        assertFalse("轨道表无新轨时不得报成功（上游错误码不回传，以回读为准）", ok)
    }

    // ---- A4 终审：目标资源身份与选中状态确认（逐轨扫描） ----

    @Test
    fun `target sub track confirmed by external filename and selected`() = runTest {
        val f = SubtitleFixture(backgroundScope, RecordingCache("/data/subs/movie.zh.srt"))
        f.engine.play(session("https://media.example/movie.mkv"))
        runCurrent()

        val ok = f.engine.loadExternalSubtitle(localSub())

        assertTrue("目标轨存在+filename 匹配+selected 必须确认成功", ok)
    }

    @Test
    fun `unrelated concurrent tracks do not break target confirmation`() = runTest {
        val f = SubtitleFixture(backgroundScope, RecordingCache("/data/subs/movie.zh.srt"))
        f.engine.play(session("https://media.example/movie.mkv"))
        runCurrent()
        val inst = f.instances.single()
        // 并发无关轨：目标轨之前插 video 轨，之后插另一条非选中 sub 轨
        inst.extraTracksBefore += FakeInstance.Track("video", null, false)
        inst.extraTracksAfter += FakeInstance.Track("sub", "/other/sub.en.srt", false)

        val ok = f.engine.loadExternalSubtitle(localSub())

        assertTrue("目标非末轨+并发无关轨不得阻断目标确认", ok)
    }

    @Test
    fun `wrong filename or unselected track reports failure`() = runTest {
        val f = SubtitleFixture(backgroundScope, RecordingCache("/data/subs/movie.zh.srt"))
        f.engine.play(session("https://media.example/movie.mkv"))
        runCurrent()
        // filename 不匹配（sub-add 到别的文件）→ 不得确认
        f.instances.single().tracksToAppendOnSubAdd =
            mutableListOf(FakeInstance.Track("sub", "/other/wrong.srt", true))
        val okWrongFile = f.engine.loadExternalSubtitle(localSub())
        assertFalse("external-filename 不匹配不得报成功", okWrongFile)

        // filename 匹配但未选中 → 不得确认
        val f2 = SubtitleFixture(backgroundScope, RecordingCache("/data/subs/movie.zh.srt"))
        f2.engine.play(session("https://media.example/movie.mkv"))
        runCurrent()
        f2.instances.single().tracksToAppendOnSubAdd =
            mutableListOf(FakeInstance.Track("sub", null, false))
        val okUnselected = f2.engine.loadExternalSubtitle(localSub())
        assertFalse("目标轨未选中不得报成功", okUnselected)
    }

    @Test
    fun `stop interleaved with subtitle load prevents sub-add and closes cache session`() = runTest {
        val cacheDir = kotlin.io.path.createTempDirectory("a4-stop-cache").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val barrierCache = object : SubtitleCache(
            cacheDir, okhttp3.OkHttpClient(), { _, _ -> false }, NoLogger,
        ) {
            override suspend fun localPathFor(
                uri: String, mediaUrl: String, scopeKey: String,
                sessionHeaders: Map<String, String>,
            ): String? {
                entered.complete(Unit)
                release.await()
                return "/data/subs/movie.zh.srt"
            }
        }
        val f = SubtitleFixture(backgroundScope, barrierCache)
        f.engine.play(session("https://media.example/movie.mkv"))
        runCurrent()

        var ok: Boolean? = null
        val job = launch { ok = f.engine.loadExternalSubtitle(localSub()) }
        // 确定性推进：runCurrent 让 job 启动并挂起在下载屏障（entered 随即完成）
        runCurrent()
        check(entered.isCompleted) { "字幕加载未到达下载屏障（调度未推进）" }
        // 与下载挂起交错：stop 使会话失效 → isCurrent 闸门丢弃 sub-add
        f.engine.stop()
        release.complete(Unit)
        runCurrent()
        job.join()

        assertEquals("stop 交错后字幕加载不得报成功", false, ok)
        assertFalse("不得发生任何 sub-add", f.instances.single().commands.any { it.first() == "sub-add" })
        // endSession 已执行：会话缓存目录被清空
        val sessionDir = java.io.File(cacheDir, "subtitles")
        assertTrue(
            "endSession 必须清空会话缓存目录（或从未创建）",
            sessionDir.listFiles()?.isEmpty() ?: true,
        )
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
