package com.mediahub.provider.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

/**
 * SAF 文档树 Provider：持久化 content:// URI，兼容本机、U 盘及 DocumentProvider。
 *
 * 浏览全程 tree-backed（[SafTreeNavigator]），支持多层子目录；
 * 旧数据（baseUrl 为空）明确返回 REAUTH_REQUIRED（review P2-2）。
 */
class LocalProvider(
    private val server: com.mediahub.model.MediaServer,
    private val context: Context,
) : MediaProvider, MediaBrowseProvider, MediaPlaybackProvider {

    override val serverId: String get() = server.id
    override val descriptor: ProviderDescriptor = DESCRIPTOR

    private val treeUri: String? get() = server.baseUrl.takeIf { it.isNotBlank() }

    private fun navigator(): SafTreeNavigator {
        val uri = treeUri ?: throw ProviderException.Connection(serverId, "本地目录未授权")
        return SafTreeNavigator(uri, SafTreeNavigator.ContentResolverSource(context))
    }

    override suspend fun testConnection(request: ConnectionTestRequest): ConnectionStatus = withContext(Dispatchers.IO) {
        val uri = treeUri
            ?: return@withContext ConnectionStatus(
                ok = false,
                message = "本地目录未授权，请重新选择媒体目录",
                errorCode = ProviderException.ErrorCode.REAUTH_REQUIRED,
            )
        if (!uri.startsWith("content://")) {
            return@withContext ConnectionStatus(
                ok = false,
                message = "本地目录必须是 content:// 文档树",
                errorCode = ProviderException.ErrorCode.CONNECTION,
            )
        }
        val hasReadGrant = context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uri && it.isReadPermission
        }
        if (!hasReadGrant) {
            return@withContext ConnectionStatus(
                ok = false,
                message = "目录授权未持久化，请重新选择",
                errorCode = ProviderException.ErrorCode.REAUTH_REQUIRED,
            )
        }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uri))
        if (root == null || !root.exists() || !root.isDirectory || !root.canRead()) {
            ConnectionStatus(ok = false, message = "目录不可访问或授权已经失效")
        } else {
            ConnectionStatus(ok = true, message = "本地目录可用：${root.name ?: "已选目录"}")
        }
    }

    override suspend fun listFolder(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem> =
        withContext(Dispatchers.IO) {
            val nav = navigator()
            val parentDocId = if (folder == null) {
                nav.rootDocId()
            } else {
                // tree-backed document uri 字符串 → docId（绝不 fromSingleUri，见 SafTreeNavigator）
                nav.docIdOf(folder.path ?: folder.id)
            }
            val entries = nav.listChildren(parentDocId)
                .sortedWith(compareBy({ it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR },
                    { it.name.lowercase() }))
            val paged = entries.drop(page.offset).take(page.limit)
            PagedResult(
                items = paged.map { it.toMediaItem(parentId = folder?.id) },
                totalCount = entries.size,
                hasMore = page.offset + paged.size < entries.size,
            )
        }

    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(item.path ?: item.id)
            if (!SafTreeNavigator.ContentResolverSource(context).canOpen(uri.toString())) {
                throw ProviderException.NotFound(serverId, uri.toString())
            }
            PlaybackSource(
                url = uri.toString(),
                mimeType = context.contentResolver.getType(uri),
                container = item.container,
                mode = PlaybackMode.DIRECT_PLAY,
            )
        }

    private fun SafTreeNavigator.SafEntry.toMediaItem(parentId: String?): MediaItem {
        val fileName = name.ifBlank { uri.substringAfterLast('/').substringAfterLast(':').ifBlank { "未命名" } }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val mediaType = when {
            isDir -> MediaType.FOLDER
            mimeType?.startsWith("video/") == true || extension in VIDEO_EXTENSIONS -> MediaType.VIDEO
            mimeType?.startsWith("audio/") == true || extension in AUDIO_EXTENSIONS -> MediaType.AUDIO
            else -> MediaType.OTHER
        }
        return MediaItem(
            serverId = serverId,
            id = uri.toString(),
            type = mediaType,
            title = if (isDir) fileName else fileName.substringBeforeLast('.', fileName),
            path = uri.toString(),
            parentId = parentId,
            sizeBytes = sizeBytes,
            container = extension.takeIf { it.isNotBlank() && !isDir },
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
