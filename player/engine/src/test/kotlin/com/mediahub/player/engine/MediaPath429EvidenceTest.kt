package com.mediahub.player.engine

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.mediahub.core.network.ServerIdResolver
import com.mediahub.core.network.UserAgentPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * G5 · Media3 生产取流路径的 **429 行为证据**（只取证，不改生产代码）。
 *
 * 已知残余风险（AGENTS.md §7.1 / queue.json G5）：
 * 「Coil 图片与 Media3 ExoPlayer 取流两条生产出网路径没有 429 冷却」，
 * 且后者正是**实际播放视频**的路径。
 *
 * 本测试不做补丁式断言，而是驱动**与生产完全相同的链**
 *（[PlayerFactory.mediaDataSourceFactory]，即 ExoPlayer 取流唯一入口），
 * 用 MockWebServer 复现「长视频 Range 206 之后同源收到 429」，
 * 并把**当前实际行为**固化成可重复执行的证据：
 *
 * 1. 429 以异常形式上抛（播放中断），不被吸收重试；
 * 2. Retry-After 不被解析（无任何等待/退避）；
 * 3. 同一 client、同一 origin（且 serverId 解析器可解析）上，
 *    下一次请求仍立刻真实出网 —— 该路径上**不存在**任何 429 冷却。
 *
 * 第 3 点是关键反证：若该路径具备冷却，被冷却作用域的后续请求会在**连接前**
 * 快速失败、根本到达不了服务器（对照：core:network 的 RateLimitEgressBoundaryTest）。
 *
 * 语义声明：本测试不声称该缺陷可接受，只固定当前事实，供策略决策使用。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPath429EvidenceTest {

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

    /**
     * 生产链 + **已知 serverId 解析器**：若链上存在冷却，scope 必然解析到 srv-g5，
     * 因此「下一次仍出网」不能被解释成「解析不到 serverId 所以没进冷却」。
     */
    private fun productionFactory(): DataSource.Factory = PlayerFactory(
        RuntimeEnvironment.getApplication(),
        cacheProvider,
        userAgentPolicy = UserAgentPolicy(),
        serverIdFor = ServerIdResolver { "srv-g5" },
    ).mediaDataSourceFactory(PlaybackHeadersHolder(), PlaybackSpeedMonitor())

    private fun readOnce(url: String, position: Long, length: Int): Int {
        val source = productionFactory().createDataSource()
        try {
            source.open(DataSpec(Uri.parse(url), position, length.toLong()))
            val buffer = ByteArray(length)
            var total = 0
            while (total < length) {
                val read = source.read(buffer, total, length - total)
                if (read == -1) break
                total += read
            }
            return total
        } finally {
            runCatching { source.close() }
        }
    }

    private fun MockWebServer.takeRequestOrFail(label: String): RecordedRequest {
        val request = takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("5s 内没有请求到达 $label —— 测试未走真实出网路径", request)
        return request!!
    }

    @Test
    fun `after a 206 range response a same origin 429 surfaces and the next request still egresses`() {
        val streamUrl = server.url("/emby/Videos/g5-long/stream.mp4").toString()

        // 1) 长视频首段：206 Partial Content（正常播放中的 Range 请求）
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-7/1024")
                .setHeader("Content-Type", "video/mp4")
                .setBody("01234567"),
        )
        assertEquals("首段必须真正读到字节", 8, readOnce(streamUrl, 0, 8))
        val first = server.takeRequestOrFail("segment-1")
        assertNotNull("首段必须是 Range 请求", first.getHeader("Range"))

        // 2) 同源后续 Range 被限流：真实服务器常见形态
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "60")
                .setBody("too many requests"),
        )
        val startedAtMs = System.currentTimeMillis()
        try {
            readOnce(streamUrl, 8, 8)
            fail("429 必须被上抛（否则播放会在无字节的情况下静默继续）")
        } catch (expected: Exception) {
            val root = generateSequence<Throwable>(expected) { it.cause }.last()
            val msg = (expected.message ?: "") + " " + (root.message ?: "")
            assertTrue(
                "429 必须以 HTTP 响应码错误形式上抛，实际：$msg",
                expected is DataSourceException || msg.contains("429") || msg.contains("Invalid response"),
            )
        }
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        assertTrue(
            "不得因 Retry-After 产生任何退避（该路径不解析 Retry-After）；实际等待 ${elapsedMs}ms",
            elapsedMs < 5_000,
        )
        server.takeRequestOrFail("segment-2-429")

        // 3) **关键反证**：同一 origin / 可解析 serverId 上，后续请求仍然真实出网
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 16-23/1024")
                .setBody("89abcdef"),
        )
        assertEquals("冷却缺失时下一段仍应能读到字节", 8, readOnce(streamUrl, 16, 8))
        val third = server.takeRequestOrFail("segment-3")
        assertNotNull(third.getHeader("Range"))
        assertEquals(
            "media 取流路径若具备 429 冷却，第 3 段应被快速失败拦截、不会到达服务器；" +
                "实际服务器收到的请求数说明冷却不存在",
            3,
            server.requestCount,
        )
    }

    @Test
    fun `every range request after a 429 goes out immediately with no per origin suppression`() {
        val streamUrl = server.url("/emby/Videos/g5-burst/stream.mp4").toString()

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        runCatching { readOnce(streamUrl, 0, 4) }
        server.takeRequestOrFail("burst-1-429")

        repeat(4) { index ->
            server.enqueue(
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes ${index * 4}-${index * 4 + 3}/64")
                    .setBody("abcd"),
            )
        }
        repeat(4) { index ->
            assertEquals("第 ${index + 1} 次 206 必须真正读到字节", 4, readOnce(streamUrl, (index + 1) * 4L, 4))
        }

        assertEquals(
            "429 之后连续 4 次同源取流全部真实出网 → 该路径没有任何冷却/抑制",
            5,
            server.requestCount,
        )
    }
}
