package com.mediahub.core.common.archive

import com.mediahub.core.common.sync.WireText
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 归档的密钥派生，逐字对齐 Windows `MediaHub.Sync.ArchiveKdf`。
 *
 * ADR-S005 D2 登记的偏离：ADR-S001 D6 要求 Argon2id，但两端都不许引第三方依赖，
 * 因此实际实现是 PBKDF2-HMAC-SHA256、600000 轮、16 字节 salt、32 字节密钥。
 * kdf 名字写在明文头部里，读端按名字分派——将来加 Argon2id 不需要升格式版本（D5）。
 */
object ArchiveKdf {

    /** 目前唯一认识的 kdf 名。 */
    const val PBKDF2_SHA256_NAME: String = "pbkdf2-sha256"

    const val SALT_SIZE: Int = 16
    const val KEY_SIZE: Int = 32

    /** OWASP 2025 对 PBKDF2-HMAC-SHA256 的最低建议轮数，也是本实现的写侧默认值。 */
    const val MIN_ITERATIONS: Int = 600_000
    const val DEFAULT_ITERATIONS: Int = MIN_ITERATIONS

    /**
     * 只认 `pbkdf2-sha256`（先按 [WireText.trim] 剥 Unicode White_Space、再只折叠 ASCII 大小写）。
     * 认不出来返回 false，**不回落默认值**：回落会让「将来用新 kdf 写的文件」在旧读端上被当成口令错，
     * 比直接报 unknown-kdf 难查得多。
     *
     * 不用 lowercase()：JVM 的 lowercase() 会把 U+212A（开尔文符号）折成 k，而 .NET 的
     * OrdinalIgnoreCase 不会，同一份头部会在两端给出不同的 kdf 判定。
     */
    fun tryParse(name: String?): Boolean {
        if (name == null) return false
        return WireText.equalsAsciiIgnoreCase(WireText.trim(name), PBKDF2_SHA256_NAME)
    }

    /**
     * 派生 32 字节密钥。参数不合法是编程错误（不是用户输入错误），故用 require 抛参数异常，
     * 与 Windows 的 ArgumentException / ArgumentOutOfRangeException 同义。
     */
    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        require(salt.size == SALT_SIZE) { "The salt must be " + SALT_SIZE + " bytes." }
        require(iterations >= MIN_ITERATIONS) {
            "The iteration count must be at least " + MIN_ITERATIONS + "."
        }
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_SIZE * 8)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
