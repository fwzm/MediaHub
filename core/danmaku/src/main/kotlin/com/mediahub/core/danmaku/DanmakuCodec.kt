package com.mediahub.core.danmaku

/**
 * 解析结果的本地缓存编码。缓存的是「已清洗的弹幕」而不是原始载荷，
 * 避免每次播放都重新解析超大 XML；格式自带版本头，损坏行逐行丢弃。
 */
object DanmakuCodec {

    private const val HEADER = "MEDIAHUB-DANMAKU-1"
    private const val FIELD_COUNT = 7

    fun encode(comments: List<DanmakuComment>): String = buildString {
        appendLine(HEADER)
        comments.forEach { comment ->
            append(comment.id.sanitize())
            append('\t').append(comment.timeMs)
            append('\t').append(comment.mode.name)
            append('\t').append(comment.colorArgb)
            append('\t').append(comment.fontSizeSp)
            append('\t').append(comment.source.sanitize())
            append('\t').append(comment.text.sanitize())
            append('\n')
        }
    }

    fun decode(payload: String): List<DanmakuComment> {
        if (payload.isEmpty() || payload.length > DanmakuParser.MAX_PAYLOAD_CHARS) return emptyList()
        return try {
            val lines = payload.lineSequence()
            if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
            val comments = ArrayList<DanmakuComment>()
            lines.drop(1).forEach { line ->
                if (comments.size >= MAX_COMMENTS) return@forEach
                val fields = line.split('\t', limit = FIELD_COUNT)
                if (fields.size != FIELD_COUNT) return@forEach
                val timeMs = fields[1].toLongOrNull() ?: return@forEach
                val mode = runCatching { DanmakuMode.valueOf(fields[2]) }.getOrNull() ?: return@forEach
                val color = fields[3].toIntOrNull() ?: return@forEach
                val fontSize = fields[4].toIntOrNull() ?: return@forEach
                val text = fields[6].trim()
                if (text.isEmpty()) return@forEach
                comments += DanmakuComment(
                    id = fields[0],
                    timeMs = timeMs.coerceAtLeast(0L),
                    text = text,
                    mode = mode,
                    colorArgb = color,
                    fontSizeSp = fontSize,
                    source = fields[5],
                )
            }
            comments
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun String.sanitize(): String =
        replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private const val MAX_COMMENTS = 20_000
}
