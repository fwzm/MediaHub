package com.mediahub.feature.player

/** 播放速度 HUD 的语义状态。 */
enum class PlaybackSpeedStatus { NETWORK, CACHED, BUFFERING, UNKNOWN }

/** HUD 应当显示的标签类别。 */
enum class PlaybackSpeedLabel { RATE, CACHED, BUFFERING, UNKNOWN }

/**
 * 播放速度 HUD 的纯策略。
 *
 * 缓存命中时网络速率为 0，不能据此显示“未知”；只要播放意图仍在且缓冲位置领先
 * 当前位置至少 [CACHED_AHEAD_THRESHOLD_MS]，就说明这一段来自本地缓存。
 */
object PlaybackSpeedPolicy {
    const val CACHED_AHEAD_THRESHOLD_MS: Long = 1_000L

    fun status(
        bytesPerSecond: Long,
        isBuffering: Boolean,
        positionMs: Long,
        bufferedPositionMs: Long,
        playWhenReady: Boolean,
        isEnded: Boolean,
    ): PlaybackSpeedStatus {
        if (bytesPerSecond > 0L) return PlaybackSpeedStatus.NETWORK
        val cachedAhead = bufferedPositionMs - positionMs >= CACHED_AHEAD_THRESHOLD_MS
        if (!isEnded && playWhenReady && cachedAhead) return PlaybackSpeedStatus.CACHED
        if (isBuffering) return PlaybackSpeedStatus.BUFFERING
        return PlaybackSpeedStatus.UNKNOWN
    }

    fun labelKind(bytesPerSecond: Long, status: PlaybackSpeedStatus): PlaybackSpeedLabel = when {
        bytesPerSecond > 0L -> PlaybackSpeedLabel.RATE
        status == PlaybackSpeedStatus.CACHED -> PlaybackSpeedLabel.CACHED
        status == PlaybackSpeedStatus.BUFFERING -> PlaybackSpeedLabel.BUFFERING
        else -> PlaybackSpeedLabel.UNKNOWN
    }

    fun shouldShowBufferingIndicator(isBuffering: Boolean, status: PlaybackSpeedStatus): Boolean =
        isBuffering && status != PlaybackSpeedStatus.CACHED
}
