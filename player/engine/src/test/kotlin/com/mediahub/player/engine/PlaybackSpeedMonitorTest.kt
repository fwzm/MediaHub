package com.mediahub.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 条目 19：缓存命中 seek 的读取速率与空闲衰减。 */
class PlaybackSpeedMonitorTest {
    private var now = 1_000L
    private val monitor = PlaybackSpeedMonitor(nowMs = { now })

    @Test
    fun `cached bytes mark cache reading immediately`() {
        monitor.onCachedBytesRead(cacheSizeBytes = 10_000L, cachedBytesRead = 2_048L)

        assertTrue(monitor.isReadingFromCache.value)
    }

    @Test
    fun `cached bytes over the window produce a cache read rate`() {
        monitor.onCachedBytesRead(cacheSizeBytes = 10_000L, cachedBytesRead = 1_000L)
        now += 1_000L
        monitor.onCachedBytesRead(cacheSizeBytes = 10_000L, cachedBytesRead = 3_000L)

        assertEquals(3_000L, monitor.cacheBytesPerSecond.value)
        assertTrue(monitor.cacheBytesPerSecond.value > 0L)
    }

    @Test
    fun `idle tick clears cache rate and reading flag`() {
        monitor.onCachedBytesRead(cacheSizeBytes = 10_000L, cachedBytesRead = 1_000L)
        assertTrue(monitor.isReadingFromCache.value)

        now += 1_500L
        monitor.tick()

        assertEquals(0L, monitor.cacheBytesPerSecond.value)
        assertFalse(monitor.isReadingFromCache.value)
    }

    @Test
    fun `reset clears cache state between sessions`() {
        monitor.onCachedBytesRead(cacheSizeBytes = 10_000L, cachedBytesRead = 1_000L)
        monitor.reset()

        assertEquals(0L, monitor.cacheBytesPerSecond.value)
        assertFalse(monitor.isReadingFromCache.value)
    }

    @Test
    fun `cumulative cached byte counter does not double count`() {
        monitor.onCachedBytesRead(cacheSizeBytes = 10_000L, cachedBytesRead = 1_000L)
        monitor.onCachedBytesRead(cacheSizeBytes = 10_000L, cachedBytesRead = 1_000L)

        now += 1_100L
        monitor.onCachedBytesRead(cacheSizeBytes = 10_000L, cachedBytesRead = 1_000L)
        // 仍无新增字节：速率保持 0，不应因累计值重复而虚高。
        assertEquals(0L, monitor.cacheBytesPerSecond.value)
    }
}
