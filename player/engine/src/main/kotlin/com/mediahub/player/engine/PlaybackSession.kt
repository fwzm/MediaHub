package com.mediahub.player.engine

import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackSource
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackProgressReason

/** 一次播放会话：条目 + 播放源 + 起播位置。 */
data class PlaybackSession(
    val serverId: String,
    val itemId: String,
    val itemTitle: String,
    val source: PlaybackSource,
    val startPositionMs: Long? = null,
    val resumePositionMs: Long? = null,
    val itemType: MediaType? = null,
)

data class PlaybackProgressEvent(
    val progress: PlaybackProgress,
    val reason: PlaybackProgressReason,
)
