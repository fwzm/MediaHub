package com.mediahub.core.common.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 配对载荷里的 32 字节一次性 challenge，规范形式是带 '=' 填充的标准 base64。
 *
 * 与 Windows PairingChallenge.cs 逐字对齐：
 * - 长度固定 32 字节，解析时非 32 字节一律拒绝；
 * - 只接受规范拼写：解码后重新编码必须与原串完全相同（空格、缺填充、换位写法都拒绝）；
 * - 比较用常量时间，避免把 challenge 当普通字符串比较；
 * - toString 不暴露内容（日志里出现 challenge 等于泄漏一次性凭据）。
 */
class PairingChallenge private constructor(val base64: String) {

    /** 解码后的 32 字节（空 challenge 返回空数组）。 */
    val bytes: ByteArray
        get() = if (base64.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(base64)

    /** 空 challenge。 */
    val isEmpty: Boolean
        get() = base64.isEmpty()

    /**
     * 常量时间比较。任一侧为空都返回 false（对齐 Windows PairingChallenge.Matches）。
     */
    fun matches(other: PairingChallenge?): Boolean {
        if (other == null) return false
        if (isEmpty || other.isEmpty) return false
        return MessageDigest.isEqual(bytes, other.bytes)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is PairingChallenge && base64 == other.base64)

    override fun hashCode(): Int = base64.hashCode()

    override fun toString(): String = "PairingChallenge(<redacted>)"

    companion object {
        /** 字节数。 */
        const val BYTE_LENGTH: Int = 32

        /** 空值单例（载荷缺省时使用）。 */
        val EMPTY: PairingChallenge = PairingChallenge("")

        private val random: SecureRandom = SecureRandom()

        /**
         * 由 32 字节构造；长度不符抛 IllegalArgumentException（编程错误，不是契约错误）。
         */
        fun fromBytes(bytes: ByteArray): PairingChallenge {
            require(bytes.size == BYTE_LENGTH) {
                "challenge 必须 " + BYTE_LENGTH + " 字节，实际 " + bytes.size
            }
            return PairingChallenge(Base64.getEncoder().encodeToString(bytes))
        }

        /** CSPRNG 生成 32 字节。 */
        fun createRandom(): PairingChallenge {
            val bytes = ByteArray(BYTE_LENGTH)
            random.nextBytes(bytes)
            return fromBytes(bytes)
        }

        /**
         * 严格解析；任何不合规返回 null（不抛）。
         */
        fun tryParse(text: String?): PairingChallenge? {
            if (text == null || text.isBlank()) return null
            val decoded: ByteArray = try {
                Base64.getDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (decoded.size != BYTE_LENGTH) return null
            val canonical = Base64.getEncoder().encodeToString(decoded)
            if (canonical != text) return null
            return PairingChallenge(canonical)
        }

        /**
         * 解析；不合规抛 PairingContractException(bad-challenge)。
         */
        fun parse(text: String?): PairingChallenge =
            tryParse(text) ?: throw PairingContractException.badChallenge(
                "challenge 不是 32 字节规范 base64（长度 " + (text?.length ?: 0) + "）"
            )
    }
}
