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

    /** OWASP 2023 推荐 PBKDF2-SHA256 最低迭代数。 */
    const val PBKDF2_ITERATIONS = 310_000
    const val PBKDF2_SALT_BYTES = 32
    const val PBKDF2_KEY_BITS = 256
    const val GCM_NONCE_BYTES = 12
    const val GCM_TAG_BITS = 128

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

    /** 派生加密密钥。 */
    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBits: Int): DerivedKey {
        require(salt.size >= PBKDF2_SALT_BYTES) { "salt 太短" }
        require(iterations in 10_000..2_000_000) { "iterations 超出安全范围" }
        require(keyLengthBits == 256) { "仅支持 256 位密钥" }
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec)
        spec.clearPassword()
        return DerivedKey(
            key = SecretKeySpec(key.encoded, "AES"),
            params = KdfParams(
                algorithm = "PBKDF2WithHmacSHA256",
                salt = salt.copyOf(),
                iterations = iterations,
                keyLengthBits = keyLengthBits,
            ),
        )
    }

    /** 生成随机 salt。 */
    fun randomSalt(): ByteArray = ByteArray(PBKDF2_SALT_BYTES).also { secureRandom.nextBytes(it) }

    /** 生成随机 nonce。 */
    fun randomNonce(): ByteArray = ByteArray(GCM_NONCE_BYTES).also { secureRandom.nextBytes(it) }

    /** AES-256-GCM 加密（认证加密：密文篡改时解密抛 AEADBadTagException）。 */
    fun encrypt(plaintext: ByteArray, key: SecretKeySpec, nonce: ByteArray): ByteArray {
        require(nonce.size == GCM_NONCE_BYTES) { "nonce 必须 ${GCM_NONCE_BYTES} 字节" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    /** AES-256-GCM 解密（认证失败抛异常——完整性保护）。 */
    fun decrypt(ciphertext: ByteArray, key: SecretKeySpec, nonce: ByteArray): ByteArray {
        require(nonce.size == GCM_NONCE_BYTES) { "nonce 必须 ${GCM_NONCE_BYTES} 字节" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }
}
