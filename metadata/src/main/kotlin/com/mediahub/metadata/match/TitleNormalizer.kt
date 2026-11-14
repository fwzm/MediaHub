package com.mediahub.metadata.match

import java.text.Normalizer

/** 从文件名/目录名解析出的候选标题信息。 */
data class ParsedTitle(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * 标题归一化：把文件名/用户输入折叠成可比较的标题。
 *
 * 覆盖：全角折叠（NFKC）、大小写、扩展名、方括号/圆括号标签、发布组后缀、
 * 分辨率与编码等发布词、季集标记（SxxExx / 1x02 / Season 1 / 第 N 季）、年份、标点。
 * 年份提取只认 19xx/20xx，避免把 1080p / 2160p 误判为年份。
 */
object TitleNormalizer {

    private val EXTENSION = Regex("\\.(mkv|mp4|avi|mov|m4v|ts|webm|wmv|flv)$", RegexOption.IGNORE_CASE)
    private val PAREN_TAGS = Regex("\\([^)]*\\)|（[^）]*）")
    private val BRACKET_CHARS = Regex("[\\[\\]【】]")
    private val RELEASE_GROUP = Regex("-\\s*[a-z0-9]{2,}$", RegexOption.IGNORE_CASE)
    private val RELEASE_TOKEN = Regex(
        "\\b(480p|576p|720p|1080p|1080i|2160p|4320p|4k|8k|uhd|hdr10\\+?|hdr|dolby|vision|bluray|blu-ray|" +
            "bdrip|brrip|webrip|web-dl|webdl|hdtv|dvdrip|remux|x264|x265|h264|h265|hevc|avc|aac|ac3|eac3|" +
            "dts(-hd)?|truehd|atmos|flac|10bit|8bit|proper|repack|extended|uncut|imax|remastered)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val SXXEXX = Regex("\\bs\\d{1,2}\\s*e\\d{1,3}\\b", RegexOption.IGNORE_CASE)
    private val NXM = Regex("\\b\\d{1,2}x\\d{1,3}\\b", RegexOption.IGNORE_CASE)
    private val SEASON_WORD = Regex("\\bseason\\s*\\d{1,2}\\b", RegexOption.IGNORE_CASE)
    private val SEASON_CN = Regex("第\\s*(\\d{1,3}|[一二三四五六七八九十]+)\\s*[季部]")
    private val SEASON_ONLY = Regex("\\bs\\d{1,2}\\b", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")

    private val SXXEXX_CAPTURE = Regex("\\bs(\\d{1,2})\\s*e(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
    private val NXM_CAPTURE = Regex("\\b(\\d{1,2})x(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
    private val SEASON_WORD_CAPTURE = Regex("\\bseason\\s*(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
    private val SEASON_CN_CAPTURE = Regex("第\\s*(\\d{1,3})\\s*[季部]")
    private val SEASON_ONLY_CAPTURE = Regex("\\bs(\\d{1,2})\\b", RegexOption.IGNORE_CASE)

    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        var text = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase()
        text = EXTENSION.replace(text, "")
        text = PAREN_TAGS.replace(text, " ")
        text = BRACKET_CHARS.replace(text, " ")
        text = SXXEXX.replace(text, " ")
        text = NXM.replace(text, " ")
        text = SEASON_WORD.replace(text, " ")
        text = SEASON_CN.replace(text, " ")
        text = SEASON_ONLY.replace(text, " ")
        text = RELEASE_TOKEN.replace(text, " ")
        text = RELEASE_GROUP.replace(text, " ")
        text = YEAR.replace(text, " ")
        text = NON_WORD.replace(text, " ")
        return text.trim().replace(WHITESPACE, " ")
    }

    fun parse(raw: String): ParsedTitle {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase()
        val year = YEAR.findAll(folded).lastOrNull()?.value?.toIntOrNull()

        var season: Int? = null
        var episode: Int? = null
        SXXEXX_CAPTURE.find(folded)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
        } ?: NXM_CAPTURE.find(folded)?.let {
            season = it.groupValues[1].toIntOrNull()
            episode = it.groupValues[2].toIntOrNull()
        } ?: SEASON_WORD_CAPTURE.find(folded)?.let {
            season = it.groupValues[1].toIntOrNull()
        } ?: SEASON_CN_CAPTURE.find(folded)?.let {
            season = it.groupValues[1].toIntOrNull()
        } ?: SEASON_ONLY_CAPTURE.find(folded)?.let {
            season = it.groupValues[1].toIntOrNull()
        }

        return ParsedTitle(
            title = normalize(raw),
            year = year,
            season = season,
            episode = episode,
        )
    }
}
