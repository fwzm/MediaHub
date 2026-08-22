package com.mediahub.player.engine

import android.view.Surface
import androidx.media3.common.text.CueGroup
import com.mediahub.model.PlaybackProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 播放内核种类（U2：Media3 快速路径 / mpv 兼容兜底）。 */
enum class EngineKind { MEDIA3, MPV }

/**
 * Seek 语义（U3-B）：
 * - [PREVIEW]：只移动播放位置，不发 [PlaybackEvent.Seeked]——手势拖动预览、
 *   连续快退的节流 seek 不触发远端即时同步；
 * - [COMMIT]：移动播放位置并发出 [PlaybackEvent.Seeked]，进度同步管线立即 flush。
 */
enum class SeekMode { PREVIEW, COMMIT }

/**
 * 播放引擎能力面（可测性抽象，Phase 1B-2.1；U2 解耦 ExoPlayer）。
 *
 * UI / ViewModel 只依赖本接口，不感知底层是 ExoPlayer 还是 libmpv：
 * - 视频渲染统一走 [attachSurface]（Media3 setVideoSurface / mpv render context）；
 * - 字幕走 [subtitleCues]（Media3 发出 cues；mpv 内部 libass 渲染，恒发 null）。
 * 生产实现：[PlaybackEngine]（Media3）、MpvPlaybackEngine（libmpv）。
 */
interface PlaybackEnginePort {
    val kind: EngineKind
    val uiState: StateFlow<PlaybackUiState>
    /** 每秒进度流（进度同步管线消费，见 ADR-017）。 */
    val progress: SharedFlow<PlaybackProgress>
    /** 关键事件流（Pause/Seek/Ended/Stopped）。 */
    val events: Flow<PlaybackEvent>
    /** 字幕 cues（Media3 引擎发出；mpv 内部渲染，恒发 null）。 */
    val subtitleCues: StateFlow<CueGroup?>
    /** 真实媒体下载速度（B/s，TransferListener 统计；Overlay 展示）。 */
    val downloadSpeedBps: StateFlow<Long>

    /** 绑定/解绑视频渲染 Surface（null 解绑）。 */
    fun attachSurface(surface: Surface?)
    fun play(session: PlaybackSession)
    fun togglePlayPause()
    fun seekTo(positionMs: Long, mode: SeekMode = SeekMode.COMMIT)
    fun setSpeed(speed: Float)
    fun selectAudioTrack(selection: TrackSelection?)
    fun selectSubtitleTrack(selection: TrackSelection?)
    /** 停止并返回最终进度（ADR-023 退出 flush 用）。 */
    fun stop(): PlaybackProgress?
    fun release()
}

/**
 * 引擎工厂接口（可测性抽象，Phase 1B-2.1）。
 * [PlaybackEngineFactory] 是 Media3 生产实现；每次 create 创建独立请求头上下文（ADR-018）。
 */
fun interface PlaybackEngineCreator {
    fun create(scope: CoroutineScope): PlaybackEnginePort
}
