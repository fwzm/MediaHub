package com.mediahub.player.engine

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.mediahub.core.network.NetworkExchangeLedger
import com.mediahub.core.network.RequestChannel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * M16-G4 · Media3 取流路径的**可观测性边界**回归测试（先红后绿）。
 *
 * 背景（AGENTS.md §7.4 / queue.json G4）：`recordUnobservable` 在 Media3 取流路径上
 * 没有生产调用者，因此 `partialObservability` 恒为 false —— 台账会把
 * "ExoPlayer 真实出网" 静默表述成 "零交换"，这正是 V8 §三 明令禁止的"伪造 0"。
 *
 * 本测试驱动的是**生产链本身**：`PlayerFactory.mediaDataSourceFactory`（即 `create()`
 * 装入 ExoPlayer 的同一条链）→ 打开真实 HTTP URL → **真实发出一次取流请求**。
 * 断言两点：
 * 1. MockWebServer 确实收到了请求（证明走的是真实取流路径，而不是降级分支）；
 * 2. 台账在该起播点登记了 MEDIA 不可观测通道 → `partialObservability == true`。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Media3ObservabilityBoundaryTest {

    private lateinit var server: MockWebServer
    private lateinit var cacheProvider: MediaCacheProvider

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        cacheProvider = MediaCacheProvider(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        runCatching { cacheProvider.cache.release() }
        server.shutdown()
    }

    private fun newFactory(ledger: NetworkExchangeLedger?) = PlayerFactory(
        RuntimeEnvironment.getApplication(),
        cacheProvider,
        ledger = ledger,
    )

    private fun openAndRead(factory: DataSource.Factory, url: String) {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec(Uri.parse(url)))
            val sink = ByteArray(16)
            var total = 0
            while (true) {
                val read = source.read(sink, 0, sink.size)
                if (read == -1) break
                total += read
                if (total >= 16) break
            }
            assertTrue("media3 取流必须真正读到字节", total > 0)
        } finally {
            source.close()
        }
    }

    private fun MockWebServer.takeRequestOrFail(label: String): RecordedRequest {
        val request = takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("5s 内没有请求到达 $label —— 测试未走真实出网路径", request)
        return request!!
    }

    @Test
    fun `production media chain marks MEDIA unobservable at the real fetch start point`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setBody("0123456789abcdef"),
        )
        val ledger = NetworkExchangeLedger()
        assertTrue("接线前台账不应声称部分可观测", !ledger.snapshot().partialObservability)

        openAndRead(newFactory(ledger).mediaDataSourceFactory(PlaybackHeadersHolder(), PlaybackSpeedMonitor()),
            server.url("/emby/Videos/1/stream.mp4").toString())

        // 先证明真的出网了：请求确实到达服务器。
        val request = server.takeRequestOrFail("server")
        assertTrue("必须是媒体取流请求", request.path!!.contains("/stream.mp4"))

        val snap = ledger.snapshot()
        assertTrue(
            "Media3 在生产起点必须登记不可观测，否则未计数会被伪装成 0（actual=" + snap.coverageNote + "）",
            snap.partialObservability,
        )
        assertTrue("必须精确登记 MEDIA 通道", RequestChannel.MEDIA in snap.partialChannels)
    }

    @Test
    fun `a custom header holder still reaches the network with the ledger wired`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789abcdef"))
        val ledger = NetworkExchangeLedger()
        val holder = PlaybackHeadersHolder().apply { setHeaders(mapOf("X-Emby-Token" to "tok-g4")) }

        openAndRead(newFactory(ledger).mediaDataSourceFactory(holder, PlaybackSpeedMonitor()),
            server.url("/emby/Videos/2/stream.mp4").toString())

        // 接线不得破坏既有的请求头注入语义（M16 只加观测，不改行为）。
        assertNotNull(server.takeRequestOrFail("server").getHeader("X-Emby-Token"))
        assertTrue(ledger.snapshot().partialObservability)
    }

    @Test
    fun `without a ledger the production chain stays byte-for-byte usable`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("0123456789abcdef"))

        openAndRead(newFactory(null).mediaDataSourceFactory(PlaybackHeadersHolder(), PlaybackSpeedMonitor()),
            server.url("/emby/Videos/3/stream.mp4").toString())

        assertNotNull(
            "未注入台账时生产链必须照常工作（默认路径行为不变）",
            server.takeRequestOrFail("server"),
        )
    }
}
