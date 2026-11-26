package com.mediahub.player.engine

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.TimeUnit

/**
 * 外挂字幕取流鉴权回归（用户条目 107 / 审计 81）。
 *
 * Media3 的 sideloaded subtitle 与视频流共用 ExoPlayer 的 MediaSource 工厂，因此
 * **生产链** `PlayerFactory.mediaDataSourceFactory(headersHolder)` 必须把本轮鉴权头
 * 真实带到 `/Videos/{id}/{source}/Subtitles/{index}/Stream.{format}` 上。
 * 本测试驱动同一条链（不是替身），断言 `X-Emby-Token` 实际到达服务端。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubtitleStreamCredentialDeliveryTest {

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

    @Test
    fun `sideloaded subtitle request carries the emby token through the production data source`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/x-subrip")
                .setBody("1\n00:00:01,000 --> 00:00:02,000\n你好\n"),
        )
        val holder = PlaybackHeadersHolder().apply {
            setHeaders(mapOf("X-Emby-Token" to "tok-sub", "X-Emby-Authorization" to "MediaBrowser Token=\"tok-sub\""))
        }
        val factory = PlayerFactory(RuntimeEnvironment.getApplication(), cacheProvider)
            .mediaDataSourceFactory(holder, PlaybackSpeedMonitor())

        val source = factory.createDataSource()
        try {
            source.open(
                DataSpec(
                    Uri.parse(
                        server.url("/emby/Videos/m1/src1/Subtitles/2/Stream.srt").toString()
                    )
                )
            )
            val buffer = ByteArray(8)
            assertTrue("外挂字幕必须真正读到字节", source.read(buffer, 0, buffer.size) > 0)
        } finally {
            source.close()
        }

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("5s 内没有字幕请求到达服务端", request)
        assertEquals(
            "外挂字幕请求必须携带本轮鉴权头",
            "tok-sub",
            request!!.getHeader("X-Emby-Token"),
        )
        assertTrue("必须是官方字幕端点", request.path!!.contains("/Subtitles/2/Stream.srt"))
    }
}
