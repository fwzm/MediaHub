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
     * 首选音频语言（已收敛为语言子标签；空 = 无偏好，沿用系统 Locale 策略）。
     * 由 ViewModel 从用户偏好注入，见 [TrackPreferencePolicy]。
     */
    val preferredAudioLanguages: List<String> = emptyList(),
    /**
     * 首选字幕语言（空 = 不自动开启字幕）。匹配不到时引擎必须保持字幕关闭。
     */
    val preferredTextLanguages: List<String> = emptyList(),
)
