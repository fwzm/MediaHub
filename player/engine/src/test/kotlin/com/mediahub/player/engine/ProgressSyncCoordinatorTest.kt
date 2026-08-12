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
import org.junit.Assert.assertFalse
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
    fun `full exit chain flushes final progress exactly once`() = runTest {
        // 模拟完整退出路径（ADR-023）：
        // 1. 播放中流里最新进度 = 20s；
        // 2. engine.stop() 返回退出瞬间 final = 25s（不经 SharedFlow，取真实 position）；
        // 3. 显式 flush(final) → stop() → release()。
        // 断言：25s 恰好一次本地 + 一次远端；20s 不得作为退出 final 再次上报；
        //       Stopped 事件本身不触发自动 flush。
        val local = mutableListOf<Long>()
        val remote = mutableListOf<Long>()
        val coordinator = ProgressSyncCoordinator(
            scope = this,
            localSave = { local += it.positionMs },
            remoteReport = { remote += it.positionMs },
        )
        val progress = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 16)
        val events = MutableSharedFlow<PlaybackEvent>(
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        coordinator.start(progress, events, remoteIntervalMs = 60_000)
        runCurrent() // 让 collect 协程订阅就绪

        // 播放中的采样进度（20s，仅用于 latest，不应在退出时重复上报）
        progress.tryEmit(progress(20_000L))
        runCurrent()

        // 1) engine.stop()：发出 Stopped（仅状态通知），返回 final = 25s
        val finalProgress = progress(25_000L)
        events.tryEmit(PlaybackEvent.Stopped)
        runCurrent()

        // Stopped 不得触发自动 flush
        assertTrue("local=$local", local.isEmpty())
        assertTrue("remote=$remote", remote.isEmpty())

        // 2) 唯一权威退出路径：显式 flush(finalProgress) → stop
        coordinator.flush(finalProgress)
        coordinator.stop()

        // 3) 25s 恰好一次；20s 未被重复上报
        assertEquals(listOf(25_000L), local)
        assertEquals(listOf(25_000L), remote)
    }

    @Test
    fun `flush with explicit final progress wins over latest`() = runTest {
        val local = mutableListOf<Long>()
        val remote = mutableListOf<Long>()
        val coordinator = ProgressSyncCoordinator(
            scope = this,
            localSave = { local += it.positionMs },
            remoteReport = { remote += it.positionMs },
        )
        val progress = MutableSharedFlow<PlaybackProgress>(extraBufferCapacity = 16)
        val events = MutableSharedFlow<PlaybackEvent>()
        coordinator.start(progress, events, remoteIntervalMs = 60_000)
        runCurrent()

        // 流中最新是 20s，退出瞬间实际是 25s —— flush 显式传入 25s
        progress.tryEmit(progress(20_000L))
        runCurrent()
        coordinator.flush(progress(25_000L))

        assertTrue(local.contains(25_000L))
        assertTrue(remote.contains(25_000L))
        assertFalse(local.contains(20_000L))
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
