package com.mediahub.core.common.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份加密（Phase 1I-A）：PBKDF2WithHmacSHA256 密钥派生 + AES-256-GCM 认证加密。
 *
 * - KDF 参数版本化并设上下界（防恶意备份以极端参数消耗资源）；
 * - 每次加密使用安全随机 12 字节 nonce + 32 字节 salt；
 * - GCM 认证标签 128 位，同时保护机密性与完整性；
 * - 禁止自行设计密码算法；Base64 仅做传输编码，不作为加密。
 */
object BackupCrypto {

    const val FORMAT_VERSION = 1
    const val MINIMUM_READER_VERSION = 1
    const val MAGIC = "MHBK"

    /** OWASP 2025 密码存储指引 PBKDF2-HMAC-SHA256 推荐最低迭代数。 */
    const val PBKDF2_ITERATIONS = 600_000
    const val PBKDF2_SALT_BYTES = 32
    const val PBKDF2_KEY_BITS = 256
    const val GCM_NONCE_BYTES = 12
    const val GCM_TAG_BITS = 128

    /** 算法白名单。 */
    val SUPPORTED_KDF_ALGORITHMS = setOf("PBKDF2WithHmacSHA256")

    private val secureRandom = SecureRandom()

    /** KDF 参数（随备份序列化，恢复时按同一参数重派生密钥）。 */
    data class KdfParams(
        val algorithm: String = "PBKDF2WithHmacSHA256",
        val salt: ByteArray,
        val iterations: Int,
        val keyLengthBits: Int,
    )

    /** 派生结果。 */
    data class DerivedKey(val key: SecretKeySpec, val params: KdfParams)

    /**
     * 派生加密密钥（含参数白名单与安全边界校验）。
     * PBEKeySpec 在 finally 中 clearPassword（异常路径同样清理）。
     * [testIterations] 允许测试注入低成本参数（不影响生产默认值）。
     */
    fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int,
        testIterations: Int? = null,
    ): DerivedKey {
        require(salt.size >= PBKDF2_SALT_BYTES) { "salt 太短" }
        val effectiveMin = testIterations ?: MIN_ITERATIONS
        require(iterations in effectiveMin..MAX_ITERATIONS) { "iterations 超出安全范围 [$effectiveMin..$MAX_ITERATIONS]" }
        require(keyLengthBits == 256) { "仅支持 256 位密钥" }
        require(algorithmWhitelistContains()) { "不支持的 KDF 算法" }
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val key = factory.generateSecret(spec)
            return DerivedKey(
                key = SecretKeySpec(key.encoded, "AES"),
                params = KdfParams(
                    algorithm = "PBKDF2WithHmacSHA256",
                    salt = salt.copyOf(),
                    iterations = iterations,
                    keyLengthBits = keyLengthBits,
                ),
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun algorithmWhitelistContains() = SUPPORTED_KDF_ALGORITHMS.contains("PBKDF2WithHmacSHA256")

    const val MIN_ITERATIONS = 10_000
    const val MAX_ITERATIONS = 2_000_000

    /** 生成随机 salt。 */
    fun randomSalt(): ByteArray = ByteArray(PBKDF2_SALT_BYTES).also { secureRandom.nextBytes(it) }

    /** 生成随机 nonce。 */
    fun randomNonce(): ByteArray = ByteArray(GCM_NONCE_BYTES).also { secureRandom.nextBytes(it) }

    /**
     * 构造 AAD（Additional Authenticated Data）：备份文件头参数的稳定编码。
     * 加解密必须使用同一函数与同一输入——AAD 不匹配时 GCM 解密抛 AEADBadTagException。
     * 稳定编码不依赖 JSON 属性顺序。
     */
    fun aadFor(magic: String, formatVersion: Int, kdfAlgorithm: String, kdfIterations: Int): ByteArray =
        "$magic|$formatVersion|$kdfAlgorithm|$kdfIterations".toByteArray(Charsets.UTF_8)

    /** AES-256-GCM 加密（AAD 纳入认证：头部参数被篡改时解密抛异常）。 */
    fun encrypt(plaintext: ByteArray, key: SecretKeySpec, nonce: ByteArray, aad: ByteArray? = null): ByteArray {
        require(nonce.size == GCM_NONCE_BYTES) { "nonce 必须 ${GCM_NONCE_BYTES} 字节" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(plaintext)
    }

    /** AES-256-GCM 解密（AAD 或密文篡改时抛 AEADBadTagException）。 */
    fun decrypt(ciphertext: ByteArray, key: SecretKeySpec, nonce: ByteArray, aad: ByteArray? = null): ByteArray {
        require(nonce.size == GCM_NONCE_BYTES) { "nonce 必须 ${GCM_NONCE_BYTES} 字节" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(ciphertext)
    }
}
