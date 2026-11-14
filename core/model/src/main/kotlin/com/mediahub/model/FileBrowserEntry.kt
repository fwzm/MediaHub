package com.mediahub.model

/**
 * 文件浏览器条目（条目 108/110）。
 *
 * 文件浏览器是媒体库之上的「按目录看文件」视图，只承载展示所需的字段；
 * 播放仍走 [MediaItem] 原始条目，避免两套模型各自演进。
 */
data class FileBrowserEntry(
    val id: String,
    val name: String,
    val path: String? = null,
    val isDirectory: Boolean = false,
    val sizeBytes: Long? = null,
    val modifiedAtEpochMs: Long? = null,
)

/** 把媒体条目投影为文件浏览器条目；文件夹与文件共用同一列表。 */
fun MediaItem.toFileBrowserEntry(): FileBrowserEntry = FileBrowserEntry(
    id = id,
    name = title,
    path = path,
    isDirectory = type == MediaType.FOLDER,
    sizeBytes = sizeBytes,
)

/** 文件大小可读化；未知大小返回空串，不用「0 B」误导用户。 */
fun formatFileSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return ""
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1.0) return String.format(java.util.Locale.US, "%.1f GB", gb)
    val mb = bytes / 1_048_576.0
    if (mb >= 1.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
    val kb = bytes / 1024.0
    return String.format(java.util.Locale.US, "%.0f KB", kb)
}
