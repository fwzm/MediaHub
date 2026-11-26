package com.mediahub.player.engine

import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackSource
import com.mediahub.model.ResumeSeekMode

/** 一次播放会话：条目 + 播放源 + 起播位置。 */
data class PlaybackSession(
    val serverId: String,
    val itemId: String,
    val itemTitle: String,
    val source: PlaybackSource,
    val startPositionMs: Long? = null,
    val resumePositionMs: Long? = null,
    val itemType: MediaType? = null,
    /** 展示用快照：继续观看卡片的缩略图（Phase 1B-2.3，无图时 UI 走占位）。 */
    val posterUrl: String? = null,
    val trace: PlaybackStartupTrace? = null,
    /**
     * 起播时是否应处于「暂停」态（G3 修复）。
     *
     * 背景：引擎自动降级（Media3 失败 → mpv 同位置重播）此前**不携带暂停意图**，
     * 于是「用户已暂停 → 发生降级 → 新引擎无条件起播」会把暂停的会话重新播起来。
     * 默认 null = 沿用各引擎既有行为（起播即播放），因此对现有调用方零行为变化；
     * 降级路径显式把旧引擎的暂停态带过来。
     */
    val startPaused: Boolean? = null,
    /**
     * 续播定位策略（条目 124）：换源/换集/续播时的起播 seek 精度。
     * 默认 [ResumeSeekMode.KEYFRAME] 以邻近关键帧快速起播；EXACT 按精确时间定位。
     */
    val resumeSeekMode: ResumeSeekMode = ResumeSeekMode.KEYFRAME,
)
