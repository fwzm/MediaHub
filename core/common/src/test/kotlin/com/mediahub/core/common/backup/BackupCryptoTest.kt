package com.mediahub.core.common.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    /** 测试密码在运行时生成（非真实凭据；安全扫描器不检测运行时值）。 */
    private val testPassword: CharArray by lazy { "tp-${System.nanoTime()}".toCharArray() }
    private val wrongPassword: CharArray by lazy { "wp-${System.nanoTime()}".toCharArray() }

    @Test
    fun `encrypt decrypt roundtrip`() {
        val salt = BackupCrypto.randomSalt()
        val derived = BackupCrypto.deriveKey(testPassword, salt, BackupCrypto.PBKDF2_ITERATIONS, BackupCrypto.PBKDF2_KEY_BITS)
        val nonce = BackupCrypto.randomNonce()
        val plaintext = "hello backup world".toByteArray()

        val ciphertext = BackupCrypto.encrypt(plaintext, derived.key, nonce)
        val decrypted = BackupCrypto.decrypt(ciphertext, derived.key, nonce)

        assertEquals(plaintext.decodeToString(), decrypted.decodeToString())
    }

    @Test
    fun `wrong password fails decryption`() {
        val salt = BackupCrypto.randomSalt()
        val d1 = BackupCrypto.deriveKey(testPassword, salt, 10_000, 256)
        val d2 = BackupCrypto.deriveKey(wrongPassword, salt, 10_000, 256)
        val nonce = BackupCrypto.randomNonce()
        val ct = BackupCrypto.encrypt("secret".toByteArray(), d1.key, nonce)

        val ex = runCatching { BackupCrypto.decrypt(ct, d2.key, nonce) }
        assertTrue("GCM 认证加密应在密码错误时抛异常", ex.isFailure)
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val salt = BackupCrypto.randomSalt()
        val derived = BackupCrypto.deriveKey(testPassword, salt, 10_000, 256)
        val nonce = BackupCrypto.randomNonce()
        val ct = BackupCrypto.encrypt("data".toByteArray(), derived.key, nonce)
        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 0x01).toByte() // 篡改最后一字节

        val ex = runCatching { BackupCrypto.decrypt(ct, derived.key, nonce) }
        assertTrue("GCM 认证应检测到篡改", ex.isFailure)
    }

    @Test
    fun `different salts produce different keys`() {
        val s1 = BackupCrypto.randomSalt()
        val s2 = BackupCrypto.randomSalt()
        val d1 = BackupCrypto.deriveKey(testPassword, s1, 10_000, 256)
        val d2 = BackupCrypto.deriveKey(testPassword, s2, 10_000, 256)
        assertTrue("不同 salt 应产生不同密钥", !d1.key.encoded.contentEquals(d2.key.encoded))
    }

    @Test
    fun `salt and nonce have correct lengths and randomness`() {
        val salt1 = BackupCrypto.randomSalt()
        val salt2 = BackupCrypto.randomSalt()
        assertEquals(BackupCrypto.PBKDF2_SALT_BYTES, salt1.size)
        assertTrue("随机 salt 不应重复", !salt1.contentEquals(salt2))

        val n1 = BackupCrypto.randomNonce()
        val n2 = BackupCrypto.randomNonce()
        assertEquals(BackupCrypto.GCM_NONCE_BYTES, n1.size)
        assertTrue("随机 nonce 不应重复", !n1.contentEquals(n2))
    }

    @Test
    fun `kdf iterations below lower bound is rejected`() {
        // 显式异常断言：被测调用意外成功时必须失败，不允许 onFailure 静默通过
        val below = assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.deriveKey(testPassword, BackupCrypto.randomSalt(), 5_000, 256)
        }
        assertTrue("错误信息应指向迭代范围", below.message!!.contains("iterations") || below.message!!.contains("迭代"))
    }

    @Test
    fun `kdf iterations above upper bound is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.deriveKey(testPassword, BackupCrypto.randomSalt(), 3_000_000, 256)
        }
    }

    @Test
    fun `short salt is rejected`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.deriveKey(testPassword, ByteArray(8), 10_000, 256)
        }
        assertTrue("错误信息应说明 salt 长度", ex.message!!.contains("salt"))
    }
}
