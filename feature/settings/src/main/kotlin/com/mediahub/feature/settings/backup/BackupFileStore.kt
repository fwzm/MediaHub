package com.mediahub.feature.settings.backup

import android.content.Context
import android.net.Uri
import com.mediahub.core.common.AppDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * 备份文件操作层（Phase 1I review）：SAF 读写与主线程解耦。
 *
 * - 读取分块有界：超过 [MAX_FILE_BYTES] 立即终止并返回 [ReadResult.TooLarge]，
 *   不先把整个流读入内存再检查；
 * - 写入把 `openOutputStream` 返回 null 视为失败（空流 = 没有写入任何数据 ≠ 成功），
 *   写入异常与关闭异常均归为失败；
 * - 调度器经 [AppDispatchers] 注入，UI 层只处理 URI 与结果。
 */
interface BackupFileStore {

    /** 读取所选备份文件（有界）。[uriString] 为 SAF 返回的 uri.toString()。 */
    suspend fun readBounded(uriString: String, maxBytes: Int = BackupSerializerLimits.MAX_FILE_BYTES): ReadResult

    /** 将导出字节写入用户选定的目标。 */
    suspend fun write(uriString: String, bytes: ByteArray): WriteResult

    sealed interface ReadResult {
        data class Read(val bytes: ByteArray, val fileName: String) : ReadResult
        data object TooLarge : ReadResult
        data object Empty : ReadResult
        data class ReadFailed(val cause: String) : ReadResult
    }

    sealed interface WriteResult {
        data object Written : WriteResult
        /** 目标流不可用（openOutputStream 返回 null）——未写入任何数据。 */
        data object StreamUnavailable : WriteResult
        data class WriteFailed(val cause: String) : WriteResult
    }
}

/** 与 BackupSerializer.MAX_FILE_BYTES 一致（feature 层不直接依赖序列化器常量）。 */
object BackupSerializerLimits {
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
}

class ProductionBackupFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: AppDispatchers,
) : BackupFileStore {

    override suspend fun readBounded(uriString: String, maxBytes: Int): BackupFileStore.ReadResult =
        withContext(dispatchers.io) {
            val uri = Uri.parse(uriString)
            val input = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                return@withContext BackupFileStore.ReadResult.ReadFailed("无法打开所选文件：${e.message}")
            } ?: return@withContext BackupFileStore.ReadResult.ReadFailed("目标文件不可读（内容提供方拒绝访问）")

            input.use { stream ->
                val buffer = ByteArray(BUFFER_BYTES)
                var out = ByteArray(BUFFER_BYTES)
                var total = 0
                try {
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        if (total + n > maxBytes) {
                            // 超限立即终止：不再继续读入，避免为超大文件分配内存
                            return@use BackupFileStore.ReadResult.TooLarge
                        }
                        if (total + n > out.size) {
                            out = out.copyOf((total + n).coerceAtLeast(out.size * 2).coerceAtMost(maxBytes))
                        }
                        System.arraycopy(buffer, 0, out, total, n)
                        total += n
                    }
                } catch (e: Exception) {
                    return@use BackupFileStore.ReadResult.ReadFailed("读取文件失败：${e.message}")
                }
                if (total == 0) {
                    BackupFileStore.ReadResult.Empty
                } else {
                    BackupFileStore.ReadResult.Read(
                        bytes = out.copyOf(total),
                        fileName = resolveDisplayName(uri),
                    )
                }
            }
        }

    override suspend fun write(uriString: String, bytes: ByteArray): BackupFileStore.WriteResult =
        withContext(dispatchers.io) {
            val uri = Uri.parse(uriString)
            val output = try {
                context.contentResolver.openOutputStream(uri)
            } catch (e: Exception) {
                return@withContext BackupFileStore.WriteResult.WriteFailed("无法打开保存位置：${e.message}")
            }
            // openOutputStream 允许返回 null：此时没有写入任何数据，必须报告失败
                ?: return@withContext BackupFileStore.WriteResult.StreamUnavailable

            try {
                output.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                }
                BackupFileStore.WriteResult.Written
            } catch (e: Exception) {
                BackupFileStore.WriteResult.WriteFailed("写入备份失败：${e.message}")
            }
        }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }

    /** SAF 文件显示名：优先 OpenableColumns.DISPLAY_NAME（downloads uri 的 lastPathSegment 是数字 id）。 */
    private fun resolveDisplayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
        return queried ?: uri.lastPathSegment ?: "backup.mhb"
    }
}
