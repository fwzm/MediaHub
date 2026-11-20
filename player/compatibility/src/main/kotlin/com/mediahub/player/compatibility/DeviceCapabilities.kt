package com.mediahub.player.compatibility

import com.mediahub.model.HdrType

/** 某视频编码在设备上的解码能力。 */
data class VideoCodecCapability(
    val codec: VideoCodec,
    val maxWidth: Int = Int.MAX_VALUE,
    val maxHeight: Int = Int.MAX_VALUE,
    val supports10Bit: Boolean = true,
    val hardwareAccelerated: Boolean = true,
) {
    fun canDecode(width: Int?, height: Int?, is10Bit: Boolean): Boolean {
        if (width == null || height == null) return true // 未知尺寸，不阻断
        return width <= maxWidth && height <= maxHeight && (is10Bit.not() || supports10Bit)
    }
}

/** 设备播放能力快照（由 AndroidDeviceCapabilitiesProvider 采集，评估器只读使用）。 */
data class DeviceCapabilities(
    val videoCodecs: Set<VideoCodecCapability> = emptySet(),
    val audioCodecs: Set<AudioCodec> = emptySet(),
    val hdrSupported: Set<HdrType> = emptySet(),
    val maxDisplayWidth: Int = 3840,
    val maxDisplayHeight: Int = 2160,
    val sdkInt: Int = 0,
) {
    /**
     * 合并同一编码的多个解码器能力（MediaCodecList 常为同一编码返回软解 + 硬解 + 不同档位）。
     * 只取首个匹配会让能力取决于声明顺序：软解在前会把硬解能力藏起来，
     * 因此这里取"包络"：最大分辨率取最大、10bit 与硬解取任一支持。
     */
    fun videoCapability(codec: VideoCodec): VideoCodecCapability? {
        val matching = videoCodecs.filter { it.codec == codec }
        if (matching.isEmpty()) return null
        if (matching.size == 1) return matching.first()
        return VideoCodecCapability(
            codec = codec,
            maxWidth = matching.maxOf { it.maxWidth },
            maxHeight = matching.maxOf { it.maxHeight },
            supports10Bit = matching.any { it.supports10Bit },
            hardwareAccelerated = matching.any { it.hardwareAccelerated },
        )
    }

    fun supportsHdr(hdr: HdrType): Boolean = hdr == HdrType.NONE || hdr in hdrSupported
}
