package com.mediahub.app

import android.app.Application
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.text.CueGroup
import androidx.room.Room
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.prefs.UserPreferencesStore
import com.mediahub.core.database.repository.ProgressRepository
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.Redactor
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.feature.player.ArtworkPaletteLoader
import com.mediahub.feature.player.PlayerViewModel
import com.mediahub.feature.player.ResolveState
import com.mediahub.feature.server.AddServerViewModel
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.model.ServerType
import com.mediahub.player.engine.EngineKind
import com.mediahub.player.engine.PlaybackEngineCreator
import com.mediahub.player.engine.PlaybackEnginePort
import com.mediahub.player.engine.PlaybackEvent
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.PlaybackUiState
import com.mediahub.player.engine.SeekMode
import com.mediahub.player.engine.TrackSelection
import com.mediahub.provider.base.DefaultProviderRegistry
import com.mediahub.provider.webdav.WebDavCredentialCoordinator
import com.mediahub.provider.webdav.WebDavProviderFactory
import com.mediahub.app.di.DataStoreEnginePreferenceHistory
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A2-6 联合链路测试：表单 → 保存 → 浏览 → 详情 → 播放源解析 → PlayerViewModel 接入
 * → NavArgCodec 往返（真实组件链，JVM/Robolectric + MockWebServer 扮演 WebDAV 服务器）。
 *
 * 全链使用真实生产实现，不用任何 Stub Auth/Browse/Detail/Playback：
 * - [AddServerViewModel]（表单）× [DefaultProviderRegistry]（真实注册表）×
 *   [WebDavProviderFactory]（真实工厂：真实 ApiClient/MediaHttpClient/Session/CredentialStore）；
 * - authenticate 的 PROPFIND Depth:0 真实打 MockWebServer（207）；
 * - browse/detail/playback 依次真实请求，凭据经 CredentialVault 代际（真实 WebDavCredentialStore）；
 * - [PlayerViewModel] 真实构造：真实 ServerStore/ProgressStore（Room in-memory）、真实 Registry、
 *   真实 UserPreferencesStore（DataStore）、真实 DataStoreEnginePreferenceHistory；
 *   唯一测试缝是 `PlaybackEngineCreator`（fun interface，可测性抽象 Phase 1B-2.1）——
 *   Android Player 引擎（ExoPlayer/libmpv）在 JVM 不可装配，替身只记录 engine.play 收到的
 *   [PlaybackSession]，据此断言 PlayerViewModel.resolve() 全链产出的播放源。
 *
 * 调度：真实线程池主执行器 + CountDownLatch 有界等待（Room suspend + 真实 IO 不吃虚拟时间，
 * 项目既定教训）；XML 响应计数：5 次 PROPFIND / 5 份 207 multistatus（detail 体复用 3 次）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebDavJointChainTest {

    private val mainExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "joint-chain-main").apply { isDaemon = true }
    }
    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    /** 内存 SecretStorage（JVM 测试用；Keystore 仅在设备上）。 */
    private class MemorySecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }

    /** StdoutLogger + 可见堆栈：联合链路失败时必须能看到确切异常。 */
    private class StackTraceLogger : Logger {
        override fun d(tag: LogTag, message: String) = println("[D][${tag.name}] ${Redactor.redact(message)}")
        override fun i(tag: LogTag, message: String) = println("[I][${tag.name}] ${Redactor.redact(message)}")
        override fun w(tag: LogTag, message: String, throwable: Throwable?) {
            println("[W][${tag.name}] ${Redactor.redact(message)}")
            throwable?.printStackTrace()
        }
        override fun e(tag: LogTag, message: String, throwable: Throwable?) {
            println("[E][${tag.name}] ${Redactor.redact(message)}")
            throwable?.printStackTrace()
        }
    }

    /** 记录 engine.play() 收到的会话：PlayerViewModel 全链产出（DIRECT_PLAY 播放源）的观测点。 */
    private class RecordingEngine(private val sink: MutableList<PlaybackSession>) : PlaybackEnginePort {
        override val kind = EngineKind.MEDIA3
        override val uiState = MutableStateFlow(PlaybackUiState())
        override val progress = kotlinx.coroutines.flow.MutableSharedFlow<PlaybackProgress>()
        override val events = kotlinx.coroutines.flow.MutableSharedFlow<PlaybackEvent>()
        override val subtitleCues = MutableStateFlow<CueGroup?>(null)
        override val downloadSpeedBps = MutableStateFlow(0L)
        override fun attachSurface(surface: Surface?) = Unit
        override fun play(session: PlaybackSession) { sink += session }
        override fun togglePlayPause() = Unit
        override fun seekTo(positionMs: Long, mode: SeekMode) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun selectAudioTrack(selection: TrackSelection?) = Unit
        override fun selectSubtitleTrack(selection: TrackSelection?) = Unit
        override fun stop(): PlaybackProgress? = null
        override fun release() = Unit
    }

    // ---- RFC 4918 multistatus 夹具（与 provider/webdav 测试夹具同构；跨模块 internal 不可见） ----

    private fun multistatus(vararg responses: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
           <D:multistatus xmlns:D="DAV:">${responses.joinToString(separator = "")}</D:multistatus>"""

    private fun collection(href: String): String = """<D:response>
        <D:href>$href</D:href>
        <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
        <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
      </D:response>"""

    private fun file(href: String, length: Long): String = """<D:response>
        <D:href>$href</D:href>
        <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>$length</D:getcontentlength></D:prop>
        <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
      </D:response>"""

    private lateinit var db: AppDatabase
    private lateinit var serverRepository: ServerRepository
    private lateinit var mock: MockWebServer
    private lateinit var registry: DefaultProviderRegistry
    private lateinit var base: String
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        serverRepository = ServerRepository(db)
        mock = MockWebServer()
        mock.start()
        base = mock.url("/").toString().trimEnd('/')
        val logger = StdoutLogger()
        val storage = MemorySecretStorage()
        val factory = WebDavProviderFactory(
            httpClientFactory = HttpClientFactory(logger),
            tokenStore = TokenStore(storage),
            credentialVault = CredentialVault(storage),
            credentialCoordinator = WebDavCredentialCoordinator(),
            logger = logger,
        )
        // 真实注册表（DefaultProviderRegistry）注入真实 Factory 集——不是 StubRegistry
        registry = DefaultProviderRegistry(setOf(factory))
    }

    @After
    fun tearDown() {
        viewModelStore.clear() // PlayerViewModel.onCleared → engine stop/release（替身端口）
        db.close()
        Dispatchers.resetMain()
        mainDispatcher.close()
        mock.shutdown()
    }

    private fun onMain(block: () -> Unit) {
        mainExecutor.submit { block() }.get(5, TimeUnit.SECONDS)
    }

    private fun expectedBasic(): String =
        "Basic " + Base64.getEncoder().encodeToString("alice:p@ss word".toByteArray(Charsets.UTF_8))

    /** 有界轮询 resolve 状态：真实 IO 完成即返回，不靠 sleep 伪造时序。 */
    private fun awaitResolve(vm: PlayerViewModel): ResolveState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            when (val state = vm.resolveState.value) {
                is ResolveState.Ready, is ResolveState.Failed -> return state
                else -> Thread.sleep(25)
            }
        }
        return vm.resolveState.value
    }

    @Test
    fun `webdav form to saved server to browse detail playback and player source`() {
        // ============================================================
        // 1. 表单：真实 AddServerViewModel + Room in-memory + 真实 Registry
        // ============================================================
        val descriptors = registry.descriptors()
        assertEquals("真实 Registry 只解析出真实 WebDAV Factory", 1, descriptors.size)
        assertEquals(ServerType.WEBDAV, descriptors.single().serverType)

        val addVm = AddServerViewModel(
            SavedStateHandle(emptyMap()),
            serverRepository,
            registry,
            StdoutLogger(),
        )
        assertEquals("webdav", addVm.uiState.value.selectedDescriptorId)
        onMain {
            addVm.updateName("家里 NAS")
            addVm.updateBaseUrl(base) // 显式 http scheme：规范化原样保留
            addVm.updateUsername("alice")
            addVm.updatePassword("p@ss word")
        }
        val resolvedUrl = requireNotNull(addVm.uiState.value.resolvedUrl)
        assertEquals("规范化不改动 MockWebServer 地址", base, resolvedUrl)

        // authenticate 的 PROPFIND Depth:0（第 1 个 207）
        mock.enqueue(MockResponse().setResponseCode(207).setBody(multistatus(collection("/"))))

        var saved: com.mediahub.model.MediaServer? = null
        val savedLatch = CountDownLatch(1)
        onMain {
            addVm.loginAndSave { server -> saved = server; savedLatch.countDown() }
        }
        assertTrue("登录+落库必须完成", savedLatch.await(15, TimeUnit.SECONDS))

        val server = requireNotNull(saved)
        assertEquals(ServerType.WEBDAV, server.type)
        assertEquals("alice", server.username)
        assertEquals(listOf(resolvedUrl), server.endpoints.map { it.url })
        assertTrue(server.endpoints.single().isPrimary)
        assertEquals(
            "落库与回调一致",
            server.id,
            runBlocking { serverRepository.getServer(server.id) }?.id,
        )

        // ============================================================
        // 2. 浏览：真实 WebDavProviderFactory.create(server) → handle.browse
        // ============================================================
        val persisted = requireNotNull(runBlocking { serverRepository.getServer(server.id) })
        val handle = requireNotNull(registry.create(persisted))
        val browse = requireNotNull(handle.browse)

        // PROPFIND Depth:1（第 2 个 207）：1 子目录（标准编码 href）+ 1 视频文件（未编码中文/空格 href）
        mock.enqueue(
            MockResponse().setResponseCode(207).setBody(
                multistatus(collection(FOLDER_HREF), file(FILE_HREF, FILE_SIZE)),
            ),
        )
        val page = runBlocking { browse.listFolder(null, PageRequest()) }

        assertEquals(2, page.totalCount)
        assertFalse(page.hasMore)
        assertNull(page.nextOffset)
        val folder = page.items.single { it.type == MediaType.FOLDER }
        assertEquals("中文目录名经 percent-decode 还原", FOLDER_TITLE, folder.title)
        val video = page.items.single { it.type == MediaType.VIDEO }
        assertEquals("中文+空格文件名无损", FILE_TITLE, video.title)
        assertEquals(FILE_SIZE, video.sizeBytes)
        assertEquals("mkv", video.container)
        assertTrue("条目 id 是同 origin 绝对 URL", video.id.startsWith("$base/"))
        assertEquals("percent 编码的 href 解析回绝对 URL", base + FOLDER_HREF, folder.id)

        // ============================================================
        // 3. 详情：handle.detail.getItemDetail(browse 产出的 itemId)
        // ============================================================
        val detail = requireNotNull(handle.detail)
        // PROPFIND Depth:0 打在文件上（第 3 个 207）
        mock.enqueue(
            MockResponse().setResponseCode(207).setBody(multistatus(file(FILE_HREF, FILE_SIZE))),
        )
        val mediaDetail = runBlocking { detail.getItemDetail(video.id) }
        assertEquals(FILE_TITLE, mediaDetail.item.title)
        assertEquals(MediaType.VIDEO, mediaDetail.item.type)
        assertEquals(FILE_SIZE, mediaDetail.item.sizeBytes)
        assertEquals("itemId 即同 origin 绝对 URL，path 与 id 一致", video.id, mediaDetail.item.path)
        assertEquals(video.id, mediaDetail.item.id)

        // ============================================================
        // 4. 播放源解析：handle.playback.resolvePlayback → DIRECT_PLAY
        // ============================================================
        val source = runBlocking {
            requireNotNull(handle.playback).resolvePlayback(mediaDetail.item, PlaybackOptions())
        }
        assertEquals(PlaybackMode.DIRECT_PLAY, source.mode)
        assertTrue("播放地址与服务器同 origin", source.url.startsWith("$base/"))
        assertEquals("Basic 凭据只经 header 传递（真实表单密码）", expectedBasic(), source.headers["Authorization"])
        assertEquals("video/x-matroska", source.mimeType)
        assertNull("WebDAV 无服务端会话语义", source.sessionId)
        assertTrue(source.supportsSeeking)

        // ============================================================
        // 5. PlayerViewModel 接入：真实构造，init 自动 resolve()
        //    （无 type 快照 → detail 分支真实打 PROPFIND Depth:0，第 4 个 207）
        // ============================================================
        val engineSessions = CopyOnWriteArrayList<PlaybackSession>()
        val engineCreator = PlaybackEngineCreator { RecordingEngine(engineSessions) }
        val appContext = RuntimeEnvironment.getApplication()
        // PROPFIND Depth:0 打在文件上（第 4 个 207）——必须在 VM 构造前入队（init 自动 resolve）
        mock.enqueue(
            MockResponse().setResponseCode(207).setBody(multistatus(file(FILE_HREF, FILE_SIZE))),
        )
        val playerVm = PlayerViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "serverId" to server.id,
                    "itemId" to NavArgCodec.encode(video.id),
                ),
            ),
            serverStore = serverRepository,
            progressStore = ProgressRepository(db),
            subtitleMemoryStore = object : com.mediahub.core.database.repository.SubtitleMemoryStore {
                override suspend fun recall(versionKey: String) = null
                override suspend fun remember(entry: com.mediahub.core.database.repository.SubtitleMemoryEntry) = Unit
                override suspend fun forget(versionKey: String) = Unit
            },
            registry = registry,
            media3EngineFactory = engineCreator,
            mpvEngineFactory = engineCreator,
            engineHistory = DataStoreEnginePreferenceHistory(appContext),
            userPreferencesRepository = UserPreferencesStore(appContext),
            artworkPaletteLoader = ArtworkPaletteLoader { _, _ -> null },
            logger = StackTraceLogger(),
        )
        viewModelStore.put("player", playerVm)

        val resolveState = awaitResolve(playerVm)
        assertTrue(
            "resolve 必须就绪，实际=$resolveState",
            resolveState is ResolveState.Ready,
        )
        val session = requireNotNull(engineSessions.firstOrNull()) {
            "engine.play 必须收到真实链路解析出的播放会话"
        }
        assertEquals(server.id, session.serverId)
        assertEquals("NavArgCodec 解码后的 itemId 进入会话", video.id, session.itemId)
        assertEquals(MediaType.VIDEO, session.itemType)
        assertEquals(FILE_TITLE, session.itemTitle)
        val playerSource: PlaybackSource = session.source
        assertEquals("PlayerViewModel 全链产出 DIRECT_PLAY", PlaybackMode.DIRECT_PLAY, playerSource.mode)
        assertEquals("播放地址 = 详情条目同 origin URL", video.id, playerSource.url)
        assertEquals(expectedBasic(), playerSource.headers["Authorization"])

        // ============================================================
        // 6. NavArgCodec 往返：encode → decode → 再次 getItemDetail 闭环
        // ============================================================
        val encoded = NavArgCodec.encode(video.id)
        val decoded = NavArgCodec.decode(encoded)
        assertEquals("路由参数无损往返", video.id, decoded)
        assertFalse(encoded.contains('/'))
        // PROPFIND Depth:0（第 5 个 207）
        mock.enqueue(
            MockResponse().setResponseCode(207).setBody(multistatus(file(FILE_HREF, FILE_SIZE))),
        )
        val roundtrip = runBlocking { detail.getItemDetail(decoded) }
        assertEquals(video.id, roundtrip.item.id)
        assertEquals(FILE_TITLE, roundtrip.item.title)
        assertEquals(FILE_SIZE, roundtrip.item.sizeBytes)

        // ============================================================
        // 协议审计：5 个请求全部是带 Basic 凭据的 PROPFIND，Depth/路径逐一核对
        // ============================================================
        val requests = mutableListOf<RecordedRequest>()
        while (true) {
            val request = mock.takeRequest(2, TimeUnit.SECONDS) ?: break
            requests += request
        }
        // P2 字幕中心接入后：resolve 成功自动触发同目录字幕发现（+1 次 Depth:1
        // PROPFIND），总请求 5→6（auth 0 / browse 1 / detail 0 / player detail 0 /
        // replay-detail 0 / subtitle-discovery 1）
        assertEquals("XML 响应与请求一一对应（6 份 207 multistatus）", 6, requests.size)
        val depths = requests.map { it.getHeader("Depth") }
        assertEquals(listOf("0", "1", "0", "0", "0", "1"), depths)
        requests.forEach { request ->
            assertEquals("PROPFIND", request.method)
            assertEquals("每次请求都携带同一条 Basic 凭据", expectedBasic(), request.getHeader("Authorization"))
        }
        assertEquals("认证与浏览都打在根目录", listOf("/", "/"), requests.take(2).map { it.path })
        requests.drop(2).take(3).forEach { request ->
            assertEquals(
                "详情/播放链路真实命中中文+空格文件（percent 编码保真）",
                FILE_PATH_DECODED,
                URLDecoder.decode(requireNotNull(request.path), Charsets.UTF_8),
            )
        }
        assertEquals(
            "字幕发现打在视频所在目录（Depth:1 同目录枚举）",
            "/电影/",
            URLDecoder.decode(requireNotNull(requests.last().path), Charsets.UTF_8),
        )
    }

    private companion object {
        const val FOLDER_TITLE = "电影"
        const val FILE_TITLE = "流浪 地球 2.mkv"
        const val FILE_SIZE = 241_172_480L
        const val FILE_PATH_DECODED = "/电影/流浪 地球 2.mkv"

        /** 标准服务器形态：href 已 percent 编码。 */
        const val FOLDER_HREF = "/%E7%94%B5%E5%BD%B1/"

        /** 真实世界常见形态：href 含未编码中文与空格（客户端容错编码路径）。 */
        const val FILE_HREF = "/电影/流浪 地球 2.mkv"
    }
}
