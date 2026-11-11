package com.mediahub.player.engine

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.mediahub.core.network.UserAgentPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * G5-b 接线证据：证明 429 退避策略**真的**装在 ExoPlayer 使用的媒体源工厂上，
 * 且它消费的是**生产取流链真实抛出的** 429 异常（含真实 `Retry-After` 头）。
 *
 * 两条断言互补：
 * 1. 接线：`PlayerFactory.mediaSourceFactory()` 内部的 `DefaultMediaSourceFactory`
 *    上的 `loadErrorHandlingPolicy` 是注入的同一个策略实例（不是死接线）；
 * 2. 数据面：生产链（[PlayerFactory.mediaDataSourceFactory]）遇到 429 时抛出的
 *    `InvalidResponseCodeException` 能被该策略解析出有界退避。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Media3RateLimitBackoffWiringTest {

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

    private fun readOnce(url: String): Throwable? {
        val source: DataSource =
            PlayerFactory(
                RuntimeEnvironment.getApplication(),
                cacheProvider,
                userAgentPolicy = UserAgentPolicy(),
            ).mediaDataSourceFactory(PlaybackHeadersHolder(), PlaybackSpeedMonitor())
                .createDataSource()
        return try {
            source.open(DataSpec(Uri.parse(url), 0L, 4L))
            null
        } catch (error: Exception) {
            error
        } finally {
            runCatching { source.close() }
        }
    }

    @Test
    fun `the media source factory is wired with the injected backoff policy`() {
        val sentinel = object : LoadErrorHandlingPolicy {
            override fun getFallbackSelectionFor(
                fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
                loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
            ) = null

            override fun getRetryDelayMsFor(
                loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
            ): Long = 1L

            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 1
        }
        val factory = PlayerFactory(
            RuntimeEnvironment.getApplication(),
            cacheProvider,
            loadErrorHandlingPolicy = sentinel,
        ).mediaSourceFactory(PlaybackHeadersHolder(), PlaybackSpeedMonitor())

        assertTrue("媒体源工厂必须仍是快照包装器", factory is SnapshotMediaSourceFactory)
        val delegateField = SnapshotMediaSourceFactory::class.java.getDeclaredField("delegate")
        delegateField.isAccessible = true
        val delegate = delegateField.get(factory) as DefaultMediaSourceFactory

        val policyField = DefaultMediaSourceFactory::class.java
            .getDeclaredField("loadErrorHandlingPolicy")
        policyField.isAccessible = true
        assertSame(
            "PlayerFactory.mediaSourceFactory 必须把注入策略装进 ExoPlayer 真正使用的媒体源工厂",
            sentinel,
            policyField.get(delegate),
        )
    }

    @Test
    fun `a real 429 from the production chain is parsed by the backoff policy`() {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "60")
                .setBody("too many requests"),
        )

        val error = readOnce(server.url("/emby/Videos/g5b/stream.mp4").toString())
        assertNotNull("429 必须从生产取流链上抛", error)
        val httpError = generateSequence(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .first()
        assertEquals(429, httpError.responseCode)
        assertTrue(
            "生产链的真实 429 必须到达服务器",
            server.takeRequest(5, TimeUnit.SECONDS) != null,
        )

        val policy = Media3RateLimitBackoffPolicy()
        val info = LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(0L, DataSpec(Uri.parse("https://media.invalid/stream.mp4")), 0L),
            MediaLoadData(C.DATA_TYPE_MEDIA),
            httpError,
            1,
        )
        assertEquals(
            "真实 Retry-After: 60 必须被钳到有界退避，而不是 60s 空等",
            Media3RateLimitBackoffPolicy.DEFAULT_MAX_DELAY_MS,
            policy.getRetryDelayMsFor(info),
        )
        assertEquals(
            "重试预算用尽后必须停止重试",
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(
                LoadErrorHandlingPolicy.LoadErrorInfo(
                    LoadEventInfo(0L, DataSpec(Uri.parse("https://media.invalid/stream.mp4")), 0L),
                    MediaLoadData(C.DATA_TYPE_MEDIA),
                    httpError,
                    2,
                ),
            ),
        )
    }
}
