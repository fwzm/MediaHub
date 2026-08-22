package com.mediahub.player.engine

import com.mediahub.model.PlaybackProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放引擎能力面（可测性抽象，Phase 1B-2.1）。
 *
 * 只包含 ViewModel / UI 实际使用的最小成员集合；[PlaybackEngine] 是唯一生产实现。
 * 测试可用内存 fake 验证\"PlaybackSource 最终到达 engine.play\"，不测试 Media3 内部。
 */
interface PlaybackEnginePort {
    val uiState: StateFlow<PlaybackUiState>
    /** 每秒进度流（进度同步管线消费，见 ADR-017）。 */
    val progress: SharedFlow<PlaybackProgress>
    /** 关键事件流（Pause/Seek/Ended/Stopped）。 */
    val events: Flow<PlaybackEvent>
    /** 底层 Media3 播放器（仅 UI 渲染 PlayerView 用）。 */
    val exoPlayer: androidx.media3.exoplayer.ExoPlayer
    /** 真实媒体下载速度（B/s，TransferListener 统计；Overlay 展示）。 */
    val downloadSpeedBps: StateFlow<Long>

    fun play(session: PlaybackSession)
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun selectAudioTrack(selection: TrackSelection?)
    fun selectSubtitleTrack(selection: TrackSelection?)
    /** 停止并返回最终进度（ADR-023 退出 flush 用）。 */
    fun stop(): PlaybackProgress?
    fun release()
}

/**
 * 引擎工厂接口（可测性抽象，Phase 1B-2.1）。
 * [PlaybackEngineFactory] 是唯一生产实现；每次 create 创建独立请求头上下文（ADR-018）。
 */
fun interface PlaybackEngineCreator {
    fun create(scope: CoroutineScope): PlaybackEnginePort
}
