package com.mediahub.provider.local

import com.mediahub.model.*
import com.mediahub.provider.api.*
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException

/** One configured tree, accessed only through its retained read grant. */
internal class SafLocalAccess(private val server: MediaServer, private val roots: LocalRootProvider) {
    private val tree = server.baseUrl
    private val documents: SafTreeNavigator.SafSource get() = roots as? SafTreeNavigator.SafSource
        ?: throw ProviderException.DirectoryAccessRequired(server.id)

    /**
     * SAF / ContentResolver 访问的统一包装（用户条目 149 / 审计 62）。
     *
     * 个别设备在授权失效、DocumentsProvider 崩溃或文档已删除时抛 SecurityException /
     * IllegalStateException / IllegalArgumentException / FileNotFoundException，
     * 直接冒泡会变成进程崩溃或原始异常文案 → 统一映射为 DirectoryAccessRequired
     * （UI 走"重新授权目录"文案）。
     *
     * 不吞 [ProviderException]（NotFound / Parse 等业务语义）与 [CancellationException]
     * （后者是 IllegalStateException 的子类，必须先捕获重抛）。
     */
    private inline fun <T> access(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (denied: ProviderException) {
        throw denied
    } catch (denied: SecurityException) {
        throw ProviderException.DirectoryAccessRequired(server.id)
    } catch (denied: IllegalStateException) {
        throw ProviderException.DirectoryAccessRequired(server.id)
    } catch (denied: IllegalArgumentException) {
        throw ProviderException.DirectoryAccessRequired(server.id)
    } catch (denied: FileNotFoundException) {
        throw ProviderException.DirectoryAccessRequired(server.id)
    }

    private fun member(uri: String) {
        if (!SafUri.contains(tree, uri)) throw ProviderException.NotFound(server.id, "本地文档")
    }
    private fun root(): SafTreeNavigator.SafSource {
        val grantedTrees = try { roots.contentRoots() }
        catch (e: Exception) {
            if (SafAccessFailurePolicy.isAccessFailure(e)) throw ProviderException.DirectoryAccessRequired(server.id)
            throw e
        }
        if (!SafUri.validTree(tree) || grantedTrees.none { SafUri.sameTree(tree, it.toString()) }) {
            throw ProviderException.DirectoryAccessRequired(server.id)
        }
        val source = documents
        val metadata = try { source.metadata(SafUri.documentUri(tree, SafUri.treeDocId(tree))) }
            catch (e: Exception) {
                if (SafAccessFailurePolicy.isAccessFailure(e)) throw ProviderException.DirectoryAccessRequired(server.id)
                throw e
            }
        if (metadata?.mimeType != DIRECTORY) throw ProviderException.DirectoryAccessRequired(server.id)
        return source
    }
    private fun describe(source: SafTreeNavigator.SafSource, uri: String): SafTreeNavigator.SafRow =
        try { source.metadata(uri) ?: throw ProviderException.NotFound(server.id, "本地文档") }
        catch (e: ProviderException) { throw e }
        catch (e: Exception) {
            if (SafAccessFailurePolicy.isAccessFailure(e)) throw ProviderException.DirectoryAccessRequired(server.id)
            throw e
        }

    fun connection(): ConnectionStatus = try {
        access { root() }
        ConnectionStatus(true, message = "授权目录可用")
    } catch (_: Exception) {
        ConnectionStatus(false, message = "目录授权失效或不可访问，请重新选择", errorCode = ProviderException.ErrorCode.CONNECTION)
    }

    fun list(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem> = access {
        if (folder != null && folder.serverId != server.id) throw ProviderException.NotFound(server.id, "本地文档")
        val parent = folder?.let { it.path ?: it.id } ?: SafUri.documentUri(tree, SafUri.treeDocId(tree))
        member(parent)
        val source = root()
        if (describe(source, parent).mimeType != DIRECTORY) throw ProviderException.NotFound(server.id, "本地目录")
        val rows = try { source.listChildren(SafUri.childrenUri(tree, SafUri.docId(parent))) }
            catch (e: Exception) {
                if (SafAccessFailurePolicy.isAccessFailure(e)) throw ProviderException.DirectoryAccessRequired(server.id)
                throw e
            }
        val all = rows.map { row -> mapped(row, parent) }
            .sortedWith(compareBy({ it.type != MediaType.FOLDER }, { it.title.lowercase() }, { it.id }))
        val items = all.drop(page.offset).take(page.limit)
        val next = page.offset + items.size
        PagedResult(items, totalCount = all.size, hasMore = next < all.size,
            nextOffset = next.takeIf { it < all.size })
    }

    fun detail(uri: String): MediaDetail = access {
        member(uri)
        val source = root()
        MediaDetail(item = mapped(describe(source, uri), source.parentUri(uri)))
    }

    fun playback(item: MediaItem): PlaybackSource = access {
        if (item.serverId != server.id) throw ProviderException.NotFound(server.id, "本地文档")
        val uri = item.path ?: item.id
        member(uri)
        val source = root()
        val row = describe(source, uri)
        val openable = try { source.canOpen(uri) }
        catch (e: Exception) {
            if (SafAccessFailurePolicy.isAccessFailure(e)) throw ProviderException.DirectoryAccessRequired(server.id)
            throw e
        }
        if (row.mimeType == DIRECTORY || !openable) throw ProviderException.NotFound(server.id, "本地媒体")
        PlaybackSource(url = uri, mimeType = row.mimeType,
            container = row.name.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() },
            mode = PlaybackMode.DIRECT_PLAY)
    }

    fun subtitles(video: MediaItem): List<DiscoveredSubtitle> = access {
        if (video.serverId != server.id) throw ProviderException.NotFound(server.id, "本地文档")
        val uri = video.path ?: video.id
        member(uri)
        val source = root()
        val parent = video.parentId ?: source.parentUri(uri) ?: return@access emptyList()
        member(parent)
        val children = try { source.listChildren(SafUri.childrenUri(tree, SafUri.docId(parent))) }
        catch (e: Exception) {
            if (SafAccessFailurePolicy.isAccessFailure(e)) throw ProviderException.DirectoryAccessRequired(server.id)
            throw e
        }
        children
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
