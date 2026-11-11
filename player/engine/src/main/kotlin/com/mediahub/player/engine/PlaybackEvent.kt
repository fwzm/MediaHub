package com.mediahub.player.engine

import com.mediahub.model.PlaybackProgress

/** The enum remains compatible; production controls also carry their immutable progress. */
sealed interface PlaybackEventMessage {
    val kind: PlaybackEvent
    val progress: PlaybackProgress? get() = null
}

data class PlaybackEventSnapshot(
    override val kind: PlaybackEvent,
    override val progress: PlaybackProgress,
) : PlaybackEventMessage {
    override fun toString(): String = "PlaybackEventSnapshot(kind=$kind)"
}

/** 播放器关键事件（进度同步管线消费，见 ADR-017）。 */
enum class PlaybackEvent : PlaybackEventMessage {
    /** 已暂停（暂停时刻应立刻同步进度） */
    Paused,

    /** 已恢复 */
    Resumed,

    /** 已 Seek */
    Seeked,

    /** 播放结束（应上报完成状态） */
    Ended,

    /** 播放器释放 */
    Stopped;

    override val kind: PlaybackEvent get() = this
}
