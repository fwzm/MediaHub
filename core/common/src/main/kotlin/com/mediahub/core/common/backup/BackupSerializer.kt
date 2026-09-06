package com.mediahub.core.common.backup

import java.security.SecureRandom

/**
 * 备份文件序列化/反序列化管道（纯 JVM，Phase 1I-A）。
 *
 * 导出：payload JSON → PBKDF2 派生密钥 → AES-256-GCM 加密 → envelope JSON → bytes
 * 导入：bytes → envelope JSON → PBKDF2 重派生 → AES-256-GCM 解密 → payload JSON
 *
 * [restore] 时调用方须校验 formatVersion/minimumReaderVersion 并构建恢复预览。
 */
object BackupSerializer {

    /** 导出：加密并序列化为 `.mhbackup` 文件字节。 */
    fun export(payload: BackupDtos.BackupPayload, password: CharArray): ByteArray {
        val payloadJson = BackupDtos.encodePayload(payload).toByteArray(Charsets.UTF_8)
        val salt = BackupCrypto.randomSalt()
        val derived = BackupCrypto.deriveKey(password, salt, BackupCrypto.PBKDF2_ITERATIONS, BackupCrypto.PBKDF2_KEY_BITS)
        val nonce = BackupCrypto.randomNonce()
        val ciphertext = BackupCrypto.encrypt(payloadJson, derived.key, nonce)

        val envelope = BackupFileFormat.Envelope(
            magic = BackupCrypto.MAGIC,
            formatVersion = BackupCrypto.FORMAT_VERSION,
            kdfAlgorithm = derived.params.algorithm,
            kdfSaltB64 = BackupFileFormat.encodeB64(derived.params.salt),
            kdfIterations = derived.params.iterations,
            kdfKeyLengthBits = derived.params.keyLengthBits,
            nonceB64 = BackupFileFormat.encodeB64(nonce),
            ciphertextB64 = BackupFileFormat.encodeB64(ciphertext),
        )
        return BackupFileFormat.encodeEnvelope(envelope).toByteArray(Charsets.UTF_8)
    }

    /** 导入结果。 */
    sealed interface ImportResult {
        data class Ok(val payload: BackupDtos.BackupPayload) : ImportResult
        data class InvalidPassword(val cause: Exception) : ImportResult
        data class Corrupted(val reason: String) : ImportResult
        data class VersionTooNew(val fileVersion: Int, val readerVersion: Int) : ImportResult
        data object NotABackupFile : ImportResult
    }

    /** 导入：反序列化并解密。 */
    fun import(data: ByteArray, password: CharArray): ImportResult {
        val text = try {
            String(data, Charsets.UTF_8)
        } catch (e: Exception) {
            return ImportResult.Corrupted("无法解码文件内容")
        }

        if (!text.contains(BackupCrypto.MAGIC)) return ImportResult.NotABackupFile

        val envelope = try {
            BackupFileFormat.decodeEnvelope(text)
        } catch (e: Exception) {
            return ImportResult.Corrupted("备份文件格式无法解析：${e.message}")
        }

        if (envelope.magic != BackupCrypto.MAGIC) return ImportResult.NotABackupFile
        if (envelope.formatVersion > BackupCrypto.FORMAT_VERSION) {
            return ImportResult.VersionTooNew(envelope.formatVersion, BackupCrypto.MINIMUM_READER_VERSION)
        }

        val salt = try {
            BackupFileFormat.decodeB64(envelope.kdfSaltB64)
        } catch (e: Exception) {
            return ImportResult.Corrupted("KDF salt 无效")
        }
        val nonce = try {
            BackupFileFormat.decodeB64(envelope.nonceB64)
        } catch (e: Exception) {
            return ImportResult.Corrupted("nonce 无效")
        }
        val ciphertext = try {
            BackupFileFormat.decodeB64(envelope.ciphertextB64)
        } catch (e: Exception) {
            return ImportResult.Corrupted("密文无效")
        }

        val derived = try {
            BackupCrypto.deriveKey(password, salt, envelope.kdfIterations, envelope.kdfKeyLengthBits)
        } catch (e: Exception) {
            return ImportResult.Corrupted("密钥派生失败：${e.message}")
        }

        val plaintext = try {
            BackupCrypto.decrypt(ciphertext, derived.key, nonce)
        } catch (e: Exception) {
            return ImportResult.InvalidPassword(e)
        }

        val payload = try {
            BackupDtos.decodePayload(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            return ImportResult.Corrupted("备份内容 JSON 解析失败：${e.message}")
        }

        return ImportResult.Ok(payload)
    }
}
