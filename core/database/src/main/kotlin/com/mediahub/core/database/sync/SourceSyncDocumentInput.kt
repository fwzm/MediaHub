package com.mediahub.core.database.sync

import com.mediahub.core.common.archive.ArchiveError
import com.mediahub.core.common.archive.ArchiveHeader
import com.mediahub.core.common.archive.ArchiveReader
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope

/**
 * W6-63：把「用户选中的文件字节」分成明文信封与离线加密归档（.mharchive）两条路。
 *
 * 判定次序逐字对齐 Windows SyncCliRunner.TryResolveEnvelope：
 * 先看前 8 字节是不是 MHSYNCA1（归档）；是归档而没有口令 ⇒ passphrase-required；
 * 有口令才进入 ArchiveReader 的结构 / 口令 / 载荷三阶段判定；不是归档则按 UTF-8 文本原样走既有明文解码。
 *
 * 只碰字节，不写盘、不记日志，绝不回显口令或载荷。passphrase-required 与 Windows CLI 同名，是跨端可对照的
 * 稳定码；其余失败码全部来自 ArchiveError（两端同名同序），本类一个都不改写。
 */
object SourceSyncDocumentInput {

    /** 归档需要口令但没有给：与 Windows CLI 的 passphrase-required 同名同义。 */
    const val PASSPHRASE_REQUIRED: String = "passphrase-required"

    private val MAGIC: ByteArray = ArchiveHeader.CURRENT_MAGIC.toByteArray(Charsets.US_ASCII)

    /** 前 8 字节等于归档 magic 才算归档；比 magic 还短的字节一律按明文处理（同 Windows LooksLikeArchive）。 */
    fun looksLikeArchive(documentBytes: ByteArray): Boolean {
        if (documentBytes.size < MAGIC.size) return false
        for (index in MAGIC.indices) {
            if (documentBytes[index] != MAGIC[index]) return false
        }
        return true
    }

    /** 把字节分成四类：Plaintext / Decrypted / PassphraseRequired / Rejected。 */
    fun resolve(documentBytes: ByteArray, passphrase: String?): SourceSyncDocumentResolution {
        if (!looksLikeArchive(documentBytes)) {
            return SourceSyncDocumentResolution.Plaintext(documentBytes.toString(Charsets.UTF_8))
        }
        if (passphrase == null) {
            return SourceSyncDocumentResolution.PassphraseRequired(
                "the input is an encrypted archive; a passphrase is required",
            )
        }
        val (envelope, reason) = ArchiveReader.tryRead(documentBytes, passphrase)
        if (envelope == null) {
            val code = reason ?: ArchiveError.MALFORMED_PAYLOAD
            return SourceSyncDocumentResolution.Rejected(code, ArchiveReader.describe(code))
        }
        val json = runCatching { SourceSyncCodec.encode(envelope) }.getOrElse {
            return SourceSyncDocumentResolution.Rejected(
                ArchiveError.MALFORMED_PAYLOAD,
                ArchiveReader.describe(ArchiveError.MALFORMED_PAYLOAD),
            )
        }
        return SourceSyncDocumentResolution.Decrypted(envelope, json)
    }
}

/** SourceSyncDocumentInput.resolve 的四种结论。 */
sealed interface SourceSyncDocumentResolution {

    /** 不是归档：UTF-8 解出的文本，原样交给既有明文解码（保留宽松解码语义）。 */
    data class Plaintext(val text: String) : SourceSyncDocumentResolution

    /** 归档已解开：信封本体 + 重新编码的明文 JSON（交给既有网关 / 预览）。 */
    data class Decrypted(val envelope: SourceSyncEnvelope, val json: String) : SourceSyncDocumentResolution

    /** 是归档但没有口令。 */
    data class PassphraseRequired(val message: String?) : SourceSyncDocumentResolution

    /** 归档被拒：reason 是 ArchiveError 的稳定码（不会走到这里的是 passphrase-required 由上层另判）。 */
    data class Rejected(val reason: String, val message: String?) : SourceSyncDocumentResolution
}
