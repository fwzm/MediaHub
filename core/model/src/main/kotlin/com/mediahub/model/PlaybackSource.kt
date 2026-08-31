package com.mediahub.model

/**
 * 播放源（播放器输入）。
 *
 * 关键约束（ADR-003）：url 是"临时签名 URL / 会话 URL"，绝不落库；
 * 应用持久化 [MediaItem.path] / itemId，播放时由 Provider 重新 resolve。
 */
data class PlaybackSource(
    /** 播放地址（临时） */
    val url: String,
    /** 播放请求头（含鉴权时由上层负责，仅内存） */
    val headers: Map<String, String> = emptyMap(),
    /** Cookie（仅内存，绝不落库） */
    val cookies: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val subtitleCodec: String? = null,
    val bitrate: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val hdrType: HdrType = HdrType.NONE,
    val durationMs: Long? = null,
    val mode: PlaybackMode = PlaybackMode.DIRECT_PLAY,
    /**
     * Provider 签发的播放会话关联 id（如 Emby/Jellyfin PlaybackInfo 返回的
     * PlaySessionId），仅用于当前播放生命周期的进度上报关联。
     * 仅内存使用：不得落库、不得作为鉴权凭据（Token 红线不变）、
     * 不参与媒体 URL 构造与引擎选择。Local/WebDAV 等无会话语义的 Provider 为 null。
     */
    val sessionId: String? = null,
    /** 链接过期时间（epoch ms），null 表示未知/不过期 */
    val expiresAtEpochMs: Long? = null,
    val supportsSeeking: Boolean = true,
) {
    val isDirectPlay: Boolean get() = mode == PlaybackMode.DIRECT_PLAY
    val isDirectStream: Boolean get() = mode == PlaybackMode.DIRECT_STREAM
    val isTranscode: Boolean get() = mode == PlaybackMode.TRANSCODE

    /** 链接是否已过期（未知视为未过期）。 */
    fun isExpired(nowEpochMs: Long): Boolean =
        expiresAtEpochMs != null && nowEpochMs >= expiresAtEpochMs
}

/**
 * 播放请求选项（用户偏好 + 播放器上下文），由 Provider 的 resolvePlayback 消费。
 */
data class PlaybackOptions(
    /** 最大码率（bps），null 表示不限 */
    val maxBitrate: Long? = null,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val enableDirectPlay: Boolean = true,
    val enableDirectStream: Boolean = true,
    val forceTranscode: Boolean = false,
    /** 起始播放位置（续播） */
    val startPositionMs: Long? = null,
    val audioBoost: Float = 1f,
)
