package com.mediahub.core.download

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 下载目录布局（确定性路径：索引丢失时仍能按 key 前缀清理与找回）。 */
internal object DownloadPaths {
    private val unsafeChars = Regex("[^A-Za-z0-9._-]")

    fun keyHash(key: DownloadTaskKey): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${key.serverId}\u0000${key.itemId}".toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(HASH_LENGTH)
    }

    fun serverDir(root: File, serverId: String): File {
        val safe = serverId.replace(unsafeChars, "_").ifBlank { "server" }
        return File(root, safe)
    }

    fun sanitizeFileName(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val safe = base.replace(unsafeChars, "_").trim('.', '_')
        return safe.ifBlank { "download.bin" }
    }

    /** 最终文件：`<root>/<serverId>/<keyHash>_<fileName>`。 */
    fun finalFile(root: File, serverId: String, itemId: String, fileName: String): File =
        File(serverDir(root, serverId), "${keyHash(DownloadTaskKey(serverId, itemId))}_${sanitizeFileName(fileName)}")

    /** 未完成文件：`<final>.part`（断点续传的追加目标）。 */
    fun partialFile(finalFile: File): File = File(finalFile.parentFile, "${finalFile.name}.part")

    fun relativePath(root: File, file: File): String =
        file.relativeTo(root).invariantSeparatorsPath

    fun fileForRelativePath(root: File, relativePath: String): File = File(root, relativePath)

    private const val HASH_LENGTH = 16
}

/** 下载索引持久化：单 JSON 文件 + 临时文件原子替换，避免半写损坏。 */
internal class FileDownloadIndex(private val rootDir: File) {
    private val indexFile = File(rootDir, INDEX_FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Map<DownloadTaskKey, DownloadRecord> {
        if (!indexFile.isFile) return emptyMap()
        return runCatching {
            val stored = json.decodeFromString<List<StoredRecord>>(indexFile.readText(Charsets.UTF_8))
            stored.associate { it.toKey() to it.toRecord() }
        }.getOrElse { emptyMap() }
    }

    fun save(records: Map<DownloadTaskKey, DownloadRecord>) {
        rootDir.mkdirs()
        val text = json.encodeToString(records.values.map { StoredRecord.from(it) })
        val tmp = File(rootDir, "$INDEX_FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        runCatching {
            Files.move(
                tmp.toPath(),
                indexFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            Files.move(tmp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Serializable
    private data class StoredRecord(
        val serverId: String,
        val itemId: String,
        val fileName: String,
        val relativePath: String,
        val mimeType: String? = null,
        val sizeBytes: Long? = null,
        val status: String,
        val updatedAtEpochMs: Long,
        val failureKind: String? = null,
        val message: String? = null,
    ) {
        fun toKey() = DownloadTaskKey(serverId, itemId)

        fun toRecord(): DownloadRecord = DownloadRecord(
            key = toKey(),
            fileName = fileName,
            relativePath = relativePath,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
            updatedAtEpochMs = updatedAtEpochMs,
            failureKind = failureKind?.let { runCatching { DownloadFailureKind.valueOf(it) }.getOrNull() },
            message = message,
        )

        companion object {
            fun from(record: DownloadRecord) = StoredRecord(
                serverId = record.key.serverId,
                itemId = record.key.itemId,
                fileName = record.fileName,
                relativePath = record.relativePath,
                mimeType = record.mimeType,
                sizeBytes = record.sizeBytes,
                status = record.status.name,
                updatedAtEpochMs = record.updatedAtEpochMs,
                failureKind = record.failureKind?.name,
                message = record.message,
            )
        }
    }

    private companion object {
        const val INDEX_FILE_NAME = "downloads.json"
    }
}
