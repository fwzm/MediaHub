package com.mediahub.player.mpv

/**
 * 音频增益策略（缺陷 7：播放音频偏小）。
 *
 * 多声道片源（AC3/EAC3/DTS/TrueHD 等）在立体声输出上会被 mpv 下混。
 * libmpv 默认 `audio-normalize-downmix=no`，下混后中置/环绕能量被压低，
 * 听感就是"声音偏小"。修复手段是在**已知多声道编码**上启用下混归一化，
 * 而不是抬高 `volume-max` 音量上限。
 *
 * 立体声编码（AAC/MP3/Opus/Vorbis）保持原行为，避免无差别改变现有音量曲线。
 * 纯函数、无 Android 依赖，可单测。
 */
object MpvAudioGainPolicy {

    /** 常见多声道（≥5.1）音频编码别名；匹配时忽略大小写与首尾空白。 */
    private val MULTICHANNEL_CODECS = setOf(
        "ac3",
        "eac3",
        "dts",
        "dts-hd",
        "dts-hd ma",
        "dts-hd hra",
        "truehd",
        "mlp",
        "flac",
        "pcm_bluray",
        "pcm_dvd",
    )

    fun shouldNormalizeDownmix(audioCodec: String?): Boolean {
        val normalized = audioCodec?.trim()?.lowercase() ?: return false
        if (normalized.isEmpty()) return false
        // E-AC3 在容器里可能写作 "eac3" 或 "ec-3"；DTS 家族同样带连字符。
        val canonical = normalized.replace("ec-3", "eac3").replace("e-ac3", "eac3")
        return canonical in MULTICHANNEL_CODECS
    }
}
