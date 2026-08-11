package com.mediahub.player.compatibility

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.mediahub.model.HdrType

/** 设备能力采集接口（结果缓存，运行时不变）。 */
interface DeviceCapabilitiesProvider {
    fun get(): DeviceCapabilities
}

/**
 * 基于系统 API 的设备能力采集：
 * - MediaCodecList：编解码器 + 最大分辨率 + 10bit 支持；
 * - Display：HDR 能力、最大分辨率；
 * - 音频：按 MIME 判断系统解码器存在性。
 *
 * 说明：真实硬件支持矩阵比 MediaCodecList 复杂（厂商标注、外接屏等），
 * 这里给出可靠的基础实现，后续可在 Debug 页人工校准。
 */
class AndroidDeviceCapabilitiesProvider(context: Context) : DeviceCapabilitiesProvider {

    private val appContext = context.applicationContext

    @Volatile
    private var cached: DeviceCapabilities? = null

    override fun get(): DeviceCapabilities = cached ?: synchronized(this) {
        cached ?: build().also { cached = it }
    }

    @SuppressLint("NewApi")
    private fun build(): DeviceCapabilities {
        val video = mutableSetOf<VideoCodecCapability>()
        val audio = mutableSetOf<AudioCodec>()

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            val types = runCatching { info.supportedTypes }.getOrDefault(arrayOf())
            val mime = types.firstOrNull() ?: continue

            when (mime) {
                "video/avc" -> video += videoCapabilityOf(info, VideoCodec.H264)
                "video/hevc" -> video += videoCapabilityOf(info, VideoCodec.HEVC)
                "video/av01" -> video += videoCapabilityOf(info, VideoCodec.AV1)
                "video/x-vnd.on2.vp9" -> video += videoCapabilityOf(info, VideoCodec.VP9)
                "video/mpeg2" -> video += videoCapabilityOf(info, VideoCodec.MPEG2)
                "video/mp4v-es" -> video += videoCapabilityOf(info, VideoCodec.MPEG4)
                "video/wvc1" -> video += videoCapabilityOf(info, VideoCodec.VC1)
                "video/x-vnd.on2.vp8" -> video += videoCapabilityOf(info, VideoCodec.VP8)

                "audio/mp4a-latm" -> audio += AudioCodec.AAC
                "audio/ac3" -> audio += AudioCodec.AC3
                "audio/eac3" -> audio += AudioCodec.EAC3
                "audio/vnd.dts" -> audio += AudioCodec.DTS
                "audio/vnd.dts.hd" -> audio += AudioCodec.DTS_HD
                "audio/flac" -> audio += AudioCodec.FLAC
                "audio/opus" -> audio += AudioCodec.OPUS
                "audio/mpeg" -> audio += AudioCodec.MP3
                "audio/vorbis" -> audio += AudioCodec.VORBIS
                "audio/raw" -> audio += AudioCodec.PCM
            }
        }

        // HDR 能力（DisplayManager，API 24+）
        val hdr = mutableSetOf<HdrType>()
        val display = getDefaultDisplay()
        if (display != null) {
            val caps = display.hdrCapabilities
            val types = runCatching { caps.supportedHdrTypes }.getOrDefault(intArrayOf())
            if (types.contains(Display.HdrCapabilities.HDR_TYPE_HDR10)) hdr += HdrType.HDR10
            if (types.contains(Display.HdrCapabilities.HDR_TYPE_HLG)) hdr += HdrType.HLG
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                types.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS)
            ) {
                hdr += HdrType.HDR10_PLUS
            }
            // Dolby Vision 无标准 Display HDR 枚举，用 codec 近似（dvhe 等）
            if (hasDolbyVisionDecoder()) hdr += HdrType.DOLBY_VISION
        }

        val displaySize = getDisplaySize()
        return DeviceCapabilities(
            videoCodecs = video,
            audioCodecs = audio,
            hdrSupported = hdr,
            maxDisplayWidth = displaySize.first,
            maxDisplayHeight = displaySize.second,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    private fun videoCapabilityOf(info: MediaCodecInfo, codec: VideoCodec): VideoCodecCapability {
        val caps = info.getCapabilitiesForType(codec.mimeType ?: return defaultCapability(codec))
        val videoCaps = caps.videoCapabilities
        val width = videoCaps?.supportedWidths?.let { it.upper } ?: Int.MAX_VALUE
        val height = videoCaps?.supportedHeights?.let { it.upper } ?: Int.MAX_VALUE
        val supports10Bit = supports10BitProfile(codec, caps.profileLevels)
        return VideoCodecCapability(
            codec = codec,
            maxWidth = width,
            maxHeight = height,
            supports10Bit = supports10Bit,
            hardwareAccelerated = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else false,
        )
    }

    private fun defaultCapability(codec: VideoCodec) = VideoCodecCapability(codec = codec)

    private fun supports10BitProfile(codec: VideoCodec, profileLevels: Array<MediaCodecInfo.CodecProfileLevel>): Boolean =
        when (codec) {
            VideoCodec.HEVC -> profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
            VideoCodec.H264 -> profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 }
            VideoCodec.VP9 -> profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2 ||
                    it.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile3
            }
            VideoCodec.AV1 -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 }
            else -> true
        }

    private fun hasDolbyVisionDecoder(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        return codecList.codecInfos.any { info ->
            !info.isEncoder && runCatching { info.supportedTypes.toList() }.getOrDefault(emptyList())
                .any { it.startsWith("video/dolby-vision") || it.startsWith("video/dovi") }
        }
    }

    private fun getDefaultDisplay(): Display? = runCatching {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        wm?.defaultDisplay
    }.getOrNull()

    private fun getDisplaySize(): Pair<Int, Int> {
        val dm = appContext.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private val VideoCodec.mimeType: String?
        get() = when (this) {
            VideoCodec.H264 -> "video/avc"
            VideoCodec.HEVC -> "video/hevc"
            VideoCodec.AV1 -> "video/av01"
            VideoCodec.VP9 -> "video/x-vnd.on2.vp9"
            VideoCodec.MPEG2 -> "video/mpeg2"
            VideoCodec.MPEG4 -> "video/mp4v-es"
            VideoCodec.VC1 -> "video/wvc1"
            VideoCodec.VP8 -> "video/x-vnd.on2.vp8"
            VideoCodec.OTHER -> null
        }
}
