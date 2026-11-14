package com.mediahub.core.danmaku

import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 容错弹幕解析：任何单条坏数据只丢弃该条，畸形/超长输入返回空列表，绝不抛异常
 * （条目 59「加载弹幕导致闪退」的第一道防线）。
 */
object DanmakuParser {

    const val MAX_TEXT_LENGTH: Int = 200
    const val MAX_PAYLOAD_CHARS: Int = 16 * 1024 * 1024

    private val XML_ROW = Regex(
        pattern = """<d\b[^>]*\bp\s*=\s*["']([^"']*)["'][^>]*>(.*?)</d>""",
        option = RegexOption.DOT_MATCHES_ALL,
    )
    private val NUMERIC_ENTITY = Regex("""&#(x?[0-9a-fA-F]+);""")
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 解析 B 站 XML：<d p="time,mode,font,color,...">text</d>。 */
    fun parseXml(payload: String, source: String = "xml"): List<DanmakuComment> =
        tolerate(payload) {
            val comments = ArrayList<DanmakuComment>()
            XML_ROW.findAll(it).forEachIndexed { index, match ->
                val p = match.groupValues[1]
                val text = cleanText(match.groupValues[2]) ?: return@forEachIndexed
                val parts = p.split(',')
                val timeMs = secondsToMillis(parts.getOrNull(0)) ?: return@forEachIndexed
                val mode = modeOf(parts.getOrNull(1))
                val fontSize = fontSizeOf(parts.getOrNull(2))
                val color = colorOf(parts.getOrNull(3))
                val rowId = parts.getOrNull(7)?.toLongOrNull()
                comments += DanmakuComment(
                    id = if (rowId != null && rowId > 0) "xml-$rowId" else "xml-$index",
                    timeMs = timeMs,
                    text = text,
                    mode = mode,
                    colorArgb = color,
                    fontSizeSp = fontSize,
                    source = source,
                )
            }
            comments.take(MAX_COMMENTS)
        }

    /** 解析弹弹play comment JSON：{"comments":[{"cid":..,"p":"秒,模式,颜色", "m":"文本"}]}。 */
    fun parseDanDanPlayJson(payload: String, source: String = "dandanplay"): List<DanmakuComment> =
        tolerate(payload) {
            val root = JSON.parseToJsonElement(it)
            val array: JsonArray = when (root) {
                is JsonObject -> root["comments"] as? JsonArray ?: return@tolerate emptyList()
                is JsonArray -> root
                else -> return@tolerate emptyList()
            }
            val comments = ArrayList<DanmakuComment>(array.size.coerceAtMost(MAX_COMMENTS))
            array.forEachIndexed { index, element ->
                if (comments.size >= MAX_COMMENTS) return@tolerate comments
                val obj = element as? JsonObject ?: return@forEachIndexed
                val p = obj.string("p") ?: return@forEachIndexed
                val text = cleanText(obj.string("m") ?: return@forEachIndexed) ?: return@forEachIndexed
                val parts = p.split(',')
                val timeMs = secondsToMillis(parts.getOrNull(0)) ?: return@forEachIndexed
                val id = obj.string("cid") ?: "ddp-$index"
                comments += DanmakuComment(
                    id = "ddp-$id",
                    timeMs = timeMs,
                    text = text,
                    mode = modeOf(parts.getOrNull(1)),
                    colorArgb = colorOf(parts.getOrNull(2)),
                    fontSizeSp = fontSizeOf(parts.getOrNull(3)),
                    source = source,
                )
            }
            comments
        }

    /** 自动识别 XML / JSON（缓存写回的是原始载荷，读取时无需知道来源）。 */
    fun parseAuto(payload: String, source: String = "auto"): List<DanmakuComment> {
        val trimmed = payload.trimStart()
        if (trimmed.isEmpty()) return emptyList()
        return if (trimmed.startsWith("<")) {
            parseXml(payload, source)
        } else {
            parseDanDanPlayJson(payload, source)
        }
    }

    private inline fun tolerate(
        payload: String,
        block: (String) -> List<DanmakuComment>,
    ): List<DanmakuComment> {
        if (payload.isEmpty() || payload.length > MAX_PAYLOAD_CHARS) return emptyList()
        return try {
            block(payload)
        } catch (_: Throwable) {
            // 解析层是外部数据边界：畸形 XML/JSON/超大文本不得让播放器闪退。
            emptyList()
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun secondsToMillis(raw: String?): Long? {
        val seconds = raw?.trim()?.toDoubleOrNull() ?: return null
        if (seconds.isNaN() || seconds.isInfinite() || seconds < 0) return null
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
    }

    private fun modeOf(raw: String?): DanmakuMode = when (raw?.trim()) {
        "4" -> DanmakuMode.BOTTOM
        "5" -> DanmakuMode.TOP
        else -> DanmakuMode.SCROLL
    }

    private fun fontSizeOf(raw: String?): Int {
        val size = raw?.trim()?.toIntOrNull() ?: return DanmakuComment.DEFAULT_FONT_SIZE_SP
        return size.coerceIn(12, 64)
    }

    private fun colorOf(raw: String?): Int {
        val value = raw?.trim()?.toLongOrNull() ?: return DanmakuComment.DEFAULT_COLOR_ARGB
        return if (value > 0xFFFFFFL) {
            value.toInt()
        } else {
            (0xFF000000L or (value and 0xFFFFFFL)).toInt()
        }
    }

    private fun cleanText(raw: String): String? {
        val unescaped = unescapeEntities(raw)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .trim()
        if (unescaped.isEmpty()) return null
        return unescaped.take(MAX_TEXT_LENGTH)
    }

    private fun unescapeEntities(raw: String): String {
        if ('&' !in raw) return raw
        var value = raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
        value = NUMERIC_ENTITY.replace(value) { match ->
            val token = match.groupValues[1]
            val code = if (token.startsWith("x", ignoreCase = true)) {
                token.drop(1).toIntOrNull(16)
            } else {
                token.toIntOrNull()
            }
            code?.takeIf { it in 0..0x10FFFF }?.let { String(Character.toChars(it)) } ?: match.value
        }
        return value
    }

    private const val MAX_COMMENTS = 20_000
}
