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
    fun videoCapability(codec: VideoCodec): VideoCodecCapability? =
        videoCodecs.firstOrNull { it.codec == codec }

    fun supportsHdr(hdr: HdrType): Boolean = hdr == HdrType.NONE || hdr in hdrSupported
}
