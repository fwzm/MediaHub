package com.mediahub.feature.player

import com.mediahub.model.MediaType

/**
 * 播放完成后的去向策略（#46 播放完成自动退出）。
 *
 * 现状：电影或剧集最后一集播到 EOF 后，引擎只发 Ended，播放器停在末帧，用户必须手动返回。
 * 规则：只有「单片/单集」类内容播完且没有启动续播时才自动退出；音频、直播、容器类型不退出
 * （音频应保持队列语义，直播没有自然结尾）。
 */
object PlaybackCompletionPolicy {
    /** 播完后短暂停留，让用户看到结束状态再退回详情页。 */
    const val AUTO_EXIT_GRACE_MS: Long = 1_500L

    /** 该类型播完且无续播时是否应自动退出播放页。 */
    fun shouldAutoExit(type: MediaType): Boolean = when (type) {
        MediaType.MOVIE, MediaType.EPISODE, MediaType.VIDEO -> true
        MediaType.SERIES, MediaType.SEASON, MediaType.FOLDER,
        MediaType.AUDIO, MediaType.LIVE_TV, MediaType.OTHER -> false
    }
}
