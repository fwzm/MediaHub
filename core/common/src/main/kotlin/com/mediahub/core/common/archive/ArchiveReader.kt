package com.mediahub.core.common.archive

import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 离线加密归档（ADR-S005）的读端，逐字对齐 Windows `MediaHub.Sync.EncryptedArchiveReader`。
 *
 * 三类职责严格分层，失败按「需不需要密钥」分家：
 * - 结构错误（magic / 头部 / 版本 / kdf / 迭代数 / 密文长度）只看字节，**不需要口令**；
 * - 口令策略错误在解密之前报；
 * - 解密与载荷解析错误在解密之后报。
 *
 * 判定次序就是外部可观察优先级：**框架错误优先于口令错误**——用户拿到「这个文件是更新版本写的」
 * 比拿到「你没输入口令」有用得多（Windows 注释里的原话）。
 *
 * 纯内存 API：不碰文件、不写盘；同一个 reader 可以反复调用（无可变状态）。
 */
object ArchiveReader {

    /** 读头部。结构性失败抛 [ArchiveException]，reason 是真实的结构码（不只是 magic-mismatch）。 */
    fun readHeader(archive: ByteArray): ArchiveHeader {
        layoutOf(archive)?.let { return it.header }
        val reason = lastReason ?: ArchiveError.CORRUPT_HEADER
        throw ArchiveException(reason, describe(reason))
    }

    /** 读头部：成功返回 header，失败返回 null（不区分具体原因，调用方要用原因时走 [tryRead]）。 */
    fun tryReadHeader(archive: ByteArray): ArchiveHeader? = layoutOf(archive)?.header

    /** 读并解密归档；失败抛 [ArchiveException]，reason 是 [ArchiveError] 里的稳定码。 */
    fun read(archive: ByteArray, passphrase: String?): SourceSyncEnvelope {
        val layout = layoutOf(archive)
            ?: throw ArchiveException(lastReason ?: ArchiveError.CORRUPT_HEADER, describe(lastReason ?: ArchiveError.CORRUPT_HEADER))
        val envelope = decrypt(layout, archive, passphrase)
        if (envelope == null) {
            val reason = lastReason ?: ArchiveError.MALFORMED_PAYLOAD
            throw ArchiveException(reason, describe(reason))
        }
        return envelope
    }

    /**
     * 读并解密归档；失败返回 `(null, 稳定码)`。
     * 这是测试与 CLI 的主入口：它们需要码，不需要异常。
     */
    fun tryRead(archive: ByteArray, passphrase: String?): Pair<SourceSyncEnvelope?, String?> {
        val layout = layoutOf(archive)
        if (layout == null) return null to lastReason
        val envelope = decrypt(layout, archive, passphrase)
        return envelope to if (envelope == null) lastReason else null
    }

    /** 把稳定码翻译成面向用户的英文句子；**绝不引用载荷字节、长度或口令**。 */
    fun describe(reason: String): String = when {
        reason == ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED ->
            "The archive could not be decrypted: the passphrase is wrong or the file has been modified."
        reason == ArchiveError.SECRETS_NOT_ALLOWED ->
            "The archive claims to carry credentials; refusing to import it."
        reason.startsWith(ArchiveError.MALFORMED_PAYLOAD) ->
            "The archive decrypted but does not hold a readable source envelope."
        else -> "The archive could not be read: " + reason + "."
    }

    // ------------------------------------------------------------------ 结构层

    /** 结构层结果：头部 + 密文偏移 + 密文长度 + AAD。 */
    private class Layout(val header: ArchiveHeader, val aad: ByteArray, val cipherLength: Int)

