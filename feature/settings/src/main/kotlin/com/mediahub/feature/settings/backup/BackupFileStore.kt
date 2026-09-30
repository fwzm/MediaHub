package com.mediahub.feature.settings.backup

import android.content.Context
import android.net.Uri
import com.mediahub.core.common.AppDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            require(maxBytes >= 0)
            try {
                currentCoroutineContext().ensureActive()
                val uri = Uri.parse(uriString)
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@withContext BackupFileStore.ReadResult.ReadFailed("目标文件不可读（内容提供方拒绝访问）")
                input.use { stream ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var out = ByteArray(BUFFER_BYTES)
                    var total = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        // 一个额外字节足以确认超限；不向提供方多读取整个缓冲区。
                        val requested = minOf(buffer.size.toLong(), maxBytes.toLong() - total + 1).toInt()
                        val n = stream.read(buffer, 0, requested)
                        if (n < 0) break
                        if (n == 0) continue
                        if (n > maxBytes - total) {
                            // 超限立即终止：不再继续读入，避免为超大文件分配内存
                            return@use BackupFileStore.ReadResult.TooLarge
                        }
                        if (total + n > out.size) {
                            out = out.copyOf((total + n).coerceAtLeast(out.size * 2).coerceAtMost(maxBytes))
                        }
                        System.arraycopy(buffer, 0, out, total, n)
                        total += n
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // ContentProvider 的异常可能包含完整 URI、路径或凭据，禁止展示原文。
                BackupFileStore.ReadResult.ReadFailed("无法读取所选备份文件，请重新选择文件后重试")
            }
        }

    override suspend fun write(uriString: String, bytes: ByteArray): BackupFileStore.WriteResult =
        withContext(dispatchers.io) {
            try {
                currentCoroutineContext().ensureActive()
                val uri = Uri.parse(uriString)
                // 空流表示没有写入任何数据，不得返回成功。
                val output = context.contentResolver.openOutputStream(uri)
                    ?: return@withContext BackupFileStore.WriteResult.StreamUnavailable
                output.use { stream ->
                    currentCoroutineContext().ensureActive()
                    stream.write(bytes)
                    stream.flush()
                }
                BackupFileStore.WriteResult.Written
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BackupFileStore.WriteResult.WriteFailed("无法写入备份文件，请重新选择保存位置后重试")
            }
        }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }

    /** SAF 文件显示名：优先 OpenableColumns.DISPLAY_NAME（downloads uri 的 lastPathSegment 是数字 id）；file:// 回退取路径末段。 */
    private fun resolveDisplayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
        // Windows file:// URI 的路径分隔符是 '\'，两种都取末段
        val fallback = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
        return queried ?: fallback ?: "backup.mhb"
    }
}
