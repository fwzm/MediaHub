package com.mediahub.core.common.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 信封线格式与归档头共用的文本规则。
 *
 * 为什么不用 Kotlin 自带的 isBlank() / trim() / equals(ignoreCase = true)：这三个都跟着 JVM 的
 * Unicode 版本走，而且与 .NET 的定义不同，同一份字节会在两端得到不同的稳定码——
 *  - JVM 的空白集含 U+001C–U+001F、不含 U+0085；.NET 的 char.IsWhiteSpace 恰好相反；
 *  - JVM 的 String.trim() 只剥 <= U+0020，与它自己的空白集也不一致；
 *  - JVM 的 equalsIgnoreCase 会把 U+017F（长 s）与 U+212A（开尔文符号）折成 ASCII，
 *    .NET 的 OrdinalIgnoreCase 不会。
 * 两端要对同一份字节给出同一个码，规则就必须显式钉成一张表，而不是继承运行时默认。
 *
 * 这里实现的是 Unicode White_Space 属性（BMP 上 25 个码点，与 .NET char.IsWhiteSpace 逐码点相同）；
 * 大小写只折叠 ASCII A–Z（与 .NET OrdinalIgnoreCase 对 ASCII 目标串等价）。
 * Windows 侧的对应实现是 MediaHub.Sync.WireText，两端由黄金向量逐条对照。
 */
object WireText {

    /** Unicode White_Space 属性在 BMP 上的全部码点（升序，共 25 个，与 .NET char.IsWhiteSpace 一致）。 */
    val WHITESPACE_CODE_POINTS: IntArray = intArrayOf(
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D,
        0x0020, 0x0085, 0x00A0, 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004,
        0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
    )

    /** 单字符判定；补充平面不可能是空白，故只按 char 判。 */
    fun isWhitespace(character: Char): Boolean = WHITESPACE_CODE_POINTS.binarySearch(character.code) >= 0

    /** null、空串、或只由 Unicode White_Space 组成。 */
    fun isBlank(value: String?): Boolean {
        if (value == null || value.isEmpty()) return true
        return value.all { isWhitespace(it) }
    }

    /** 剥掉首尾的 Unicode White_Space；没有可剥的字符时原样返回入参。 */
    fun trim(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && isWhitespace(value[start])) start++
        while (end > start && isWhitespace(value[end - 1])) end--
        return if (start == 0 && end == value.length) value else value.substring(start, end)
    }

    /** 只折叠 ASCII A–Z 的等值比较；其余码元必须逐字相等。 */
    fun equalsAsciiIgnoreCase(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        for (index in left.indices) {
            if (foldAscii(left[index]) != foldAscii(right[index])) return false
        }
        return true
    }

    /** 含有未配对代理项（合法的代理对，如 emoji，返回 false）。 */
    fun hasLoneSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return true
                    index += 2
                }
                character.isLowSurrogate() -> return true
                else -> index++
            }
        }
        return false
    }

    /** 文档里任一字符串含未配对代理项（转义的 \ud800 之类）。 */
    fun containsLoneSurrogate(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.any { containsLoneSurrogate(it) }
        is JsonArray -> element.any { containsLoneSurrogate(it) }
        is JsonPrimitive -> element.isString && hasLoneSurrogate(element.content)
    }

    /** 只把 ASCII a–z 折成大写的 uppercase（配对指纹展示用；不碰非 ASCII）。 */
    fun uppercaseAscii(value: String): String {
        var changed = false
        for (character in value) {
            if (character in 'a'..'z') {
                changed = true
                break
            }
        }
        if (!changed) return value
        val builder = StringBuilder(value.length)
        for (character in value) builder.append(foldAsciiUpper(character))
        return builder.toString()
    }

    /** 只折叠 ASCII 的大小写不敏感「前缀」判定（配对链接前缀、code= 参数、localhost 特判共用）。 */
    fun startsWithAsciiIgnoreCase(value: String, prefix: String): Boolean {
        if (prefix.length > value.length) return false
        for (index in prefix.indices) {
            if (foldAscii(value[index]) != foldAscii(prefix[index])) return false
        }
        return true
    }

    /** 只把 ASCII A–Z 折成小写的 lowercase（接口名前缀分类用；不碰土耳其语 I、开尔文符号等）。 */
    fun lowercaseAscii(value: String): String {
        var changed = false
        for (character in value) {
            if (character in 'A'..'Z') {
                changed = true
                break
            }
        }
        if (!changed) return value
        val builder = StringBuilder(value.length)
        for (character in value) builder.append(foldAscii(character))
        return builder.toString()
    }

    /** 只折叠 ASCII 的字典序比较（排序用；返回负数/零/正数）。 */
    fun compareAsciiIgnoreCase(left: String, right: String): Int {
        val shared = minOf(left.length, right.length)
        for (index in 0 until shared) {
            val delta = foldAscii(left[index]).code - foldAscii(right[index]).code
            if (delta != 0) return delta
        }
        return left.length - right.length
    }

    private fun foldAscii(character: Char): Char =
        if (character >= 'A' && character <= 'Z') character + ('a' - 'A') else character

    private fun foldAsciiUpper(character: Char): Char =
        if (character >= 'a' && character <= 'z') character - ('a' - 'A') else character
}