    /**
     * 只解析字节结构，不做任何解密。判定次序逐条对齐 Windows TryReadLayout。
     *
     * 成功时 [lastReason] 保持 null；失败时写入具体码并返回 null。
     */
    private fun layoutOf(archive: ByteArray): Layout? {
        lastReason = null
        val magic = ArchiveHeader.CURRENT_MAGIC.toByteArray(Charsets.US_ASCII)
        val headerOffset = magic.size + 4

        // ① 长度不足或 magic 不符：空文件也走这一条。
        if (archive.size < magic.size) {
            lastReason = ArchiveError.MAGIC_MISMATCH
            return null
        }
        if (!archive.copyOfRange(0, magic.size).contentEquals(magic)) {
            lastReason = ArchiveError.MAGIC_MISMATCH
            return null
        }

        // ② 连长度字段都不完整。
        if (archive.size < headerOffset) {
            lastReason = ArchiveError.CORRUPT_HEADER
            return null
        }

        // ③ 头部长度字段本身不合理，或声称的长度超出剩余字节。
        val headerLength = ByteBuffer.wrap(archive, magic.size, 4).int
        if (headerLength <= 0 || headerLength > ArchiveHeader.MAX_HEADER_BYTES ||
            headerLength > archive.size - headerOffset
        ) {
            lastReason = ArchiveError.CORRUPT_HEADER
            return null
        }

        // ④ 头部 JSON 解析。
        val headerBytes = archive.copyOfRange(headerOffset, headerOffset + headerLength)
        val header = ArchiveHeaderCodec.tryParse(headerBytes)
        if (header == null) {
            lastReason = ArchiveError.CORRUPT_HEADER
            return null
        }

        // ⑤ 版本先于 kdf：告诉用户「文件是更新版本写的」比「kdf 不认识」有用。
        if (header.version != ArchiveHeader.CURRENT_VERSION) {
            lastReason = ArchiveError.unsupportedVersion(header.version)
            return null
        }

        // ⑥ kdf 名不认识：不回落默认值。
        if (!ArchiveKdf.tryParse(header.kdf)) {
            lastReason = ArchiveError.unknownKdf(header.kdf)
            return null
        }

        // ⑦ 迭代数低于下限：这是「降迭代数攻击」的入口，读侧必须自己再挡一次。
        if (header.iterations < ArchiveKdf.MIN_ITERATIONS) {
            lastReason = ArchiveError.INVALID_ITERATIONS
            return null
        }

        // ⑧ salt / nonce 的 base64 与长度。
        val salt = decodeBase64(header.saltBase64, ArchiveKdf.SALT_SIZE)
        val nonce = decodeBase64(header.nonceBase64, ArchiveHeader.NONCE_SIZE)
        if (salt == null || nonce == null) {
            lastReason = ArchiveError.CORRUPT_HEADER
            return null
        }

        // ⑨ 密文长度。
        val trailer = headerOffset + headerLength
        val cipherLength = archive.size - trailer - ArchiveHeader.TAG_SIZE
        if (cipherLength <= 0) {
            lastReason = ArchiveError.CORRUPT_CIPHERTEXT
            return null
        }
        if (cipherLength > ArchiveHeader.MAX_PAYLOAD_BYTES) {
            lastReason = ArchiveError.PAYLOAD_TOO_LARGE
            return null
        }

        // ⑩ AAD 就是 magic + 长度 + 头部原字节（含顺序与空白），逐字节绑定。
        return Layout(header, archive.copyOfRange(0, trailer), cipherLength)
    }

    // ------------------------------------------------------------------ 解密层

    private fun decrypt(layout: Layout, archive: ByteArray, passphrase: String?): SourceSyncEnvelope? {
        // 口令策略在解密之前：非法口令根本不派生密钥（也就不需要 600000 轮的计算）。
        val policy = ArchivePassphrase.tryValidate(passphrase)
        if (policy != null) {
            lastReason = policy
            return null
        }

        val salt = decodeBase64(layout.header.saltBase64, ArchiveKdf.SALT_SIZE)!!
        val nonce = decodeBase64(layout.header.nonceBase64, ArchiveHeader.NONCE_SIZE)!!
        val key = ArchiveKdf.deriveKey(passphrase!!, salt, layout.header.iterations)
        val cipherOffset = layout.aad.size
        val tagOffset = cipherOffset + layout.cipherLength
        val ciphertextAndTag = archive.copyOfRange(cipherOffset, tagOffset + ArchiveHeader.TAG_SIZE)
        var plaintext: ByteArray? = null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            try {
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
                cipher.updateAAD(layout.aad)
                plaintext = cipher.doFinal(ciphertextAndTag)
            } catch (error: AEADBadTagException) {
                // 口令错与文件被篡改在密码学上不可区分，故意同一个码。
                lastReason = ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED
                return null
            } catch (error: GeneralSecurityException) {
                lastReason = ArchiveError.WRONG_PASSPHRASE_OR_TAMPERED
                return null
            }
            return decodePayload(plaintext!!)
        } finally {
            key.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun decodePayload(plaintext: ByteArray): SourceSyncEnvelope? {
        val json = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext))
                .toString()
        } catch (error: CharacterCodingException) {
            lastReason = ArchiveError.MALFORMED_PAYLOAD
            return null
        }

        val decoded = SourceSyncCodec.tryDecode(json)
        val envelope = when (decoded) {
            is SourceSyncCodec.DecodeResult.Ok -> decoded.envelope
            is SourceSyncCodec.DecodeResult.Failed -> {
                lastReason = ArchiveError.MALFORMED_PAYLOAD
                return null
            }
        }

        // 读侧与写侧同一立场：归档声称携带凭据就拒绝，而不是静默剥掉。
        if (envelope.capabilities.canSendSecrets) {
            lastReason = ArchiveError.SECRETS_NOT_ALLOWED
            return null
        }
        lastReason = null
        return envelope
    }

    private fun decodeBase64(value: String?, expectedLength: Int): ByteArray? {
        val decoded = try {
            Base64.getDecoder().decode(value ?: return null)
        } catch (error: IllegalArgumentException) {
            return null
        }
        return if (decoded.size == expectedLength) decoded else null
    }

    /**
     * 上一次 [tryRead] / [tryReadHeader] 的失败原因。
     *
     * Kotlin 没有 out 参数，公开 API 用 [tryRead] 返回 Pair；这个字段只服务于内部 helper 与
     * 需要「先看原因再决定文案」的调用方。它是**单线程**语义（与 Windows 的 out 参数同义），
     * 跨线程共享时请只用 [read] / [tryRead] 的返回值。
     */
    @Volatile
    var lastReason: String? = null
        private set
}
