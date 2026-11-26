package com.mediahub.player.engine

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * G5-b · Media3 取流路径的 429 退避策略（LoadErrorHandlingPolicy）。
 *
 * 背景（ADR-046 已知局限）：生产取流链对 429 **只记录不抑制**，重试节奏仍由
 * Media3 默认策略决定 —— 它不解析 `Retry-After`，会在窗口内按 1s/2s/4s
 * 连续重打，构成重试风暴。
 *
 * 本测试固定新策略的契约：
 * 1. 429 且带 `Retry-After` → 退避取该值，但**有上限**（不得无限等待）；
 * 2. 429 且无/非法 `Retry-After` → 使用保守默认退避；
 * 3. 429 重试**有次数上限**，超过后返回 `C.TIME_UNSET`（停止重试，交给既有错误路径）；
 * 4. 非 429（404/416/5xx/普通 IOException）→ **逐字节委托**默认策略，行为不变。
 *
 * 说明：`C.TIME_UNSET` 是 Media3 "不再重试"的权威返回值；本策略只影响
 * **加载失败后的重试节奏**，不触碰已建立的数据流，因此不会中断正在播放的流。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Media3RateLimitBackoffPolicyTest {

    private fun invalidResponse(
        code: Int,
        headers: Map<String, List<String>> = emptyMap(),
    ): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            code,
            "response $code",
            null,
            headers,
            DataSpec(Uri.parse("https://media.invalid/stream.mp4")),
            ByteArray(0),
        )

    private fun loadError(
        exception: IOException,
        errorCount: Int,
    ): LoadErrorHandlingPolicy.LoadErrorInfo = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(0L, DataSpec(Uri.parse("https://media.invalid/stream.mp4")), 0L),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        exception,
        errorCount,
    )

    /** 记录调用次数并返回固定值的委托，用于证明"非 429 完全交给默认策略"。 */
    private class RecordingDelegate(private val delayMs: Long) : LoadErrorHandlingPolicy {
        var calls = 0

        override fun getFallbackSelectionFor(
            fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
        ) = null

        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            calls++
            return delayMs
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 1
    }

    private fun policy(
        maxAttempts: Int = Media3RateLimitBackoffPolicy.DEFAULT_MAX_ATTEMPTS,
        maxDelayMs: Long = Media3RateLimitBackoffPolicy.DEFAULT_MAX_DELAY_MS,
        defaultDelayMs: Long = Media3RateLimitBackoffPolicy.DEFAULT_DELAY_MS,
        delegate: LoadErrorHandlingPolicy = RecordingDelegate(delayMs = 1234L),
        maxParserAttempts: Int = Media3RateLimitBackoffPolicy.DEFAULT_MAX_PARSER_ATTEMPTS,
    ) = Media3RateLimitBackoffPolicy(
        maxAttempts = maxAttempts,
        maxDelayMs = maxDelayMs,
        defaultDelayMs = defaultDelayMs,
        delegate = delegate,
        maxParserAttempts = maxParserAttempts,
    )

    @Test
    fun `a 429 with retry after above the cap is clamped instead of stalling forever`() {
        val delay = policy().getRetryDelayMsFor(
            loadError(invalidResponse(429, mapOf("Retry-After" to listOf("60"))), errorCount = 1),
        )
        assertEquals(Media3RateLimitBackoffPolicy.DEFAULT_MAX_DELAY_MS, delay)
    }

    @Test
    fun `a 429 with a short retry after is honored exactly`() {
        val delay = policy().getRetryDelayMsFor(
            loadError(invalidResponse(429, mapOf("Retry-After" to listOf("3"))), errorCount = 1),
        )
        assertEquals(3_000L, delay)
    }

    @Test
    fun `a 429 without retry after uses the conservative default delay`() {
        val delay = policy().getRetryDelayMsFor(loadError(invalidResponse(429), errorCount = 1))
        assertEquals(Media3RateLimitBackoffPolicy.DEFAULT_DELAY_MS, delay)
    }

    @Test
    fun `a 429 with an unparsable retry after uses the conservative default delay`() {
        val delay = policy().getRetryDelayMsFor(
            loadError(
                invalidResponse(429, mapOf("Retry-After" to listOf("not-a-delay"))),
                errorCount = 1,
            ),
        )
        assertEquals(Media3RateLimitBackoffPolicy.DEFAULT_DELAY_MS, delay)
    }

    @Test
    fun `retry after lookup is case insensitive`() {
        val delay = policy().getRetryDelayMsFor(
            loadError(invalidResponse(429, mapOf("retry-after" to listOf("2"))), errorCount = 1),
        )
        assertEquals(2_000L, delay)
    }

    @Test
    fun `a 429 stops retrying once the attempt budget is exhausted`() {
        val subject = policy(maxAttempts = 2)
        val exception = invalidResponse(429, mapOf("Retry-After" to listOf("60")))

        assertEquals(
            "第一次 429 仍应退避重试",
            Media3RateLimitBackoffPolicy.DEFAULT_MAX_DELAY_MS,
            subject.getRetryDelayMsFor(loadError(exception, errorCount = 1)),
        )
        assertEquals(
            "超过重试预算必须返回 C.TIME_UNSET，停止重试风暴",
            C.TIME_UNSET,
            subject.getRetryDelayMsFor(loadError(exception, errorCount = 2)),
        )
        assertEquals(
            "更晚的错误同样不得再重试",
            C.TIME_UNSET,
            subject.getRetryDelayMsFor(loadError(exception, errorCount = 9)),
        )
    }

    @Test
    fun `non 429 responses keep the default policy delay byte for byte`() {
        val delegate = RecordingDelegate(delayMs = 4321L)
        val subject = policy(delegate = delegate)

        assertEquals(4321L, subject.getRetryDelayMsFor(loadError(invalidResponse(404), 1)))
        assertEquals(4321L, subject.getRetryDelayMsFor(loadError(invalidResponse(416), 1)))
        assertEquals(4321L, subject.getRetryDelayMsFor(loadError(invalidResponse(500), 1)))
        assertEquals(4321L, subject.getRetryDelayMsFor(loadError(invalidResponse(503), 3)))
        assertEquals(4, delegate.calls)
    }

    @Test
    fun `a plain io failure keeps the default policy delay`() {
        val delegate = RecordingDelegate(delayMs = 777L)
        val subject = policy(delegate = delegate)

        assertEquals(777L, subject.getRetryDelayMsFor(loadError(IOException("socket reset"), 1)))
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `the 429 budget never consumes more than the configured attempts`() {
        val subject = policy(maxAttempts = 1)
        val exception = invalidResponse(429, mapOf("Retry-After" to listOf("1")))

        assertEquals(
            "maxAttempts=1 表示一次重试机会都不给",
            C.TIME_UNSET,
            subject.getRetryDelayMsFor(loadError(exception, errorCount = 1)),
        )
    }

    @Test
    fun `an oversized retry after never overflows into an immediate retry`() {
        val delay = policy().getRetryDelayMsFor(
            loadError(
                invalidResponse(429, mapOf("Retry-After" to listOf(Long.MAX_VALUE.toString()))),
                errorCount = 1,
            ),
        )
        assertTrue("必须被钳到上限，而不是溢出成 0/负值", delay == Media3RateLimitBackoffPolicy.DEFAULT_MAX_DELAY_MS)
    }

    // ---- 条目 136：EXO 解析错误的重试上限 ----

    private fun parserFailure() = ParserException.createForMalformedManifest("bad playlist", null)

    @Test
    fun `parser failures keep the delegate cadence only within the parser budget`() {
        val delegate = RecordingDelegate(delayMs = 1_500L)
        val subject = policy(delegate = delegate, maxParserAttempts = 2)
        val failure = parserFailure()

        assertEquals(
            "预算内的首次失败仍按委托策略退避",
            1_500L,
            subject.getRetryDelayMsFor(loadError(failure, errorCount = 1)),
        )
        assertEquals(
            "解析错误超出预算必须停止重试，避免反复拉同一份内容",
            C.TIME_UNSET,
            subject.getRetryDelayMsFor(loadError(failure, errorCount = 2)),
        )
        assertEquals(
            "更晚的解析错误同样不得再触发重试",
            C.TIME_UNSET,
            subject.getRetryDelayMsFor(loadError(failure, errorCount = 7)),
        )
        assertEquals("预算内只询问委托一次", 1, delegate.calls)
    }

    @Test
    fun `a parser failure wrapped as a cause is still capped`() {
        val subject = policy(maxParserAttempts = 2)
        val wrapped = IOException("manifest load failed", parserFailure())

        assertEquals(
            C.TIME_UNSET,
            subject.getRetryDelayMsFor(loadError(wrapped, errorCount = 2)),
        )
    }

    @Test
    fun `parser budget is independent from the 429 budget`() {
        val subject = policy(maxAttempts = 2, maxParserAttempts = 2)

        assertEquals(
            "解析错误在 errorCount=1 仍可重试",
            1_234L,
            subject.getRetryDelayMsFor(loadError(parserFailure(), errorCount = 1)),
        )
        assertEquals(
            "429 的预算不受解析错误影响",
            Media3RateLimitBackoffPolicy.DEFAULT_DELAY_MS,
            subject.getRetryDelayMsFor(loadError(invalidResponse(429), errorCount = 1)),
        )
    }
}
