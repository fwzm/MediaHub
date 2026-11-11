package com.mediahub.core.common.sync

/**
 * 与 System.Text.Json 的 Utf8JsonWriter 逐字节相同的 JSON 文本层（W6-72）。
 *
 * 为什么不用 kotlinx.serialization 直接输出：两端读写同一份 sync-baseline 文档，而
 * Utf8JsonWriter 的默认编码器只转义 8 个 ASCII 字符（双引号、与号、单引号、加号、小于、大于、反斜杠、反引号），
 * 控制字符用短转义，其余一切非 ASCII（含代理对）写成大写反斜杠-u 四位形式；缩进用 CRLF 加两个空格、且不写尾随换行。
 * 这些差异会让同一份清单在两端字节不同，所以这里手工复刻那套编码。
 *
 * 本文件的 quote 与配对设备文档（PairedDeviceDocument.quote）保持同一张转义表，避免两处各写一遍。
 */
internal object WireJson {
    /**
     * 粗略统计一段 JSON 文本的嵌套深度是否超过 [maxDepth]，字符串内容不计。
     *
     * 用途只有一个：在把整份文本交给 kotlinx 解析器之前先挡掉敌意文件。与 Windows 的
     * JsonDocumentOptions.MaxDepth 语义等价（超深即 baseline-too-deep），但不必先建出文档树。
     */
    fun exceedsDepth(text: String, maxDepth: Int): Boolean {
        val quoteChar = 34.toChar()
        val escapeChar = 92.toChar()
        var depth = 0
        var inString = false
        var escaped = false
        for (character in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == escapeChar -> escaped = true
                    character == quoteChar -> inString = false
                }
                continue
            }
            when (character) {
                quoteChar -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > maxDepth) return true
                }
                '}', ']' -> if (depth > 0) depth -= 1
            }
        }
        return false
    }

    private const val HEX_UPPER = "0123456789ABCDEF"

    private val BACKSLASH: Char = 92.toChar()
    private val QUOTE: Char = 34.toChar()

    /** Utf8JsonWriter（默认 encoder）的字符串编码规则。 */
    fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append(QUOTE)
        for (character in value) {
            when (character.code) {
                0x22 -> builder.append(BACKSLASH).append("u0022")
                0x26 -> builder.append(BACKSLASH).append("u0026")
                0x27 -> builder.append(BACKSLASH).append("u0027")
                0x2B -> builder.append(BACKSLASH).append("u002B")
                0x3C -> builder.append(BACKSLASH).append("u003C")
                0x3E -> builder.append(BACKSLASH).append("u003E")
                0x5C -> builder.append(BACKSLASH).append(BACKSLASH)
                0x60 -> builder.append(BACKSLASH).append("u0060")
                0x08 -> builder.append(BACKSLASH).append("b")
                0x09 -> builder.append(BACKSLASH).append("t")
                0x0A -> builder.append(BACKSLASH).append("n")
                0x0C -> builder.append(BACKSLASH).append("f")
                0x0D -> builder.append(BACKSLASH).append("r")
                else -> if (character.code < 0x20 || character.code >= 0x7F) {
                    builder.append(BACKSLASH).append("u")
                    builder.append(HEX_UPPER[(character.code shr 12) and 0xF])
                    builder.append(HEX_UPPER[(character.code shr 8) and 0xF])
                    builder.append(HEX_UPPER[(character.code shr 4) and 0xF])
                    builder.append(HEX_UPPER[character.code and 0xF])
                } else {
                    builder.append(character)
                }
            }
        }
        builder.append(QUOTE)
        return builder.toString()
    }
}

/**
 * 按 Utf8JsonWriter 的排版规则写 JSON：缩进模式等于 CRLF 加两空格；紧凑模式一个字节都不多。
 *
 * 只支持本工程需要的四种值：字符串、整数、布尔、null。空对象与空数组写成一对括号（不换行），与 Utf8JsonWriter 一致。
 */
internal class WireJsonTextWriter(private val indented: Boolean) {

    private val builder = StringBuilder()
    private val frames = ArrayList<Frame>()
    private var pendingName: String? = null

    private class Frame(val array: Boolean) {
        var count: Int = 0
    }

    fun startObject(): WireJsonTextWriter {
        beforeValue()
        builder.append("{")
        frames.add(Frame(array = false))
        return this
    }

    fun endObject(): WireJsonTextWriter = endContainer('}')

    fun startArray(): WireJsonTextWriter {
        beforeValue()
        builder.append("[")
        frames.add(Frame(array = true))
        return this
    }

    fun endArray(): WireJsonTextWriter = endContainer(']')

    fun name(name: String): WireJsonTextWriter {
        pendingName = name
        return this
    }

    fun value(text: String): WireJsonTextWriter {
        beforeValue()
        builder.append(WireJson.quote(text))
        return this
    }

    fun value(number: Int): WireJsonTextWriter {
        beforeValue()
        builder.append(number.toString())
        return this
    }

    fun value(number: Long): WireJsonTextWriter {
        beforeValue()
        builder.append(number.toString())
        return this
    }

    fun value(flag: Boolean): WireJsonTextWriter {
        beforeValue()
        builder.append(if (flag) "true" else "false")
        return this
    }

    fun nullValue(): WireJsonTextWriter {
        beforeValue()
        builder.append("null")
        return this
    }

    override fun toString(): String = builder.toString()

    private fun beforeValue() {
        val frame = frames.lastOrNull() ?: return
        if (frame.count > 0) {
            builder.append(",")
        }
        frame.count += 1
        if (indented) {
            builder.append(13.toChar()).append(10.toChar())
            repeat(frames.size) { builder.append(INDENT) }
        }
        val pending = pendingName ?: return
        builder.append(WireJson.quote(pending))
        builder.append(if (indented) ": " else ":")
        pendingName = null
    }

    private fun endContainer(closing: Char): WireJsonTextWriter {
        val frame = frames.removeAt(frames.size - 1)
        if (indented && frame.count > 0) {
            builder.append(13.toChar()).append(10.toChar())
            repeat(frames.size) { builder.append(INDENT) }
        }
        builder.append(closing)
        return this
    }

    private companion object {
        const val INDENT = "  "
    }
}
