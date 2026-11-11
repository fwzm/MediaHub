package com.mediahub.player.compatibility

import com.mediahub.model.HdrType
import com.mediahub.model.PlaybackSource

/**
 * 单维设备能力的证据态（ADR-042）。
 *
 * 纪律：UNKNOWN 既不等价于 SUPPORTED，也不等价于 UNSUPPORTED。只有当设备采集确实
 * 覆盖该轴、且该轴对该编码/格式有明确结论时才允许给出 SUPPORTED / UNSUPPORTED。
 * 采集器未覆盖的编码（例如 AudioCodec.TRUEHD 没有对应 MIME 映射）一律记为 UNKNOWN。
 */
enum class CapabilityEvidence {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

/** 单轴评估：证据态 + 人类可读原因。原因会进入播放启动 trace，禁止包含 URL / Token / Cookie。 */
data class AxisAssessment(
    val evidence: CapabilityEvidence,
    val reason: String,
) {
    companion object {
        fun supported(reason: String) = AxisAssessment(CapabilityEvidence.SUPPORTED, reason)
        fun unsupported(reason: String) = AxisAssessment(CapabilityEvidence.UNSUPPORTED, reason)
        fun unknown(reason: String) = AxisAssessment(CapabilityEvidence.UNKNOWN, reason)
    }
}

/**
 * 设备对"这一次播放源"的能力评估（ADR-042）。
 *
 * 与 [PlaybackCompatibilityEvaluator] 的分工必须保持清晰：
 * - 本类 = **可执行的客户端路由证据**：只回答"设备自身能不能解这个流"，是
 *   [com.mediahub.player.engine.PlaybackEngineSelector] 的输入之一；
 * - Evaluator = **服务端协商结论**（DIRECT_PLAY / DIRECT_STREAM / TRANSCODE / UNSUPPORTED），
 *   决定向服务端请求什么，并用于 UI / 诊断展示。
 *
 * 两者不得互相冒充：路由不得因为 evaluator 给出 TRANSCODE 就改变引擎选择，
 * 兼容性展示也不得把 UNKNOWN 画成"支持"。
 */
data class DevicePlaybackAssessment(
    val video: AxisAssessment,
    val audio: AxisAssessment,
    val hdr: AxisAssessment,
    val dolbyVision: AxisAssessment,
) {
    /** 是否存在"设备明确不支持"的编解码轴（UNKNOWN 不计入）。 */
    val hasUnsupportedCodecAxis: Boolean
        get() = video.evidence == CapabilityEvidence.UNSUPPORTED ||
            audio.evidence == CapabilityEvidence.UNSUPPORTED

    /** 单行摘要（进日志 / trace；无凭据、无 URL）。 */
    fun summary(): String = buildString {
        append("video=").append(video.evidence.name)
        append(",audio=").append(audio.evidence.name)
        append(",hdr=").append(hdr.evidence.name)
        append(",dv=").append(dolbyVision.evidence.name)
    }

    /** 播放启动 trace 元数据（键名与既有 trace 的 camelCase 风格一致）。 */
    fun traceMetadata(): Map<String, String> = mapOf(
        "deviceVideoEvidence" to video.evidence.name,
        "deviceAudioEvidence" to audio.evidence.name,
        "deviceHdrEvidence" to hdr.evidence.name,
        "deviceDolbyVisionEvidence" to dolbyVision.evidence.name,
        "deviceAssessment" to summary(),
    )

    companion object {
        /** 无设备证据时的占位（不得据此断言支持或不支持）。 */
        val UNKNOWN = DevicePlaybackAssessment(
            video = AxisAssessment.unknown("未提供设备能力证据"),
            audio = AxisAssessment.unknown("未提供设备能力证据"),
            hdr = AxisAssessment.unknown("未提供设备能力证据"),
            dolbyVision = AxisAssessment.unknown("未提供设备能力证据"),
        )
    }
}

/**
 * 把设备能力快照与具体播放源合成证据化的评估结果（纯函数，可单测）。
 *
 * 采集器覆盖范围（见 [AndroidDeviceCapabilitiesProvider]）：视频 H264/HEVC/AV1/VP9/MPEG2/MPEG4/VC1/VP8；
 * 音频 AAC/AC3/EAC3/DTS/DTS-HD/FLAC/OPUS/MP3/VORBIS/PCM；HDR 由 Display 上报（DV 以解码器存在性近似）。
 * 覆盖范围之外的编码一律 UNKNOWN，绝不因为"集合里没有"就断言设备不支持。
 */
object DevicePlaybackAssessor {

    private val ENUMERABLE_VIDEO = setOf(
        VideoCodec.H264, VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.VP9,
        VideoCodec.MPEG2, VideoCodec.MPEG4, VideoCodec.VC1, VideoCodec.VP8,
    )

