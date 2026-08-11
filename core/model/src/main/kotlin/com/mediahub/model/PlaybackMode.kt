package com.mediahub.model

/**
 * 服务端播放模式（统一语义，适用于 Emby/Jellyfin 等服务端与云盘直链）。
 *
 * - [DIRECT_PLAY]：客户端直接播放原始文件，服务端零处理。
 * - [DIRECT_STREAM]：服务端仅 remux 容器或仅转码音频，视频流原样（避免客户端解码问题）。
 * - [TRANSCODE]：服务端对视频流进行转码。
 * - [UNSUPPORTED]：无法播放（客户端与服务端均无可用路径）。
 */
enum class PlaybackMode {
    DIRECT_PLAY,
    DIRECT_STREAM,
    TRANSCODE,
    UNSUPPORTED;

    val isDirect: Boolean get() = this == DIRECT_PLAY || this == DIRECT_STREAM
    val requiresServerProcessing: Boolean get() = this == DIRECT_STREAM || this == TRANSCODE
}
