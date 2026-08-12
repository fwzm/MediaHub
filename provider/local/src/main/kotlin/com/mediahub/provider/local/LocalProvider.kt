package com.mediahub.provider.local

import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackSource
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaBrowseProvider
import com.mediahub.provider.api.MediaDetailProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderStatus
import java.io.File
import java.net.URI

/** 该 Provider 类型描述（Factory 与 Provider 共用，见 ADR-015）。 */
internal val LOCAL_PROVIDER_DESCRIPTOR = ProviderDescriptor(
    id = "local",
    serverType = ServerType.LOCAL,
    displayName = "本地存储",
    category = ProviderCategory.CLOUD_STORAGE,
    capabilities = setOf(ProviderCapability.BROWSE, ProviderCapability.MULTI_VERSION),
    authMethod = AuthMethod.NONE,
    status = ProviderStatus.STABLE,
    description = "本机存储（应用外部目录；SAF 文档树见 Phase 0.6）",
)

/**
 * 本地存储 Provider（真实实现，仅声明真实能力：BROWSE + DETAIL + PLAYBACK）。
 * 不再实现认证/媒体库/搜索/字幕/进度等"它不需要的能力"（Interface Segregation，ADR-014）。
 */
class LocalProvider(
    private val server: com.mediahub.model.MediaServer,
    private val rootProvider: LocalRootProvider,
) : MediaProvider,
    MediaBrowseProvider,
    MediaDetailProvider,
    MediaPlaybackProvider {

    override val serverId: String get() = server.id
    override val type: ServerType = ServerType.LOCAL
    override val displayName: String get() = server.displayName
    override val descriptor: ProviderDescriptor = LOCAL_PROVIDER_DESCRIPTOR

    override suspend fun testConnection(): ConnectionStatus {
        val roots = rootProvider.rootDirectories()
        return if (roots.isEmpty()) {
            ConnectionStatus(ok = false, message = "未配置本地存储目录")
        } else {
            ConnectionStatus(ok = true, message = "本地目录可用（${roots.size} 个）")
        }
    }

    // ---- 文件树浏览 ----

    override suspend fun listFolder(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem> {
        val roots = if (folder == null) {
            rootProvider.rootDirectories()
        } else {
            listOf(File(folder.path ?: folder.id))
        }
        val items = roots
            .filter { it.exists() && it.isDirectory }
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { it.toMediaItem() }
        val paged = items.drop(page.offset).take(page.limit)
        return PagedResult(
            items = paged,
            totalCount = items.size,
            hasMore = page.offset + paged.size < items.size,
        )
    }

    // ---- 详情 ----

    override suspend fun getItemDetail(itemId: String): MediaDetail {
        val file = File(itemId)
        if (!file.exists()) throw ProviderException.NotFound(serverId, itemId)
        return MediaDetail(item = file.toMediaItem())
    }

    // ---- 播放 ----

    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource {
        val file = File(item.path ?: item.id)
        if (!file.exists()) throw ProviderException.NotFound(serverId, item.path ?: item.id)
        return PlaybackSource(
            url = URI.create("file://${file.absolutePath}").toString(),
            mimeType = file.guessMimeType(),
            container = file.extension.lowercase().takeIf { it.isNotBlank() },
            durationMs = null,
            mode = PlaybackMode.DIRECT_PLAY,
        )
    }

    // ---- 映射 ----

    private fun File.toMediaItem(): MediaItem {
        val isDir = isDirectory
        val mediaType = when {
            isDir -> MediaType.FOLDER
            extension.lowercase() in VIDEO_EXTENSIONS -> MediaType.VIDEO
            extension.lowercase() in AUDIO_EXTENSIONS -> MediaType.AUDIO
            else -> MediaType.OTHER
        }
        return MediaItem(
            serverId = serverId,
            id = absolutePath,
            type = mediaType,
            title = nameWithoutExtension.ifBlank { name },
            path = absolutePath,
            parentId = parentFile?.absolutePath,
            sizeBytes = if (isDir) null else length().takeIf { it > 0 },
            container = extension.lowercase().takeIf { it.isNotBlank() && !isDir },
            libraryId = LOCAL_LIBRARY_ID,
        )
    }

    private fun File.guessMimeType(): String? = when (extension.lowercase()) {
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
        const val LOCAL_LIBRARY_ID = "local"
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "webm", "ts", "m2ts", "avi", "mov", "wmv", "flv", "3gp",
        )
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma")
    }
}