    private val ENUMERABLE_AUDIO = setOf(
        AudioCodec.AAC, AudioCodec.AC3, AudioCodec.EAC3, AudioCodec.DTS, AudioCodec.DTS_HD,
        AudioCodec.FLAC, AudioCodec.OPUS, AudioCodec.MP3, AudioCodec.VORBIS, AudioCodec.PCM,
    )

    fun assess(source: PlaybackSource, device: DeviceCapabilities): DevicePlaybackAssessment =
        DevicePlaybackAssessment(
            video = assessVideo(source, device),
            audio = assessAudio(source, device),
            hdr = assessHdr(source, device),
            dolbyVision = assessDolbyVision(source, device),
        )

    private fun assessVideo(source: PlaybackSource, device: DeviceCapabilities): AxisAssessment {
        val raw = source.videoCodec?.trim()?.takeIf { it.isNotEmpty() }
            ?: return AxisAssessment.unknown("媒体未声明视频编码")
        val codec = longestAliasMatch(raw, VideoCodec.entries.map { it to it.aliases })
            ?: return AxisAssessment.unknown("无法识别的视频编码（$raw）")
        if (codec !in ENUMERABLE_VIDEO) return AxisAssessment.unknown("设备采集未覆盖 ${codec.displayName}")
        if (device.videoCodecs.isEmpty()) return AxisAssessment.unknown("设备未枚举到任何视频解码器")
        val cap = device.videoCapability(codec)
            ?: return AxisAssessment.unsupported("设备无 ${codec.displayName} 解码器")
        if (!cap.canDecode(source.width, source.height, is10Bit = false)) {
            return AxisAssessment.unsupported("超出 ${codec.displayName} 解码上限 ${cap.maxWidth}x${cap.maxHeight}")
        }
        val mode = if (cap.hardwareAccelerated) "硬解" else "软解"
        return AxisAssessment.supported("设备可解 ${codec.displayName}（$mode）")
    }

    private fun assessAudio(source: PlaybackSource, device: DeviceCapabilities): AxisAssessment {
        val raw = source.audioCodec?.trim()?.takeIf { it.isNotEmpty() }
            ?: return AxisAssessment.unknown("媒体未声明音频编码")
        val codec = longestAliasMatch(raw, AudioCodec.entries.map { it to it.aliases })
            ?: return AxisAssessment.unknown("无法识别的音频编码（$raw）")
        if (codec !in ENUMERABLE_AUDIO) return AxisAssessment.unknown("设备采集未覆盖 ${codec.displayName}")
        if (device.audioCodecs.isEmpty()) return AxisAssessment.unknown("设备未枚举到任何音频解码器")
        return if (codec in device.audioCodecs) {
            AxisAssessment.supported("设备可解 ${codec.displayName}")
        } else {
            AxisAssessment.unsupported("设备无 ${codec.displayName} 解码器")
        }
    }

    private fun assessHdr(source: PlaybackSource, device: DeviceCapabilities): AxisAssessment {
        if (source.hdrType == HdrType.NONE) return AxisAssessment.supported("片源非 HDR")
        if (device.hdrSupported.isEmpty()) return AxisAssessment.unknown("显示设备未报告 HDR 能力")
        return if (source.hdrType in device.hdrSupported) {
            AxisAssessment.supported("显示支持 ${source.hdrType.name}")
        } else {
            AxisAssessment.unsupported("显示不支持 ${source.hdrType.name}")
        }
    }

    private fun assessDolbyVision(source: PlaybackSource, device: DeviceCapabilities): AxisAssessment {
        if (source.hdrType != HdrType.DOLBY_VISION) return AxisAssessment.supported("片源非杜比视界")
        if (device.hdrSupported.isEmpty()) return AxisAssessment.unknown("显示设备未报告 HDR/DV 能力")
        return if (HdrType.DOLBY_VISION in device.hdrSupported) {
            // 近似判据：只表示"设备报告了 DV 解码能力"，不表示 Profile 5/7 色彩管理或
            // Profile 7→8.1 转换能力已验证（后者属 M2 范围，未取证前不得声明支持）。
            AxisAssessment.supported("设备报告杜比视界解码能力（近似判据，不代表 Profile 转换能力）")
        } else {
            AxisAssessment.unsupported("设备未报告杜比视界解码能力")
        }
    }

    /**
     * 最长别名匹配。不复用 [VideoCodec.fromCodecName] / [AudioCodec.fromCodecName] 的
     * "按枚举顺序 contains" 语义：那会让 "dts-hd" 被 "dts" 抢先命中，从而把 DTS-HD
     * 误判为设备只支持 DTS。此处取匹配到的最长别名，保证归一化唯一确定。
     */
    private fun <T> longestAliasMatch(raw: String, candidates: List<Pair<T, List<String>>>): T? {
        val norm = raw.trim().lowercase()
        return candidates
            .flatMap { (value, aliases) -> aliases.map { value to it } }
            .filter { (_, alias) -> norm.contains(alias) }
            .maxByOrNull { (_, alias) -> alias.length }
            ?.first
    }
}
