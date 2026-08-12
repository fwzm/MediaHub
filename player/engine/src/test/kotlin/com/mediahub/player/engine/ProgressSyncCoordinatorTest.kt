package com.mediahub.player.engine

import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度同步管线（ADR-017）：
 * - 本地快照 5s 采样、远端上报按 Provider 间隔（默认 10s）；
 * - Pause/Seek/Ended 关键事件立即 flush；
 * - 退出时 final flush 一次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressSyncCoordinatorTest {

    private fun progress(positionMs: Long) = PlaybackProgress(
        serverId = "s1",
        itemId = "i1",
        positionMs = positionMs,
        durationMs = 600_000,
        isPaused = false,
        updatedAtEpochMs = positionMs,
        mode = PlaybackMode.DIRECT_PLAY,
        itemType = MediaType.MOVIE,
    )

    @Test
    fun `local snapshot sampled at 5s and remote at 10s`() = runTest {
        val local = mutableListOf<Long>()
        val remote = mutableListOf<Long>()
        val coordinator = ProgressSyncCoordinator(
            scope = this,
            localSave = { local += it.positionMs },
            remoteReport = { remote += it.positionMs },
        )
        val progress = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 64)
        val events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        coordinator.start(progress, events, remoteIntervalMs = 10_000)
        runCurrent() // 让 collect 协程订阅就绪

        // 模拟 30 秒播放（每秒一个 tick）
        for (i in 1..30) {
            progress.tryEmit(progress(i * 1_000L))
            advanceTimeBy(1_000)
            runCurrent()
        }

        // 5s 采样 → 约 6 次；10s 采样 → 3 次
        assertTrue("local=${local.size}", local.size in 5..7)
        assertEquals(3, remote.size)
        coordinator.stop()
    }

    @Test
    fun `pause event flushes immediately`() = runTest {
        val local = mutableListOf<Long>()
        val remote = mutableListOf<Long>()
        val coordinator = ProgressSyncCoordinator(
            scope = this,
            localSave = { local += it.positionMs },
            remoteReport = { remote += it.positionMs },
        )
        val progress = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 16)
        val events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        coordinator.start(progress, events, remoteIntervalMs = 60_000)
        runCurrent() // 让 collect 协程订阅就绪

        progress.tryEmit(progress(1_000L))
        runCurrent()
        events.tryEmit(PlaybackEvent.Paused)
        runCurrent()

        assertTrue("local=$local", local.contains(1_000L))
        assertTrue("remote=$remote", remote.contains(1_000L))
        coordinator.stop()
    }

    @Test
    fun `final flush sends latest progress once`() = runTest {
        val local = mutableListOf<Long>()
        val remote = mutableListOf<Long>()
        val coordinator = ProgressSyncCoordinator(
            scope = this,
            localSave = { local += it.positionMs },
            remoteReport = { remote += it.positionMs },
        )
        val progress = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 16)
        val events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        coordinator.start(progress, events, remoteIntervalMs = 60_000)
        runCurrent() // 让 collect 协程订阅就绪

        progress.tryEmit(progress(10_000L))
        progress.tryEmit(progress(20_000L))
        runCurrent()
        coordinator.flush()

        assertTrue(local.contains(20_000L))
        assertTrue(remote.contains(20_000L))
        coordinator.stop()
    }
}
