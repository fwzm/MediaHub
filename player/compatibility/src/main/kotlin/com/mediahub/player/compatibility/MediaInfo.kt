package com.mediahub.player.compatibility

import com.mediahub.model.HdrType
import com.mediahub.model.PlaybackSource

/**
 * 播放兼容性评估输入：从 [PlaybackSource] / 详情流信息归一化而来。
 * 归一化（codec 规范名、尺寸、HDR）由评估器内部完成。
 */
data class MediaInfo(
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val hdrType: HdrType = HdrType.NONE,
) {
    companion object {
        fun fromPlaybackSource(source: PlaybackSource): MediaInfo = MediaInfo(
            container = source.container,
            videoCodec = source.videoCodec,
            audioCodec = source.audioCodec,
            width = source.width,
            height = source.height,
            bitrate = source.bitrate,
            hdrType = source.hdrType,
        )
    }
}
