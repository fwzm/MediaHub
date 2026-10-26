package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText
import java.security.MessageDigest

/**
 * 对端公钥指纹：32 字节，规范形式是 64 位小写 hex。
 *
 * 与 Windows PairingFingerprint.cs 逐字对齐：
 * - 解析接受大写 hex，但内部统一规范化为小写（写回时也是小写）；
 * - 长度必须 64，且每个字符必须是 ASCII hex，否则拒绝；
 * - 展示形式是 16 组、每组 4 字符、大写、单空格分隔；
 * - 比较用常量时间。
 */
class PairingFingerprint private constructor(val hex: String) {

    /** 解码后的 32 字节（空指纹返回空数组）。 */
    val bytes: ByteArray
        get() {
            if (hex.isEmpty()) return ByteArray(0)
            val out = ByteArray(hex.length / 2)
            var index = 0
            while (index < out.size) {
                val high = hexDigit(hex[index * 2])
                val low = hexDigit(hex[index * 2 + 1])
                out[index] = ((high shl 4) or low).toByte()
                index += 1
            }
            return out
        }

    /** 空指纹。 */
    val isEmpty: Boolean
        get() = hex.isEmpty()

    /** 人类可读形式：16 组 4 字符、大写、单空格分隔。 */
    val displayString: String
        get() {
            if (hex.isEmpty()) return ""
            val upper = WireText.uppercaseAscii(hex)
            val parts = ArrayList<String>(16)
            var index = 0
            while (index < upper.length) {
                val end = minOf(index + 4, upper.length)
                parts.add(upper.substring(index, end))
                index = end
            }
            return parts.joinToString(" ")
        }

    /** 常量时间比较；任一侧为空返回 false。 */
    fun matches(other: PairingFingerprint?): Boolean {
        if (other == null) return false
        if (isEmpty || other.isEmpty) return false
        return MessageDigest.isEqual(bytes, other.bytes)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is PairingFingerprint && hex == other.hex)

    override fun hashCode(): Int = hex.hashCode()

    override fun toString(): String = "PairingFingerprint(" + hex + ")"

    companion object {
        /** hex 字符串长度。 */
        const val HEX_LENGTH: Int = 64

        /** 字节数。 */
        const val BYTE_LENGTH: Int = 32

        /** 空值单例。 */
        val EMPTY: PairingFingerprint = PairingFingerprint("")

        /** 由 32 字节构造；长度不符抛 IllegalArgumentException。 */
        fun fromBytes(bytes: ByteArray): PairingFingerprint {
            require(bytes.size == BYTE_LENGTH) {
                "指纹必须 " + BYTE_LENGTH + " 字节，实际 " + bytes.size
            }
            val builder = StringBuilder(HEX_LENGTH)
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                builder.append(HEX_DIGITS[value ushr 4])
                builder.append(HEX_DIGITS[value and 0x0F])
            }
            return PairingFingerprint(builder.toString())
        }

        /** 严格解析；不合规返回 null。 */
        fun tryParse(text: String?): PairingFingerprint? {
            if (text == null || WireText.isBlank(text)) return null
            if (text.length != HEX_LENGTH) return null
            for (character in text) {
                if (!isAsciiHexDigit(character)) return null
            }
            return PairingFingerprint(WireText.lowercaseAscii(text))
        }

        /** 解析；不合规抛 PairingContractException(bad-fingerprint)。 */
        fun parse(text: String?): PairingFingerprint =
            tryParse(text) ?: throw PairingContractException.badFingerprint(
                "指纹必须是 " + HEX_LENGTH + " 位 hex（长度 " + (text?.length ?: 0) + "）"
            )

        /** 是否是 ASCII hex 字符。 */
        fun isAsciiHexDigit(character: Char): Boolean =
            (character in '0'..'9') || (character in 'a'..'f') || (character in 'A'..'F')

        private const val HEX_DIGITS = "0123456789abcdef"

        private fun hexDigit(character: Char): Int = when (character) {
            in '0'..'9' -> character - '0'
            in 'a'..'f' -> character - 'a' + 10
            in 'A'..'F' -> character - 'A' + 10
            else -> throw IllegalArgumentException("非法 hex 字符")
        }
    }
}
