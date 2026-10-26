package com.mediahub.core.database.sync

import com.mediahub.core.common.archive.ArchiveException
import com.mediahub.core.common.archive.ArchiveKdf
import com.mediahub.core.common.archive.ArchiveWriter

/**
 * W6-64：本机导出的一封信封，落成文件时的三种产物。
 *
 * 明文与归档走的是同一个导出（[SourceSyncExport]），差别只在有没有口令：
 * 口令为 null 时原样交出 [SourceSyncExport.json]（与 W6-42 发送路径逐字节相同），
 * 有口令时由 [ArchiveWriter] 包成 .mharchive。
 */
sealed interface SourceSyncArchiveArtifact {

    /** 明文信封（口令缺省）：[json] 就是导出的原始文本。 */
    data class Plain(
        val json: String,
        val sourceCount: Int,
        val skippedCount: Int,
    ) : SourceSyncArchiveArtifact

    /** 加密归档：字节可直接写进用户选定的文件。 */
    data class Sealed(
        val bytes: ByteArray,
        val sourceCount: Int,
        val skippedCount: Int,
        /** 信封与归档头共用的时间戳（对齐 Windows Export 的 now 语义）。 */
        val createdAtEpochMs: Long,
        val iterations: Int,
    ) : SourceSyncArchiveArtifact

    /** 写不出来：reason 是 ArchiveError 的稳定码，一个都不改写。 */
    data class Rejected(
        val reason: String,
        val message: String?,
    ) : SourceSyncArchiveArtifact
}

/**
 * W6-64：把 [SourceSyncExport] 变成可落盘的产物（对齐 Windows SourceSyncSession.Export 的 passphrase 分支）。
 *
 * 三条语义：
 * - 口令为 null ⇒ 明文，JSON 与发送路径同一份文本，不重新编码；
 * - 口令非 null ⇒ ArchiveWriter.write，迭代数取 ArchiveKdf 默认值（600 000），
 *   createdAtEpochMs 取信封自己的时间戳，使归档头与信封内时间一致（与 Windows 相同）；
 * - 口令策略 / 载荷上限 / 凭据拒绝都由 ArchiveWriter 判定，这里只把 ArchiveException 折成 Rejected。
 *
 * 本对象不碰文件系统：写哪、怎么写由界面层（SAF）负责。
 */
object SourceSyncArchiveExport {

    /** SAF 保存器的默认文件名（Android 侧统一 .mharchive，见 docs/android-source-sync-transfer.md §36）。 */
    const val DEFAULT_FILE_NAME: String = "sync-envelope.mharchive"

    fun of(export: SourceSyncExport, passphrase: String?): SourceSyncArchiveArtifact {
        val sourceCount = export.envelope.sources.size
        val skippedCount = export.skippedServerIds.size
        if (passphrase == null) {
            return SourceSyncArchiveArtifact.Plain(export.json, sourceCount, skippedCount)
        }
        val createdAtEpochMs = export.envelope.createdAtEpochMs
        return try {
            val bytes = ArchiveWriter.write(
                envelope = export.envelope,
                passphrase = passphrase,
                iterations = ArchiveKdf.DEFAULT_ITERATIONS,
                createdAtEpochMs = createdAtEpochMs,
            )
            SourceSyncArchiveArtifact.Sealed(
                bytes = bytes,
                sourceCount = sourceCount,
                skippedCount = skippedCount,
                createdAtEpochMs = createdAtEpochMs,
                iterations = ArchiveKdf.DEFAULT_ITERATIONS,
            )
        } catch (error: ArchiveException) {
            SourceSyncArchiveArtifact.Rejected(error.reason, error.message)
        }
    }
}
