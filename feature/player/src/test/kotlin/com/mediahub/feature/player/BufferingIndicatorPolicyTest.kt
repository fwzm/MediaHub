package com.mediahub.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 条目 19：缓存区间 seek 不应误报网络加载态。 */
class BufferingIndicatorPolicyTest {
    @Test
    fun `cache reads never show the network buffering indicator`() {
        assertFalse(BufferingIndicatorPolicy.shouldShow(isBuffering = true, isReadingFromCache = true, bufferingAgeMs = 0))
        assertFalse(
            BufferingIndicatorPolicy.shouldShow(
                isBuffering = true,
                isReadingFromCache = true,
                bufferingAgeMs = BufferingIndicatorPolicy.GRACE_MS * 10,
            ),
        )
    }

    @Test
    fun `instant buffering during a cached seek stays hidden`() {
        assertFalse(BufferingIndicatorPolicy.shouldShow(isBuffering = true, isReadingFromCache = false, bufferingAgeMs = 80))
        assertFalse(
            BufferingIndicatorPolicy.shouldShow(
                isBuffering = true,
                isReadingFromCache = false,
                bufferingAgeMs = BufferingIndicatorPolicy.GRACE_MS - 1,
            ),
        )
    }

    @Test
    fun `sustained network buffering is shown after the grace period`() {
        assertTrue(
            BufferingIndicatorPolicy.shouldShow(
                isBuffering = true,
                isReadingFromCache = false,
                bufferingAgeMs = BufferingIndicatorPolicy.GRACE_MS,
            ),
        )
    }

    @Test
    fun `not buffering never shows the indicator`() {
        assertFalse(BufferingIndicatorPolicy.shouldShow(isBuffering = false, isReadingFromCache = false, bufferingAgeMs = 10_000))
    }
}
