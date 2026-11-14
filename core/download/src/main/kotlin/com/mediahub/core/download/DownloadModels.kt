package com.mediahub.core.download

/**
 * 离线下载生命周期（ADR-009：下载缓存独立于播放缓存 / 图片缓存 / 元数据）。
 *
 * 合法流转：
 * ```
 * QUEUED → RUNNING → COMPLETED
 *                  ↘ FAILED    （保留 .part，可重试续传）
 *                  ↘ PAUSED    （保留 .part，可恢复）
 *                  ↘ CANCELLED （删除 .part）
 * PAUSED/FAILED → QUEUED（再次 download）
 * ```
 * 状态只在 [DownloadManager] 内推进，UI 只读 [DownloadRecord.status]。
 */
enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** 失败分类：UI 据此决定“重试 / 重新授权 / 提示存储不足”，不解析文案。 */
enum class DownloadFailureKind {
    NETWORK,
    HTTP,
    AUTH_REQUIRED,
    STORAGE,
    INVALID_SOURCE,
}

/** 下载任务身份：数据源实例 + 媒体条目 id（跨进程稳定，不含任何 URL/Token）。 */
data class DownloadTaskKey(
    val serverId: String,
    val itemId: String,
)

/**
 * 下载请求。
 *
 * [url] 只在本进程内使用：临时签名 URL 绝不落库（ADR-003），索引只保存
 * serverId/itemId/相对路径等非敏感信息。
 */
data class DownloadRequest(
    val serverId: String,
    val itemId: String,
    val url: String,
    val fileName: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    /** 已知文件大小（字节）；提供时用于完成态校验与 416 判定。 */
    val expectedSizeBytes: Long? = null,
) {
    val key: DownloadTaskKey get() = DownloadTaskKey(serverId, itemId)
}

/**
 * 网盘 Provider 解析出的可下载地址。
 *
 * 阿里云盘 / 夸克等使用临时签名 URL：过期或 401/403 时由
 * [DownloadSourceResolver] 重新解析（ADR-003），下载器不缓存签名 URL。
 */
data class ResolvedDownloadSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** 过期时间（epoch ms）；null = 未知/不过期。 */
    val expiresAtEpochMs: Long? = null,
)

/** 下载地址解析器（由具体网盘 Provider 实现；[forceRefresh] 表示上一条 URL 已失效）。 */
interface DownloadSourceResolver {
    suspend fun resolve(request: DownloadRequest, forceRefresh: Boolean): ResolvedDownloadSource
}

/** 持久化的下载记录（不含 URL / 请求头 / Cookie / Token）。 */
data class DownloadRecord(
    val key: DownloadTaskKey,
    val fileName: String,
    /** 相对下载根目录的路径（分隔符统一为 '/'）。 */
    val relativePath: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val updatedAtEpochMs: Long = 0,
    val failureKind: DownloadFailureKind? = null,
    val message: String? = null,
)

/** 下载进度快照（字节级，供 UI / 通知使用）。 */
data class DownloadProgress(
    val key: DownloadTaskKey,
    val status: DownloadStatus,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val failureKind: DownloadFailureKind? = null,
    val message: String? = null,
) {
    /** 0f..1f；总大小未知时为 null。 */
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
}

/**
 * “已下载内容 → 本地播放源”的关联入口（条目 61）。
 *
 * 媒体库/播放器不再依赖远端 resolvePlayback：只要下载已完成（或目标文件
 * 已存在且非空），就返回 `file://` 的 DIRECT_PLAY 源，断网也能播放。
 */
interface DownloadedMediaLocator {
    suspend fun resolveLocalPlayback(
        serverId: String,
        itemId: String,
        fileNameHint: String? = null,
    ): com.mediahub.model.PlaybackSource?

    suspend fun listCompleted(): List<DownloadRecord>
}
