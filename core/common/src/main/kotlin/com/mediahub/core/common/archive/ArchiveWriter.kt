package com.mediahub.core.common.archive

import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 离线加密归档（ADR-S005）的写端，逐字对齐 Windows `MediaHub.Sync.EncryptedArchiveWriter`。
 *
 * 写侧的三条硬规则：
 * - **永不写凭据**：信封声明 canSendSecrets，或任一来源带 secrets 块，都直接拒绝（不静默剥掉）；
 * - 口令策略先于一切加密动作；
 * - 每次写都换新 salt 与 nonce，nonce 绝不复用（GCM 复用 nonce 等于自毁密钥）。
 *
 * 输出布局：`magic(8) | headerLength(4, big-endian) | headerJson | ciphertext | tag(16)`，
 * 头部原字节作为 AAD 参与认证。
 */
object ArchiveWriter {

    private val secureRandom = SecureRandom()

    /**
     * 加密一个同步信封。
     *
     * @param envelope 要写进归档的信封（不含凭据）。
     * @param passphrase 口令；必须通过 [ArchivePassphrase.tryValidate]。
     * @param iterations PBKDF2 轮数，默认（也是最低）600000。
     * @param createdAtEpochMs 头部时间戳；给 null 用当前时刻（测试传固定值以得到稳定向量）。
     * @return 完整归档字节。
     * @throws ArchiveException 口令不合规、声称携带凭据、迭代数过低或载荷过大。
     */
    fun write(
        envelope: SourceSyncEnvelope,
        passphrase: String?,
        iterations: Int = ArchiveKdf.DEFAULT_ITERATIONS,
        createdAtEpochMs: Long? = null,
    ): ByteArray {
        // ① 口令策略：空口令 / 弱口令在任何加密动作之前就拒绝。
        ArchivePassphrase.validate(passphrase)

        // ② 永不写凭据：标志位与真实 secrets 块都查，缺一不可。
        if (envelope.capabilities.canSendSecrets || carriesSecrets(envelope)) {
            throw ArchiveException(
                ArchiveError.SECRETS_NOT_ALLOWED,
                "An archive never carries credentials; write an envelope with secrets disabled.",
            )
        }

        // ③ 迭代数下限：写侧先挡，读侧还会再挡一次。
        if (iterations < ArchiveKdf.MIN_ITERATIONS) {
            throw ArchiveException(
                ArchiveError.INVALID_ITERATIONS,
                "The iteration count must be at least " + ArchiveKdf.MIN_ITERATIONS + ".",
            )
        }

        // ④ 载荷就是同步信封的线格式字节（与传输用的是同一份编码器）。
        val payload = SourceSyncCodec.encode(envelope).toByteArray(Charsets.UTF_8)
        if (payload.size > ArchiveHeader.MAX_PAYLOAD_BYTES) {
            throw ArchiveException(
                ArchiveError.PAYLOAD_TOO_LARGE,
                "The payload exceeds " + ArchiveHeader.MAX_PAYLOAD_BYTES + " bytes.",
            )
        }

        val salt = ByteArray(ArchiveKdf.SALT_SIZE).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(ArchiveHeader.NONCE_SIZE).also { secureRandom.nextBytes(it) }
        val header = ArchiveHeader(
            version = ArchiveHeader.CURRENT_VERSION,
            kdf = ArchiveKdf.PBKDF2_SHA256_NAME,
            iterations = iterations,
            saltBase64 = java.util.Base64.getEncoder().encodeToString(salt),
            nonceBase64 = java.util.Base64.getEncoder().encodeToString(nonce),
            createdAtEpochMs = createdAtEpochMs ?: System.currentTimeMillis(),
        )
        val headerJson = ArchiveHeaderCodec.serialize(header)
        val prefix = buildPrefix(headerJson)

        var key: ByteArray? = null
        try {
            key = ArchiveKdf.deriveKey(passphrase!!, salt, iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(prefix)
            val ciphertextAndTag = try {
                cipher.doFinal(payload)
            } catch (error: GeneralSecurityException) {
                throw IllegalStateException("AES-GCM encryption is unavailable on this device.", error)
            }
            val archive = ByteArray(prefix.size + ciphertextAndTag.size)
            prefix.copyInto(archive, 0)
            ciphertextAndTag.copyInto(archive, prefix.size)
            return archive
        } finally {
            key?.fill(0)
            payload.fill(0)
        }
    }

    /** magic + 大端 int32 长度 + 头部字节；这三段整体就是 AES-GCM 的 AAD。 */
    internal fun buildPrefix(headerJson: ByteArray): ByteArray {
        val magic = ArchiveHeader.CURRENT_MAGIC.toByteArray(Charsets.US_ASCII)
        val prefix = ByteArray(magic.size + 4 + headerJson.size)
        magic.copyInto(prefix, 0)
        ByteBuffer.wrap(prefix, magic.size, 4).putInt(headerJson.size)
        headerJson.copyInto(prefix, magic.size + 4)
        return prefix
    }

    /**
     * 任一来源带**非 null 值**的 secrets 块即为「携带凭据」。
     *
     * 判据必须排除 JSON null：Windows 的原话是 ValueKind != Null，即 secrets: null 不算携带。
     * Kotlin 里 JsonNull 是一个对象而不是 Kotlin null，只写 != null 会把显式 null 误判成凭据，
     * 于是两端对同一信封给出不同结论（Windows 允许写、Android 拒绝）——这是跨端契约上的真缺陷，
     * 由 ArchiveRoundTripTest 的 a_json_null_secrets_block_is_not_credentials_on_either_side 钉住。
     */
    private fun carriesSecrets(envelope: SourceSyncEnvelope): Boolean =
        envelope.hasArchiveCredentialBlocks()
}
