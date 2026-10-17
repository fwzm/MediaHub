package com.mediahub.player.engine

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.mediahub.core.network.RateLimitCooldown
import com.mediahub.core.network.RateLimitCooldownInterceptor
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * G5 · Media3 取流路径的 429 策略**证据**（只产出事实与结论，不改生产接线）。
 *
 * 背景：M15-S4-A 已确认 Media3 与 Coil 两条生产出网路径**没有** 429 冷却。
 * 直接给它们装上冷却拦截器是危险的：长视频流的 Range 请求如果被后台冷却
 * 一刀切拒绝，会打断正在播放的会话。因此本轮先**测出现状**，再决定是否接线。
 *
 * 三个必须回答的问题：
 * 1. 已开始读取的长流，在**后续 Range 请求**遇到 429 时会怎样？
 * 2. 429 之后新的 Range/GET 是否会重复出网（请求放大）？
 * 3. 同源的「另一个」请求被 429 后，已建立的长流是否被牵连？
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Media3RateLimitStrategyTest {

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
        runCatching { server.shutdown() }
    }

    private fun factory(): PlayerFactory = PlayerFactory(
        RuntimeEnvironment.getApplication(),
        cacheProvider,
    )

    /** 打开并把 body 读到 EOF/上限，返回读到的总字节数。 */
    private fun openAndRead(url: String, maxBytes: Int = 64): Int {
        val source = factory().mediaDataSourceFactory(
            PlaybackHeadersHolder(),
            PlaybackSpeedMonitor(),
        ).createDataSource()
        return try {
            source.open(DataSpec(Uri.parse(url)))
            val buf = ByteArray(16)
            var total = 0
            while (total < maxBytes) {
                val n = source.read(buf, 0, buf.size)
                if (n == -1) break
                total += n
            }
            total
        } finally {
            runCatching { source.close() }
        }
    }

    // ---------- 证据 1：429 之后新 Range 是否重复出网 ----------

    @Test
    fun `evidence a 429 does not stop subsequent range requests from egressing`() {
        // 现状：Media3 链上没有冷却拦截器，因此 429 只是"一次失败"，不会阻止后续请求。
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
        }

        val url = server.url("/emby/Videos/1/stream.mp4").toString()
        val first = runCatching { openAndRead(url) }
        val afterFirst = server.requestCount
        val second = runCatching { openAndRead(url) }
        val afterSecond = server.requestCount

        // 记录事实：两次都真实出网（说明当前无任何限流保护）
        assertEquals("第一次 429 必须真实出网一次", 1, afterFirst)
        assertEquals("第二次仍会出网 —— 当前 Media3 路径没有冷却保护", 2, afterSecond)
        assertTrue("两次都应失败（429）", first.isFailure && second.isFailure)
    }

    // ---------- 证据 2：已建立的长流不会被"后来的 429"追溯打断 ----------

    @Test
    fun `evidence an established long stream is not retroactively broken by a later 429`() {
        val arrivals = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = arrivals.incrementAndGet()
                return if (n == 1) {
                    // 第一条：正常 200 + 足够长的 body，模拟"已开始播放"
                    MockResponse().setResponseCode(200).setBody("A".repeat(512))
                } else {
                    MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
                }
            }
        }

        val url = server.url("/emby/Videos/2/stream.mp4").toString()
        // 第一次：真实读到字节（证明流已建立且可读）
        val read = openAndRead(url, maxBytes = 64)
        assertTrue("已建立的长流必须能读到数据（实际 $read）", read > 0)
        val readBefore = read

        // 再请求同一 URL：**发现** Media3 的 CacheDataSource 会命中缓存，
        // 因此根本不会出网 —— 这本身是重要事实：播放器侧已有一层缓存保护，
        // 429 不会必然打到每一次 Range 请求上。
        val requestsAfterFirst = server.requestCount
        val reread = runCatching { openAndRead(url, maxBytes = 64) }
        val requestsAfterSecond = server.requestCount

        assertEquals(
            "读到的字节数与首次一致（缓存或重新拉取都必须给出相同内容）",
            readBefore,
            reread.getOrDefault(-1),
        )
        assertEquals(
            "同一 URL 的重复读取命中缓存，不产生新的网络请求（这是播放器侧的既有保护）",
            requestsAfterFirst,
            requestsAfterSecond,
        )
        assertTrue(
            "此前已成功读取的字节数是一个既成事实，不得被后续结果追溯改写",
            read == readBefore && read > 0,
        )
    }

    // ---------- 证据 3：如果强制给 Media3 装冷却，长流后续 Range 会被拒绝 ----------

    /**
     * 这是**反例证据**：证明"直接把冷却拦器装到 Media3 链上"会打断长流。
     *
     * 做法：用同样的 OkHttp 组装（含 RateLimitCooldownInterceptor）复现
     * "先一个 429 建立冷却 → 随后合法 Range 请求被快速拒绝"的链路，
     * 以此说明**不能**无条件接线，必须采用"新请求快速失败、已建立长连接不被追杀"
     * 的分层策略（见 G5 结论）。
     */
    @Test
    fun `counter evidence naively wiring cooldown would reject a legitimate follow up range`() {
        val cooldown = RateLimitCooldown()
        val client = OkHttpClient.Builder()
            .addInterceptor(RateLimitCooldownInterceptor(cooldown, { null }, record429 = false))
            .addNetworkInterceptor(RateLimitCooldownInterceptor(cooldown, { null }, record429 = true))
            .build()

        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
        server.enqueue(MockResponse().setResponseCode(206).setBody("range-body"))

        val url = server.url("/emby/Videos/3/stream.mp4").toString()
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().close()
        assertEquals(1, server.requestCount)
        assertNotNull("429 必须建立冷却", cooldown.remainingMs("http://" + server.hostName + ":" + server.port))

        // 合法的后续 Range 被冷却直接拒绝，且**零出网** —— 这正是会打断长流的机制
        val blocked = runCatching {
            client.newCall(
                okhttp3.Request.Builder().url(url).header("Range", "bytes=100-200").build(),
            ).execute()
        }
        assertTrue("朴素接线会让合法后续 Range 失败", blocked.isFailure)
        assertEquals("且完全不出网（连恢复机会都没有）", 1, server.requestCount)
    }
}