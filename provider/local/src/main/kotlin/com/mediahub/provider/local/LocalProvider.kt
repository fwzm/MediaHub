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
            val rootUri = treeUri ?: throw ProviderException.NotFound(serverId, "本地目录")
            val parentUri = folder?.path?.let(Uri::parse) ?: rootUri
            val parentDocumentId = if (folder == null) {
                DocumentsContract.getTreeDocumentId(rootUri)
            } else {
                DocumentsContract.getDocumentId(parentUri)
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                rootUri,
                parentDocumentId,
            )
            val items = context.contentResolver.query(
                childrenUri,
                DOCUMENT_PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                buildList<MediaItem> {
                    while (cursor.moveToNext()) {
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(
                            rootUri,
                            cursor.getString(idIndex),
                        )
                        add(
                            documentToMediaItem(
                                uri = childUri,
                                displayName = cursor.getString(nameIndex).orEmpty(),
                                mimeType = cursor.getString(mimeIndex),
                                sizeBytes = if (cursor.isNull(sizeIndex)) null else cursor.getLong(sizeIndex),
                                parentId = folder?.id ?: rootUri.toString(),
                            )
                        )
                    }
                }
            } ?: throw ProviderException.Connection(serverId, "目录不可读取")
            val sorted = items.sortedWith(compareBy({ it.type != MediaType.FOLDER }, { it.title.lowercase() }))
            val paged = sorted.drop(page.offset).take(page.limit)
            PagedResult(
                items = paged,
                totalCount = sorted.size,
                hasMore = page.offset + paged.size < sorted.size,
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

    private fun documentToMediaItem(
        uri: Uri,
        displayName: String,
        mimeType: String?,
        sizeBytes: Long?,
        parentId: String?,
    ): MediaItem {
        val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val fileName = displayName.ifBlank { uri.lastPathSegment ?: "未命名" }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mediaType = when {
            isDirectory -> MediaType.FOLDER
            mimeType?.startsWith("video/") == true || extension in VIDEO_EXTENSIONS -> MediaType.VIDEO
            mimeType?.startsWith("audio/") == true || extension in AUDIO_EXTENSIONS -> MediaType.AUDIO
            else -> MediaType.OTHER
        }
        return MediaItem(
            serverId = serverId,
            id = uri.toString(),
            type = mediaType,
            title = if (isDirectory) fileName else fileName.substringBeforeLast('.', fileName),
            path = uri.toString(),
            parentId = parentId,
            sizeBytes = if (isDirectory) null else sizeBytes?.takeIf { it > 0 },
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
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
