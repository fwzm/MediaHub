package com.mediahub.core.database.repository

import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.dao.SubtitleMemoryDao
import com.mediahub.core.database.entity.SubtitleMemoryEntity
import com.mediahub.model.MediaItem
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 字幕匹配记忆的读取/写入（P2 字幕中心切片一）。
 * 键由 [SubtitleMemoryKeys] 派生；手动选择覆盖旧记忆（后写优先）。
 */
interface SubtitleMemoryStore {
    suspend fun recall(versionKey: String): SubtitleMemoryEntry?
    suspend fun remember(entry: SubtitleMemoryEntry)
    suspend fun forget(versionKey: String)
}

/** 一条匹配记忆。 */
data class SubtitleMemoryEntry(
    val versionKey: String,
    val serverId: String,
    val subtitleId: String?,
    val offsetMs: Long,
    val updatedAtEpochMs: Long,
)

/**
 * 视频版本指纹（匹配记忆键设计）：
 *
 * `serverId | itemId | sizeBytes` 优先 —— 同一影片的不同版本（不同文件大小）
 * 各自独立记忆，偏移/字幕选择互不串用；
 * size 未知（多数服务器型数据源）时回退 `serverId | itemId | path:<sha256(path)>`
 * —— 路径不同的版本（如 1080p/4K 双文件）仍然隔离。
 */
object SubtitleMemoryKeys {

    fun forItem(item: MediaItem): String {
        val size = item.sizeBytes
        val version = if (size != null && size > 0) {
            "s:$size"
        } else {
            "p:" + sha256(item.path ?: item.id)
        }
        return "${item.serverId}|${item.id}|$version"
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

@Singleton
class SubtitleMemoryRepository @Inject constructor(
    db: AppDatabase,
) : SubtitleMemoryStore {
    private val dao: SubtitleMemoryDao = db.subtitleMemoryDao()

    override suspend fun recall(versionKey: String): SubtitleMemoryEntry? =
        dao.get(versionKey)?.toEntry()

    override suspend fun remember(entry: SubtitleMemoryEntry) {
        dao.upsert(
            SubtitleMemoryEntity(
                versionKey = entry.versionKey,
                serverId = entry.serverId,
                subtitleId = entry.subtitleId,
                offsetMs = entry.offsetMs,
                updatedAtEpochMs = entry.updatedAtEpochMs.takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun forget(versionKey: String) {
        dao.delete(versionKey)
    }

    /** 删除某服务器的全部字幕记忆（媒体源删除级联，与进度清理同纪律）。 */
    suspend fun deleteByServer(serverId: String) {
        dao.deleteByServer(serverId)
    }

    private fun SubtitleMemoryEntity.toEntry() = SubtitleMemoryEntry(
        versionKey = versionKey,
        serverId = serverId,
        subtitleId = subtitleId,
        offsetMs = offsetMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
