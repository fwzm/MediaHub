package com.mediahub.core.common.backup

import java.security.SecureRandom

/**
 * 备份文件序列化/反序列化管道（纯 JVM，Phase 1I-A）。
 *
 * 导出：payload JSON → PBKDF2 派生密钥 → AES-256-GCM(AAD) 加密 → envelope JSON → bytes
 * 导入：有界读取 bytes → envelope 验证（magic/版本/算法白名单/迭代范围/密文最短）
 *      → PBKDF2 重派生 → AES-256-GCM(AAD) 解密 → 内层版本校验 → payload JSON
 *
 * - 统一格式验证入口 [validateEnvelope]；合法 JSON ≠ 合法备份
 * - 错误分类：AuthenticationFailed（密码错误或密文被篡改，无法区分）/
 *   Corrupted（结构/Base64/nonce 异常）/ NotABackupFile / VersionTooNew
 * - 文件大小上限 [MAX_FILE_BYTES]（有界读取——SAF 读取阶段即限制，不全量加载任意大文件）
 */
object BackupSerializer {

    const val MAX_FILE_BYTES = 10 * 1024 * 1024 // 10MB
    const val MIN_CIPHERTEXT_BYTES = 16 // GCM tag 至少 16 字节

    /** 导出：加密并序列化为 `.mhbackup` 文件字节。 */
    fun export(payload: BackupDtos.BackupPayload, password: CharArray): ByteArray {
        val payloadJson = BackupDtos.encodePayload(payload).toByteArray(Charsets.UTF_8)
        val salt = BackupCrypto.randomSalt()
        val derived = BackupCrypto.deriveKey(password, salt, BackupCrypto.PBKDF2_ITERATIONS, BackupCrypto.PBKDF2_KEY_BITS)
        val nonce = BackupCrypto.randomNonce()
        val aad = BackupCrypto.aadFor(BackupCrypto.MAGIC, BackupCrypto.FORMAT_VERSION, derived.params.algorithm, derived.params.iterations)
        val ciphertext = BackupCrypto.encrypt(payloadJson, derived.key, nonce, aad)

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
        data class AuthenticationFailed(val cause: String) : ImportResult
        data class Corrupted(val reason: String) : ImportResult
        data class VersionTooNew(val fileVersion: Int, val readerVersion: Int) : ImportResult
        data object NotABackupFile : ImportResult
    }

    /** envelope 结构与参数统一验证（解密前）。 */
    private fun validateEnvelope(env: BackupFileFormat.Envelope): ImportResult? {
        if (env.magic != BackupCrypto.MAGIC) return ImportResult.NotABackupFile
        if (env.formatVersion > BackupCrypto.FORMAT_VERSION) {
            return ImportResult.VersionTooNew(env.formatVersion, BackupCrypto.MINIMUM_READER_VERSION)
        }
        if (env.kdfAlgorithm !in BackupCrypto.SUPPORTED_KDF_ALGORITHMS) {
            return ImportResult.Corrupted("不支持的 KDF 算法：${env.kdfAlgorithm}")
        }
        if (env.kdfIterations !in BackupCrypto.MIN_ITERATIONS..BackupCrypto.MAX_ITERATIONS) {
            return ImportResult.Corrupted("KDF 迭代数超出允许范围：${env.kdfIterations}")
        }
        if (env.kdfKeyLengthBits != BackupCrypto.PBKDF2_KEY_BITS) {
            return ImportResult.Corrupted("密钥长度必须 256 位")
        }
        val ct = runCatching { BackupFileFormat.decodeB64(env.ciphertextB64) }
            .getOrElse { return ImportResult.Corrupted("密文 Base64 无效") }
        if (ct.size < MIN_CIPHERTEXT_BYTES) {
            return ImportResult.Corrupted("密文过短（不足 GCM 标签长度）")
        }
        return null // 通过
    }

    /** 导入：有界读取 → 统一验证 → AAD 解密 → 内层版本校验。 */
    fun import(data: ByteArray, password: CharArray): ImportResult {
        if (data.size > MAX_FILE_BYTES) return ImportResult.Corrupted("文件超过 ${MAX_FILE_BYTES / 1024 / 1024}MB 上限")
        val text = String(data, Charsets.UTF_8)
        if (!text.contains(BackupCrypto.MAGIC)) return ImportResult.NotABackupFile

        val envelope = try {
            BackupFileFormat.decodeEnvelope(text)
        } catch (e: Exception) {
            return ImportResult.Corrupted("备份文件格式无法解析")
        }

        validateEnvelope(envelope)?.let { return it }

        val salt = try { BackupFileFormat.decodeB64(envelope.kdfSaltB64) } catch (e: Exception) { return ImportResult.Corrupted("salt 无效") }
        val nonce = try { BackupFileFormat.decodeB64(envelope.nonceB64) } catch (e: Exception) { return ImportResult.Corrupted("nonce 无效") }
        val ciphertext = try { BackupFileFormat.decodeB64(envelope.ciphertextB64) } catch (e: Exception) { return ImportResult.Corrupted("密文无效") }

        val aad = BackupCrypto.aadFor(envelope.magic, envelope.formatVersion, envelope.kdfAlgorithm, envelope.kdfIterations)

        val derived = BackupCrypto.deriveKey(password, salt, envelope.kdfIterations, envelope.kdfKeyLengthBits)

        val plaintext = try {
            BackupCrypto.decrypt(ciphertext, derived.key, nonce, aad)
        } catch (e: Exception) {
            // GCM AEADBadTagException：密码错误或密文被篡改，无法单凭异常区分
            return ImportResult.AuthenticationFailed("无法解密备份：密码不正确，或文件已损坏、被修改。")
        }

        val payload = try {
            BackupDtos.decodePayload(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            return ImportResult.Corrupted("备份内容解析失败")
        }

        // 内层版本校验
        if (payload.manifest.formatVersion > BackupCrypto.FORMAT_VERSION) {
            return ImportResult.VersionTooNew(payload.manifest.formatVersion, BackupCrypto.MINIMUM_READER_VERSION)
        }

        return ImportResult.Ok(payload)
    }
}
