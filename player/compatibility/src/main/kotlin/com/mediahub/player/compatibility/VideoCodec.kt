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
    AC3(listOf("ac3", "ac-3"), "AC3"),
    EAC3(listOf("eac3", "ec-3", "ec3", "ddp", "dd+"), "EAC3"),
    TRUEHD(listOf("truehd", "mlp"), "TrueHD"),
    DTS_HD(listOf("dts-hd ma", "dts-hd hra", "dts-hd", "dtshd", "dts_ma"), "DTS-HD"),
    DTS(listOf("dts"), "DTS"),
    FLAC(listOf("flac"), "FLAC"),
    OPUS(listOf("opus"), "Opus"),
    MP3(listOf("mp3", "libmp3lame"), "MP3"),
    VORBIS(listOf("vorbis"), "Vorbis"),
    PCM(listOf("pcm", "lpcm", "raw"), "PCM"),
    OTHER(emptyList(), "其他");

    companion object {
        /**
         * 长别名优先的精确匹配表。
         *
         * 旧实现用 `norm.contains(alias)` 做子串匹配并按枚举声明顺序取第一个命中，
         * 于是 "eac3" 先命中 AC3 的 "ac3"、"dts-hd" 先命中 DTS 的 "dts" ——
         * 设备只支持 AC3/DTS 时会被误判为可直通，实际无声。
         */
        private val MATCH_ORDER: List<Pair<String, AudioCodec>> =
            entries
                .flatMap { codec -> codec.aliases.map { normalizeAlias(it) to codec } }
                .sortedByDescending { it.first.length }

        fun fromCodecName(raw: String?): AudioCodec? {
            if (raw.isNullOrBlank()) return null
            val norm = normalizeAlias(raw)
            if (norm.isEmpty()) return null
            return MATCH_ORDER.firstOrNull { (alias, _) -> aliasMatches(norm, alias) }?.second
        }

        /** 统一分隔符：点/下划线/斜杠与空格等价，便于 "dts-hd.ma.5.1" 这类写法归一。 */
        private fun normalizeAlias(raw: String): String =
            raw.lowercase().replace('.', '-').replace('_', '-').replace('/', '-').trim()

        /**
         * 别名必须落在词边界上（前面不能是字母数字），避免 "ac3" 命中 "eac3"。
         * 别名后允许紧跟版本号数字（"ddp5.1"、"dts5.1"），数字之后仍须是边界。
         */
        private fun aliasMatches(haystack: String, alias: String): Boolean {
            if (alias.isEmpty()) return false
            var from = 0
            while (true) {
                val idx = haystack.indexOf(alias, from)
                if (idx < 0) return false
                if (isBoundary(haystack.getOrNull(idx - 1)) && endsAtBoundary(haystack, idx + alias.length)) {
                    return true
                }
                from = idx + 1
            }
        }

        /** 从 [start] 起跳过版本号数字，再判断是否落在边界。 */
        private fun endsAtBoundary(haystack: String, start: Int): Boolean {
            var i = start
            while (i < haystack.length && haystack[i].isDigit()) i += 1
            return isBoundary(haystack.getOrNull(i))
        }

        private fun isBoundary(c: Char?): Boolean = c == null || !c.isLetterOrDigit()
    }
}
