package com.mediahub.core.download

import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * 下载内核 + 状态机 + 本地缓存索引（ADR-009 的离线下载独立生命周期）。
 *
 * - 状态流转见 [DownloadStatus]；每次流转都持久化，进程重启后可恢复。
 * - 断点续传：`.part` + HTTP `Range`；服务器不支持 Range（200）时自动整体重下。
 * - 索引只保存非敏感字段；临时签名 URL / 请求头 / Cookie 永不落库（ADR-003）。
 * - 网络路径不依赖“直连可用性”判断：不预检、不限定网络类型，VPN/代理下照常下载。
 */
class DownloadManager(
    val rootDir: File,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val sourceResolver: DownloadSourceResolver? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : DownloadedMediaLocator {

    private val lock = Any()
    private val index = FileDownloadIndex(rootDir)
    private val _records = MutableStateFlow(index.load())
    private val transport = HttpDownloadTransport(httpClient)
    private val activeJobs = ConcurrentHashMap<DownloadTaskKey, Job>()
    private val stopIntent = ConcurrentHashMap<DownloadTaskKey, DownloadStatus>()

    /** 当前全部下载记录（只读快照；状态变化时整体替换）。 */
    val records: StateFlow<Map<DownloadTaskKey, DownloadRecord>> = _records.asStateFlow()

    fun record(serverId: String, itemId: String): DownloadRecord? =
        _records.value[DownloadTaskKey(serverId, itemId)]

    /** 最终文件路径（确定性：同一 serverId/itemId 恒为同一路径）。 */
    fun expectedFile(serverId: String, itemId: String, fileName: String): File =
        DownloadPaths.finalFile(rootDir, serverId, itemId, fileName)

    /** 断点续传临时文件路径（`<final>.part`）。 */
    fun partialFile(serverId: String, itemId: String, fileName: String): File =
        DownloadPaths.partialFile(expectedFile(serverId, itemId, fileName))

    /**
     * 执行下载（可被 [pause] / [cancel] 从其它协程中断）。
     *
     * 已存在且非空的 COMPLETED 文件直接返回（幂等）；失败/暂停后再次调用会从 `.part` 续传。
     */
    suspend fun download(
        request: DownloadRequest,
        onProgress: ((DownloadProgress) -> Unit)? = null,
    ): DownloadRecord {
        val key = request.key
        _records.value[key]?.let { existing ->
            if (existing.status == DownloadStatus.COMPLETED) {
                val file = File(rootDir, existing.relativePath)
                if (file.isFile && file.length() > 0) return existing
            }
        }

        val fileName = _records.value[key]?.fileName?.takeIf { it.isNotBlank() } ?: request.fileName
        val finalFile = DownloadPaths.finalFile(rootDir, key.serverId, key.itemId, fileName)
        val partialFile = DownloadPaths.partialFile(finalFile)

        var record = transition(
            _records.value[key]?.copy(fileName = fileName) ?: DownloadRecord(
                key = key,
                fileName = fileName,
                relativePath = DownloadPaths.relativePath(rootDir, finalFile),
                mimeType = request.mimeType,
                updatedAtEpochMs = clock(),
            ),
            DownloadStatus.QUEUED,
        )
        persist(record)
        emit(record, partialFile.length(), request.expectedSizeBytes, onProgress)

        val job = currentCoroutineContext()[Job]
        if (job != null) activeJobs[key] = job
        stopIntent.remove(key)
        try {
            record = transition(record, DownloadStatus.RUNNING)
            persist(record)
            emit(record, partialFile.length(), request.expectedSizeBytes, onProgress)

            var forceRefresh = false
            while (true) {
                val source = resolveSource(request, forceRefresh)
                if (
                    source.expiresAtEpochMs != null && source.expiresAtEpochMs <= clock() &&
                    sourceResolver != null && !forceRefresh
                ) {
                    // 签名 URL 已过期：先重解析，避免必然失败的请求（条目 95）。
                    forceRefresh = true
                    continue
                }
                try {
                    val written = transport.fetch(source, request, partialFile) { downloaded, total ->
                        onProgress?.invoke(
                            DownloadProgress(
                                key = key,
                                status = DownloadStatus.RUNNING,
                                bytesDownloaded = downloaded,
                                totalBytes = total ?: request.expectedSizeBytes,
                            ),
                        )
                    }
                    val size = withContext(Dispatchers.IO) { moveIntoPlace(partialFile, finalFile) }
                    val completed = transition(record, DownloadStatus.COMPLETED).copy(
                        relativePath = DownloadPaths.relativePath(rootDir, finalFile),
                        sizeBytes = if (written > 0) size else finalFile.length(),
                        mimeType = request.mimeType ?: record.mimeType,
                        failureKind = null,
                        message = null,
                    )
                    persist(completed)
                    onProgress?.invoke(
                        DownloadProgress(
                            key = key,
                            status = DownloadStatus.COMPLETED,
                            bytesDownloaded = completed.sizeBytes ?: size,
                            totalBytes = completed.sizeBytes ?: size,
                        ),
                    )
                    return completed
                } catch (e: DownloadUnauthorizedException) {
                    // 401/403：签名过期或登录失效 → 重解析一次（条目 94/95）。
                    if (sourceResolver != null && !forceRefresh) {
                        forceRefresh = true
                        continue
                    }
                    return failAndReport(record, DownloadFailureKind.AUTH_REQUIRED, "需要重新授权", onProgress)
                }
            }
        } catch (e: CancellationException) {
            val intent = stopIntent.remove(key) ?: DownloadStatus.CANCELLED
            withContext(NonCancellable) {
                if (intent == DownloadStatus.PAUSED) {
                    val paused = transition(record, DownloadStatus.PAUSED)
                    persist(paused)
                    emit(paused, partialFile.length(), request.expectedSizeBytes, onProgress)
                } else {
                    partialFile.delete()
                    val cancelled = transition(record, DownloadStatus.CANCELLED)
                    persist(cancelled)
                    emit(cancelled, 0, request.expectedSizeBytes, onProgress)
                }
            }
            throw e
        } catch (e: DownloadHttpException) {
            return failAndReport(record, DownloadFailureKind.HTTP, "HTTP ${e.code}", onProgress)
        } catch (e: IOException) {
            // 保留 .part：下一次调用从断点续传。
            return failAndReport(record, DownloadFailureKind.NETWORK, "网络中断，可重试续传", onProgress)
        } finally {
            if (job != null) activeJobs.remove(key, job)
        }
    }

    /** 暂停：取消当前协程但保留 `.part`，可再次 [download] 续传。 */
    suspend fun pause(serverId: String, itemId: String) {
        val key = DownloadTaskKey(serverId, itemId)
        val job = activeJobs[key]
        if (job == null) {
            _records.value[key]?.takeIf { it.status == DownloadStatus.RUNNING }?.let { record ->
                withContext(NonCancellable) { persist(transition(record, DownloadStatus.PAUSED)) }
            }
            return
        }
        stopIntent[key] = DownloadStatus.PAUSED
        job.cancelAndJoin()
        activeJobs.remove(key, job)
    }

    /** 取消：中断并删除 `.part`（最终文件若已存在不受影响）。 */
    suspend fun cancel(serverId: String, itemId: String) {
        val key = DownloadTaskKey(serverId, itemId)
        val job = activeJobs[key]
        if (job != null) {
            stopIntent[key] = DownloadStatus.CANCELLED
            job.cancelAndJoin()
            activeJobs.remove(key, job)
            return
        }
        val record = _records.value[key] ?: return
        if (record.status !in setOf(
                DownloadStatus.RUNNING,
                DownloadStatus.PAUSED,
                DownloadStatus.FAILED,
                DownloadStatus.QUEUED,
            )
        ) {
            return
        }
        val finalFile = File(rootDir, record.relativePath)
        DownloadPaths.partialFile(finalFile).delete()
        withContext(NonCancellable) { persist(transition(record, DownloadStatus.CANCELLED)) }
    }

    /**
     * 删除一次下载的全部痕迹（条目 18）：
     * 最终文件 + `.part` + 该 key 前缀的残留文件 + 索引记录。
     *
     * 幂等：重复删除返回 false，不抛异常；索引丢失时仍按 key 前缀清理目录。
     */
    suspend fun delete(serverId: String, itemId: String): Boolean = withContext(Dispatchers.IO) {
        val key = DownloadTaskKey(serverId, itemId)
        activeJobs[key]?.let { job ->
            stopIntent[key] = DownloadStatus.CANCELLED
            job.cancelAndJoin()
            activeJobs.remove(key, job)
        }
        var removed = false

        _records.value[key]?.let { record ->
            val finalFile = File(rootDir, record.relativePath)
            if (finalFile.delete()) removed = true
            if (DownloadPaths.partialFile(finalFile).delete()) removed = true
        }

        // 索引缺失/损坏时的兜底：按 `<keyHash>_` 前缀清掉目录内所有残留（含 .part）。
        val prefix = "${DownloadPaths.keyHash(key)}_"
        DownloadPaths.serverDir(rootDir, serverId).listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(prefix) && file.delete()) removed = true
        }

        synchronized(lock) {
            if (_records.value.containsKey(key)) {
                val next = _records.value - key
                index.save(next)
                _records.value = next
                removed = true
            }
        }
        removed
    }

    /** 清空全部下载与临时残留，返回删除的文件数（索引记录同步清空）。 */
    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        activeJobs.keys.toList().forEach { key ->
            activeJobs[key]?.let { job ->
                stopIntent[key] = DownloadStatus.CANCELLED
                job.cancelAndJoin()
            }
        }
        activeJobs.clear()
        var deleted = 0
        rootDir.listFiles()?.forEach { file ->
            when {
                file.isDirectory -> {
                    file.walkBottomUp().forEach { node ->
                        if (node.isFile && node.delete()) deleted++
                    }
                }

                file.isFile -> if (file.delete()) deleted++
            }
        }
        synchronized(lock) {
            index.save(emptyMap())
            _records.value = emptyMap()
        }
        deleted
    }

    /**
     * 清理孤儿文件：目录中不被索引引用的一切文件（含无记录的 `.part`）。
     * 用于“删除不干净”的历史残留回收。
     */
    suspend fun cleanupOrphans(): Int = withContext(Dispatchers.IO) {
        val referenced = _records.value.values
            .map { File(rootDir, it.relativePath).absolutePath }
            .toSet()
        var deleted = 0
        rootDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.absolutePath !in referenced && file.delete()) deleted++
            }
        }
        deleted
    }

    override suspend fun listCompleted(): List<DownloadRecord> = withContext(Dispatchers.IO) {
        _records.value.values
            .filter { it.status == DownloadStatus.COMPLETED }
            .filter { File(rootDir, it.relativePath).let { file -> file.isFile && file.length() > 0 } }
            .sortedBy { it.fileName.lowercase() }
    }

    /**
     * 把“已下载内容”解析为本地播放源（条目 61）。
     *
     * 1. 索引 COMPLETED 且文件非空 → `file://` DIRECT_PLAY；
     * 2. 索引缺失但确定性路径上已有非空文件（“已存在”）→ 补写索引后同样可播；
     * 3. 仅有 `.part` / 文件为空 → null（不得把未完成内容当可播）。
     */
    override suspend fun resolveLocalPlayback(
        serverId: String,
        itemId: String,
        fileNameHint: String?,
    ): PlaybackSource? = withContext(Dispatchers.IO) {
        val key = DownloadTaskKey(serverId, itemId)
        _records.value[key]?.let { record ->
            if (record.status != DownloadStatus.COMPLETED) return@withContext null
            val file = File(rootDir, record.relativePath)
            if (!file.isFile || file.length() <= 0) return@withContext null
            return@withContext record.toPlaybackSource(record.mimeType)
        }

        val name = fileNameHint?.takeIf { it.isNotBlank() }
            ?: itemId.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: return@withContext null
        val file = DownloadPaths.finalFile(rootDir, serverId, itemId, name)
        if (!file.isFile || file.length() <= 0) return@withContext null
        val healed = DownloadRecord(
            key = key,
            fileName = name,
            relativePath = DownloadPaths.relativePath(rootDir, file),
            mimeType = guessMimeType(name),
            sizeBytes = file.length(),
            status = DownloadStatus.COMPLETED,
            updatedAtEpochMs = clock(),
        )
        persist(healed)
        healed.toPlaybackSource()
    }

    // ---- 内部 ----

    private suspend fun resolveSource(request: DownloadRequest, forceRefresh: Boolean): ResolvedDownloadSource =
        sourceResolver?.resolve(request, forceRefresh)
            ?: ResolvedDownloadSource(url = request.url, headers = request.headers)

    private fun failAndReport(
        record: DownloadRecord,
        kind: DownloadFailureKind,
        message: String,
        onProgress: ((DownloadProgress) -> Unit)?,
    ): DownloadRecord {
        val failed = transition(record, DownloadStatus.FAILED).copy(failureKind = kind, message = message)
        persist(failed)
        onProgress?.invoke(
            DownloadProgress(
                key = failed.key,
                status = DownloadStatus.FAILED,
                failureKind = kind,
                message = message,
            ),
        )
        return failed
    }

    private fun moveIntoPlace(partialFile: File, finalFile: File): Long {
        finalFile.parentFile?.mkdirs()
        if (finalFile.exists()) finalFile.delete()
        runCatching {
            Files.move(
                partialFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            Files.move(partialFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return finalFile.length()
    }

    private fun persist(record: DownloadRecord) {
        synchronized(lock) {
            val next = _records.value.toMutableMap()
            next[record.key] = record
            index.save(next)
            _records.value = next
        }
    }

    private fun emit(
        record: DownloadRecord,
        bytes: Long,
        total: Long?,
        onProgress: ((DownloadProgress) -> Unit)?,
    ) {
        onProgress?.invoke(
            DownloadProgress(
                key = record.key,
                status = record.status,
                bytesDownloaded = bytes,
                totalBytes = total ?: record.sizeBytes,
                failureKind = record.failureKind,
                message = record.message,
            ),
        )
    }

    private fun transition(record: DownloadRecord, target: DownloadStatus): DownloadRecord {
        val allowed = ALLOWED_TRANSITIONS[record.status].orEmpty()
        check(target in allowed) { "非法下载状态流转：${record.status} → $target" }
        return record.copy(status = target, updatedAtEpochMs = clock())
    }

    private fun DownloadRecord.toPlaybackSource(mimeTypeOverride: String? = null): PlaybackSource {
        val file = File(rootDir, relativePath)
        return PlaybackSource(
            url = file.toURI().toString(),
            mimeType = mimeTypeOverride ?: guessMimeType(file.name),
            container = file.extension.lowercase().takeIf { it.isNotBlank() },
            durationMs = null,
            mode = PlaybackMode.DIRECT_PLAY,
            sessionId = null,
            supportsSeeking = true,
        )
    }

    private fun guessMimeType(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> null
    }

    private companion object {
        val ALLOWED_TRANSITIONS: Map<DownloadStatus, Set<DownloadStatus>> = mapOf(
            DownloadStatus.QUEUED to setOf(
                DownloadStatus.QUEUED,
                DownloadStatus.RUNNING,
                DownloadStatus.PAUSED,
                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED,
            ),
            DownloadStatus.RUNNING to setOf(
                DownloadStatus.COMPLETED,
                DownloadStatus.FAILED,
                DownloadStatus.PAUSED,
                DownloadStatus.CANCELLED,
            ),
            DownloadStatus.PAUSED to setOf(DownloadStatus.QUEUED, DownloadStatus.CANCELLED),
            DownloadStatus.FAILED to setOf(DownloadStatus.QUEUED, DownloadStatus.CANCELLED),
            DownloadStatus.CANCELLED to setOf(DownloadStatus.QUEUED),
            DownloadStatus.COMPLETED to setOf(DownloadStatus.QUEUED),
        )
    }
}
