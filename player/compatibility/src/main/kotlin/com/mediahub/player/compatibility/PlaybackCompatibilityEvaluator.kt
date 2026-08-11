package com.mediahub.player.compatibility

import com.mediahub.model.HdrType
import com.mediahub.model.UserPreferences

/** 播放决策（服务端视角的"客户端能否直接播放"结论）。 */
enum class PlaybackDecision {
    /** 客户端直接播放原始文件，服务端零处理 */
    DIRECT_PLAY,

    /** 服务端仅 remux 容器 / 仅转码音频 / 仅限速（视频流原样） */
    DIRECT_STREAM,

    /** 服务端对视频转码 */
    TRANSCODE,

    /** 无可用播放路径 */
    UNSUPPORTED,
}

/** 评估结果：决策 + 原因（用户可读 / 可诊断）。 */
data class DecisionResult(
    val decision: PlaybackDecision,
    val reasons: List<String> = emptyList(),
)

/**
 * 播放兼容性评估器（纯逻辑，可单测；禁止在别处硬编码 if codec == xxx 链）。
 *
 * 输入：媒体信息 + 设备能力 + 用户偏好。
 * 输出：DIRECT_PLAY / DIRECT_STREAM / TRANSCODE / UNSUPPORTED 及原因。
 *
 * 语义约定：
 * - TRANSCODE 仅当"视频流本身无法直通"（编码不支持 / 超分辨率 / HDR 不支持）；
 * - 容器 remux 或音频转码或码率限制 → DIRECT_STREAM（视频不转码，成本最低）；
 * - 编码未知 → UNSUPPORTED（宁可明确失败，不猜测）。
 */
object PlaybackCompatibilityEvaluator {

    /** 客户端可直接解析的容器（硬解/软解均可）。 */
    private val SUPPORTED_CONTAINERS = setOf(
        "mp4", "m4v", "mov", "mkv", "webm", "ts", "m2ts", "3gp",
    )

    fun evaluate(media: MediaInfo, device: DeviceCapabilities, prefs: UserPreferences): DecisionResult {
        val reasons = mutableListOf<String>()

        // 1. 视频编码可解码性
        val videoCodec = VideoCodec.fromCodecName(media.videoCodec)
        if (videoCodec == null) {
            reasons += "无法识别视频编码（${media.videoCodec ?: "未知"}）"
            return DecisionResult(PlaybackDecision.UNSUPPORTED, reasons)
        }
        val videoCap = device.videoCapability(videoCodec)
        if (videoCap == null) {
            reasons += "设备不支持 ${videoCodec.displayName}（${media.videoCodec}）"
            return DecisionResult(PlaybackDecision.TRANSCODE, reasons)
        }

        // 2. 分辨率 / 10bit / HDR 能力（视频直通的关键门槛）
        if (!videoCap.canDecode(media.width, media.height, media.hdrType != HdrType.NONE)) {
            reasons += "超出设备解码能力（${media.width}x${media.height}）"
            return DecisionResult(PlaybackDecision.TRANSCODE, reasons)
        }
        if (!device.supportsHdr(media.hdrType)) {
            reasons += "设备显示不支持 ${media.hdrType.name}（需转码为 SDR 或降级播放）"
            return DecisionResult(PlaybackDecision.TRANSCODE, reasons)
        }

        // 3. 容器
        val containerOk = media.container?.lowercase() in SUPPORTED_CONTAINERS
        if (!containerOk) {
            reasons += "容器 ${media.container ?: "未知"} 需服务端 remux"
        }

        // 4. 音频
        val audioCodec = AudioCodec.fromCodecName(media.audioCodec)
        val audioOk = media.audioCodec == null || (audioCodec != null && audioCodec in device.audioCodecs)
        if (!audioOk) {
            reasons += "设备不支持音频 ${media.audioCodec}（将仅转码音频）"
        }

        // 5. 码率上限偏好
        val maxBitrate = prefs.maxBitrateBps
        val bitrateOk = maxBitrate == null || media.bitrate == null || media.bitrate <= maxBitrate
        if (!bitrateOk) {
            reasons += "码率超过用户上限（${media.bitrate} bps）"
        }

        return if (containerOk && audioOk && bitrateOk) {
            DecisionResult(PlaybackDecision.DIRECT_PLAY, reasons)
        } else {
            // 视频可直通，仅需 remux / 音频转码 / 限速
            DecisionResult(PlaybackDecision.DIRECT_STREAM, reasons)
        }
    }
}
