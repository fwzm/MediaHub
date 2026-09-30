package com.mediahub.model

/**
 * 外挂字幕文件格式助手（P2 字幕中心切片一）。
 *
 * 覆盖格式与 MIME 映射（Media3 SubtitleConfiguration 与 mpv 共用）：
 * - SRT  → application/x-subrip
 * - ASS/SSA → text/x-ssa
 * - WebVTT → text/vtt
 *
 * 语言猜测来自文件名尾缀（如 `movie.zh.srt` / `movie.eng.ass`），无尾缀为未知。
 * 这里只做纯文本推断，不做任何在线查询。
 */
object SubtitleFormats {

    /** 支持发现/加载的字幕扩展名（小写）。 */
    val EXTENSIONS: Set<String> = setOf("srt", "ass", "ssa", "vtt")

    /** 扩展名 → MIME；不支持（null）的扩展名由调用方直接过滤掉。 */
    fun mimeTypeOf(extension: String): String? = when (extension.lowercase()) {
        "srt" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        "vtt" -> "text/vtt"
        else -> null
    }

    /**
     * 从文件名解析语言尾缀：取最后一个不含空格的点分段（`movie.zh.srt` → `zh`）。
     * 只识别常见语言写法（ISO 639 常用码 + 中文简繁习惯写法）；
     * 无法识别返回 null（未知，不猜测——避免把发布组标签误判成语言）。
     */
    fun languageFromFileName(fileName: String): String? {
        val stem = fileName.substringBeforeLast('.')
        val tag = stem.substringAfterLast('.', "").lowercase()
        if (tag.isEmpty() || tag.length > 5 || tag.contains(' ')) return null
        return KNOWN_LANGUAGE_TAGS[tag]
    }

    /** 常见语言码 → 归一化标签（BCP-47 风格，中文区分简繁地区）。 */
    private val KNOWN_LANGUAGE_TAGS: Map<String, String> = mapOf(
        "zh" to "zh", "zho" to "zh", "chs" to "zh", "sc" to "zh", "gb" to "zh",
        "zh-hans" to "zh-cn", "zh-cn" to "zh-cn", "zh-sg" to "zh-cn",
        "zh-hant" to "zh-tw", "zh-tw" to "zh-tw", "zh-hk" to "zh-tw",
        "cht" to "zh-tw", "tc" to "zh-tw", "big5" to "zh-tw",
        "en" to "en", "eng" to "en",
        "ja" to "ja", "jpn" to "ja", "jp" to "ja",
        "ko" to "ko", "kor" to "ko", "kr" to "ko",
        "fr" to "fr", "fra" to "fr", "fre" to "fr",
        "de" to "de", "deu" to "de", "ger" to "de",
        "es" to "es", "spa" to "es",
        "ru" to "ru", "rus" to "ru",
        "pt" to "pt", "por" to "pt",
        "it" to "it", "ita" to "it",
        "th" to "th", "tha" to "th",
        "vi" to "vi", "vie" to "vi",
        "id" to "id", "ind" to "id",
        "ar" to "ar", "ara" to "ar",
        "hi" to "hi", "hin" to "hi",
    )
}
