package com.mediahub.provider.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackSource
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ConnectionTestRequest
import com.mediahub.provider.api.MediaBrowseProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SAF 文档树 Provider：持久化 content:// URI，兼容本机、U 盘及 DocumentProvider。 */
class LocalProvider(
    private val server: com.mediahub.model.MediaServer,
    private val context: Context,
) : MediaProvider, MediaBrowseProvider, MediaPlaybackProvider {

    override val serverId: String get() = server.id
    override val descriptor: ProviderDescriptor = DESCRIPTOR

    private val treeUri: Uri? get() = server.baseUrl.takeIf { it.isNotBlank() }?.let(Uri::parse)

    override suspend fun testConnection(request: ConnectionTestRequest): ConnectionStatus = withContext(Dispatchers.IO) {
        val uri = treeUri
            ?: return@withContext ConnectionStatus(ok = false, message = "请先选择媒体目录")
        if (uri.scheme != "content") {
            return@withContext ConnectionStatus(ok = false, message = "本地目录必须是 content:// 文档树")
        }
        val hasReadGrant = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!hasReadGrant) {
            return@withContext ConnectionStatus(ok = false, message = "目录授权未持久化，请重新选择")
        }
        val root = DocumentFile.fromTreeUri(context, uri)
        if (root == null || !root.exists() || !root.isDirectory || !root.canRead()) {
            ConnectionStatus(ok = false, message = "目录不可访问或授权已经失效")
        } else {
            ConnectionStatus(ok = true, message = "本地目录可用：${root.name ?: "已选目录"}")
        }
    }

    override suspend fun listFolder(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem> =
        withContext(Dispatchers.IO) {
            val directory = if (folder == null) {
                treeUri?.let { DocumentFile.fromTreeUri(context, it) }
            } else {
                DocumentFile.fromSingleUri(context, Uri.parse(folder.path ?: folder.id))
            } ?: throw ProviderException.NotFound(serverId, folder?.id ?: "本地目录")

            if (!directory.isDirectory || !directory.canRead()) {
                throw ProviderException.Connection(serverId, "目录不可读取")
            }
            val items = directory.listFiles()
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.orEmpty().lowercase() }))
                .map { it.toMediaItem(parentId = folder?.id ?: requireNotNull(treeUri).toString()) }
            val paged = items.drop(page.offset).take(page.limit)
            PagedResult(
                items = paged,
                totalCount = items.size,
                hasMore = page.offset + paged.size < items.size,
            )
        }

    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(item.path ?: item.id)
            val canOpen = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (!canOpen) throw ProviderException.NotFound(serverId, uri.toString())
            PlaybackSource(
                url = uri.toString(),
                mimeType = context.contentResolver.getType(uri),
                container = item.container,
                mode = PlaybackMode.DIRECT_PLAY,
            )
        }

    private fun DocumentFile.toMediaItem(parentId: String?): MediaItem {
        val fileName = name.orEmpty().ifBlank { uri.lastPathSegment ?: "未命名" }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mediaType = when {
            isDirectory -> MediaType.FOLDER
            type?.startsWith("video/") == true || extension in VIDEO_EXTENSIONS -> MediaType.VIDEO
            type?.startsWith("audio/") == true || extension in AUDIO_EXTENSIONS -> MediaType.AUDIO
            else -> MediaType.OTHER
        }
        return MediaItem(
            serverId = serverId,
            id = uri.toString(),
            type = mediaType,
            title = if (isDirectory) fileName else fileName.substringBeforeLast('.', fileName),
            path = uri.toString(),
            parentId = parentId,
            sizeBytes = if (isDirectory) null else length().takeIf { it > 0 },
            container = extension.takeIf { it.isNotBlank() && !isDirectory },
            libraryId = "local",
        )
    }

    companion object {
        val DESCRIPTOR = ProviderDescriptor(
            providerId = "local",
            displayName = "本地媒体",
            description = "通过系统文件选择器授权目录",
            category = ProviderCategory.LOCAL_STORAGE,
            capabilities = setOf(ProviderCapability.BROWSE, ProviderCapability.PLAYBACK),
            authMethod = AuthMethod.NONE,
            status = ProviderStatus.AVAILABLE,
            sortOrder = 40,
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "webm", "ts", "m2ts", "avi", "mov", "wmv", "flv", "3gp",
        )
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma")
    }
}
