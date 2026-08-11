package com.mediahub.player.compatibility

/** 视频编码规范名。aliases 用于把各种数据源传来的 codec 字符串归一化。 */
enum class VideoCodec(val aliases: List<String>, val displayName: String) {
    H264(listOf("h264", "avc", "avc1", "h264/avc"), "H.264"),
    HEVC(listOf("hevc", "h265", "hev1", "hvc1"), "H.265/HEVC"),
    AV1(listOf("av1", "av01"), "AV1"),
    VP9(listOf("vp9", "vp09"), "VP9"),
    MPEG2(listOf("mpeg2video", "mpeg2"), "MPEG-2"),
    MPEG4(listOf("mpeg4", "mp4v", "xvid", "divx"), "MPEG-4"),
    VC1(listOf("vc1", "wmv3"), "VC-1"),
    VP8(listOf("vp8"), "VP8"),
    OTHER(emptyList(), "其他");

    companion object {
        fun fromCodecName(raw: String?): VideoCodec? {
            if (raw.isNullOrBlank()) return null
            val norm = raw.lowercase()
            return entries.firstOrNull { codec -> codec.aliases.any { norm.contains(it) } }
        }
    }
}

/** 音频编码。 */
enum class AudioCodec(val aliases: List<String>, val displayName: String) {
    AAC(listOf("aac", "mp4a"), "AAC"),
    AC3(listOf("ac3"), "AC3"),
    EAC3(listOf("eac3", "ec-3"), "EAC3"),
    TRUEHD(listOf("truehd", "mlp"), "TrueHD"),
    DTS(listOf("dts"), "DTS"),
    DTS_HD(listOf("dts-hd", "dtshd", "dts_ma"), "DTS-HD"),
    FLAC(listOf("flac"), "FLAC"),
    OPUS(listOf("opus"), "Opus"),
    MP3(listOf("mp3", "libmp3lame"), "MP3"),
    VORBIS(listOf("vorbis"), "Vorbis"),
    PCM(listOf("pcm", "lpcm", "raw"), "PCM"),
    OTHER(emptyList(), "其他");

    companion object {
        fun fromCodecName(raw: String?): AudioCodec? {
            if (raw.isNullOrBlank()) return null
            val norm = raw.lowercase()
            return entries.firstOrNull { codec -> codec.aliases.any { norm.contains(it) } }
        }
    }
}
