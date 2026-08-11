package com.mediahub.player.engine

import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackSource

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
