package com.mediahub.player.engine

import com.mediahub.model.PlaybackProgress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 进度同步协调器（ADR-017）：把"每秒一次"的进度流拆成三档，避免写放大与请求放大。
 *
 * - UI 进度：由 PlaybackEngine.uiState 驱动（内存，无 IO）。
 * - 本地快照：progress.sample([localIntervalMs])，默认 5s（conflate 语义）。
 * - 远端上报：progress.sample([remoteIntervalMs])，由 Provider 策略决定（默认 10s）。
 * - 关键事件（Pause / Seek / Ended）：立即 flush 一次（latest）。
 * - [flush]：播放器退出时 final flush（ADR-023）；远端上报带短超时，
 *   保证退出不被慢网络阻塞。
 * - Stopped 事件：**仅状态通知，不触发自动 flush**——退出 final flush 的唯一权威
 *   路径是调用方显式执行 engine.stop() → flush(finalProgress)（ADR-023），
 *   避免一次退出产生两次本地写入与两次远端上报。
 *
 * 纯 Kotlin，可单测（kotlinx-coroutines-test virtual time）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressSyncCoordinator(
    private val scope: CoroutineScope,
    private val localSave: suspend (PlaybackProgress) -> Unit,
    private val remoteReport: suspend (PlaybackProgress) -> Unit,
    private val localIntervalMs: Long = DEFAULT_LOCAL_INTERVAL_MS,
) {
    private val latest = AtomicReference<PlaybackProgress?>(null)
    private var job: Job? = null

    fun start(
        progress: Flow<PlaybackProgress>,
        events: Flow<PlaybackEvent>,
        remoteIntervalMs: Long = DEFAULT_REMOTE_INTERVAL_MS,
    ) {
        if (job != null) return
        job = scope.launch {
            // UNDISPATCHED：在启动线程立即执行到挂起点，保证订阅在 start() 返回后即就绪，
            // 避免 tryEmit 因订阅未建立而丢值（对测试与真实启动都更确定）。
            launch(start = CoroutineStart.UNDISPATCHED) {
                progress.sample(localIntervalMs).collectLatest { runCatching { localSave(it) } }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                progress.sample(remoteIntervalMs).collectLatest { runCatching { remoteReport(it) } }
            }
            launch(start = CoroutineStart.UNDISPATCHED) { progress.collect { latest.set(it) } }
            launch(start = CoroutineStart.UNDISPATCHED) {
                events.collect { event ->
                    // Pause/Seek/Ended 立即同步；Stopped 仅状态通知（退出 flush 由 stopAndFlush 显式执行）
                    if (event == PlaybackEvent.Paused || event == PlaybackEvent.Seeked || event == PlaybackEvent.Ended) {
                        flush()
                    }
                }
            }
        }
    }

    /**
     * final flush：播放器退出 / 页面离开时调用一次。
     *
     * - [progress]：可显式传入最终进度（退出瞬间取值），缺省用 latest（流中最近值）。
     * - localSave 与 remoteReport 均防崩溃；远端上报带短超时，避免阻塞退出。
     */
    suspend fun flush(progress: PlaybackProgress? = null) {
        val p = progress ?: latest.get() ?: return
        runCatching { localSave(p) }
        runCatching { withTimeoutOrNull(REMOTE_FLUSH_TIMEOUT_MS) { remoteReport(p) } }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val DEFAULT_LOCAL_INTERVAL_MS = 5_000L
        const val DEFAULT_REMOTE_INTERVAL_MS = 10_000L

        /** 退出时远端 final report 的短超时（不阻塞退出/返回）。 */
        const val REMOTE_FLUSH_TIMEOUT_MS = 2_000L
    }
}
