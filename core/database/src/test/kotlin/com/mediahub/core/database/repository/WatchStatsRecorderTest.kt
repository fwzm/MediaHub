package com.mediahub.core.database.repository

import com.mediahub.model.WatchSession
import com.mediahub.model.WatchStatsOverview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目 141/139：观影会话累加器。
 *
 * 覆盖"观看时长偏少"修复口径：
 * - 正常播放按内容增量累计；
 * - 回拖/回看继续累计（旧口径按最后位置计算会漏算）；
 * - 大跨度前跳按墙钟封顶，不会把一次拖动计成看完；
 * - 暂停/缓冲/关闭开关不产生记录。
 */
class WatchStatsRecorderTest {

    private class FakeStore : WatchStatsStore {
        val recorded = mutableListOf<WatchSession>()
        private val history = MutableStateFlow<List<WatchSession>>(emptyList())

        override fun observeOverview(trendDays: Int): Flow<WatchStatsOverview> =
            flowOf(WatchStatsOverview.Empty)

        override fun observeHistory(limit: Int): Flow<List<WatchSession>> = history

        override suspend fun record(session: WatchSession) {
            recorded += session
            history.value = history.value + session
        }

        override suspend fun clearAll() {
            recorded.clear()
            history.value = emptyList()
        }
    }

    private var nowMs = 1_000_000L
    private val store = FakeStore()
    private val recorder = DefaultWatchStatsRecorder(store) { nowMs }

    private fun sample(
        positionMs: Long,
        isPlaying: Boolean = true,
        durationMs: Long = 1_500_000L,
        speed: Float = 1f,
        itemId: String = "ep-1",
        itemType: String = "EPISODE",
    ) {
        recorder.onSample(
            serverId = "srv-1",
            itemId = itemId,
            itemTitle = "测试剧集",
            itemType = itemType,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            speed = speed,
        )
    }

    @Test
    fun `normal playback accumulates consumed time`() = runBlocking {
        sample(0)
        repeat(10) { index ->
            nowMs += 1_000
            sample((index + 1) * 1_000L)
        }
        recorder.finish()

        assertEquals(1, store.recorded.size)
        assertEquals(10_000L, store.recorded.single().watchedMs)
    }

    @Test
    fun `rewatch after seeking back is still counted`() = runBlocking {
        sample(0)
        repeat(10) { index ->
            nowMs += 1_000
            sample((index + 1) * 1_000L)
        }
        // 回拖到 2s 后继续看 5s：旧口径按最后位置只会得到 10s，这里应得到 15s。
        sample(2_000)
        repeat(5) { index ->
            nowMs += 1_000
            sample(2_000 + (index + 1) * 1_000L)
        }
        recorder.finish()

        assertEquals(15_000L, store.recorded.single().watchedMs)
    }

    @Test
    fun `long forward seek is capped by wall clock instead of inflating watch time`() = runBlocking {
        sample(0)
        nowMs += 1_000
        sample(600_000) // 一次跳到 10 分钟处
        recorder.finish()

        assertTrue(store.recorded.single().watchedMs < 10_000L)
    }

    @Test
    fun `paused samples do not add watch time`() = runBlocking {
        sample(0)
        nowMs += 1_000
        sample(1_000)
        repeat(10) {
            nowMs += 1_000
            sample(1_000, isPlaying = false)
        }
        recorder.finish()

        assertEquals(1_000L, store.recorded.single().watchedMs)
    }

    @Test
    fun `disabled recorder ignores samples and keeps no records`() = runBlocking {
        recorder.setEnabled(false)
        sample(0)
        nowMs += 5_000
        sample(5_000)
        recorder.finish()

        assertTrue(store.recorded.isEmpty())
    }

    @Test
    fun `session shorter than one second is discarded`() = runBlocking {
        sample(0)
        nowMs += 500
        sample(500)
        recorder.finish()

        assertTrue(store.recorded.isEmpty())
    }

    @Test
    fun `checkpoint and finish share the same session id`() = runBlocking {
        sample(0)
        repeat(3) { index ->
            nowMs += 1_000
            sample((index + 1) * 1_000L)
        }
        recorder.checkpoint()
        nowMs += 1_000
        sample(4_000)
        recorder.finish()

        assertEquals(2, store.recorded.size)
        assertEquals(store.recorded[0].id, store.recorded[1].id)
        assertTrue(store.recorded[1].watchedMs > store.recorded[0].watchedMs)
    }

    @Test
    fun `completed is marked when position reaches the tail`() = runBlocking {
        sample(0, durationMs = 100_000)
        nowMs += 1_000
        sample(99_000, durationMs = 100_000)
        recorder.finish()

        assertTrue(store.recorded.single().completed)
    }
}
