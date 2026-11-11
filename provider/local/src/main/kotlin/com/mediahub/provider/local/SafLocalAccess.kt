package com.mediahub.provider.local

import com.mediahub.model.*
import com.mediahub.provider.api.*

/** One configured tree, accessed only through its retained read grant. */
internal class SafLocalAccess(private val server: MediaServer, private val roots: LocalRootProvider) {
    private val tree = server.baseUrl
    private val documents: SafTreeNavigator.SafSource get() = roots as? SafTreeNavigator.SafSource
        ?: throw ProviderException.DirectoryAccessRequired(server.id)

    private fun member(uri: String) {
        if (!SafUri.contains(tree, uri)) throw ProviderException.NotFound(server.id, "本地文档")
    }
    private fun root(): SafTreeNavigator.SafSource {
        if (!SafUri.validTree(tree) || roots.contentRoots().none { SafUri.sameTree(tree, it.toString()) }) {
            throw ProviderException.DirectoryAccessRequired(server.id)
        }
        val source = documents
        val metadata = try { source.metadata(SafUri.documentUri(tree, SafUri.treeDocId(tree))) }
            catch (_: SecurityException) { throw ProviderException.DirectoryAccessRequired(server.id) }
        if (metadata?.mimeType != DIRECTORY) throw ProviderException.DirectoryAccessRequired(server.id)
        return source
    }
    private fun describe(source: SafTreeNavigator.SafSource, uri: String): SafTreeNavigator.SafRow =
        try { source.metadata(uri) ?: throw ProviderException.NotFound(server.id, "本地文档") }
        catch (_: SecurityException) { throw ProviderException.DirectoryAccessRequired(server.id) }

    fun connection(): ConnectionStatus = try {
        root()
        ConnectionStatus(true, message = "授权目录可用")
    } catch (_: Exception) {
        ConnectionStatus(false, message = "目录授权失效或不可访问，请重新选择", errorCode = ProviderException.ErrorCode.CONNECTION)
    }

    fun list(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem> {
        if (folder != null && folder.serverId != server.id) throw ProviderException.NotFound(server.id, "本地文档")
        val parent = folder?.let { it.path ?: it.id } ?: SafUri.documentUri(tree, SafUri.treeDocId(tree))
        member(parent)
        val source = root()
        if (describe(source, parent).mimeType != DIRECTORY) throw ProviderException.NotFound(server.id, "本地目录")
        val rows = try { source.listChildren(SafUri.childrenUri(tree, SafUri.docId(parent))) }
            catch (_: SecurityException) { throw ProviderException.DirectoryAccessRequired(server.id) }
        val all = rows.map { row -> mapped(row, parent) }
            .sortedWith(compareBy({ it.type != MediaType.FOLDER }, { it.title.lowercase() }, { it.id }))
        val items = all.drop(page.offset).take(page.limit)
        val next = page.offset + items.size
        return PagedResult(items, totalCount = all.size, hasMore = next < all.size,
            nextOffset = next.takeIf { it < all.size })
    }

    fun detail(uri: String): MediaDetail {
        member(uri)
        val source = root()
        return MediaDetail(item = mapped(describe(source, uri), source.parentUri(uri)))
    }

    fun playback(item: MediaItem): PlaybackSource {
        if (item.serverId != server.id) throw ProviderException.NotFound(server.id, "本地文档")
        val uri = item.path ?: item.id
        member(uri)
        val source = root()
        val row = describe(source, uri)
        if (row.mimeType == DIRECTORY || !source.canOpen(uri)) throw ProviderException.NotFound(server.id, "本地媒体")
        return PlaybackSource(url = uri, mimeType = row.mimeType,
            container = row.name.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() },
            mode = PlaybackMode.DIRECT_PLAY)
    }

    fun subtitles(video: MediaItem): List<DiscoveredSubtitle> {
        if (video.serverId != server.id) throw ProviderException.NotFound(server.id, "本地文档")
        val uri = video.path ?: video.id
        member(uri)
        val source = root()
        val parent = video.parentId ?: source.parentUri(uri) ?: return emptyList()
        member(parent)
        return source.listChildren(SafUri.childrenUri(tree, SafUri.docId(parent)))
            .filter { it.mimeType != DIRECTORY && it.name.substringAfterLast('.', "").lowercase() in SubtitleFormats.EXTENSIONS }
            .map { row ->
                val extension = row.name.substringAfterLast('.').lowercase()
                val child = SafUri.documentUri(tree, row.docId)
                DiscoveredSubtitle(id = child, name = row.name.substringBeforeLast('.', row.name), fileName = row.name,
                    extension = extension, language = SubtitleFormats.languageFromFileName(row.name), uri = child)
            }.sortedWith(compareBy({ it.name.lowercase() }, { it.extension }))
    }

    private fun mapped(row: SafTreeNavigator.SafRow, parent: String?): MediaItem {
        val directory = row.mimeType == DIRECTORY
        val extension = row.name.substringAfterLast('.', "").lowercase()
        val type = when {
            directory -> MediaType.FOLDER
            row.mimeType?.startsWith("video/") == true || extension in VIDEO -> MediaType.VIDEO
            row.mimeType?.startsWith("audio/") == true || extension in AUDIO -> MediaType.AUDIO
            else -> MediaType.OTHER
        }
        val uri = SafUri.documentUri(tree, row.docId)
        return MediaItem(server.id, uri, type,
            title = (if (directory) row.name else row.name.substringBeforeLast('.', row.name)).ifBlank { "未命名" },
            path = uri, parentId = parent, sizeBytes = row.sizeBytes?.takeIf { !directory && it >= 0 },
            container = extension.takeIf { !directory && it.isNotBlank() }, libraryId = "local")
    }
    private companion object {
        const val DIRECTORY = "vnd.android.document/directory"
        val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "ts", "m2ts", "avi", "mov", "wmv", "flv", "3gp")
        val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma")
    }
}
