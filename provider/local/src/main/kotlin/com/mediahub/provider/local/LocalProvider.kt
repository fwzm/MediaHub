package com.mediahub.provider.local

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.Episode
import com.mediahub.model.LibraryType
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaType
import com.mediahub.model.MediaUser
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.model.Season
import com.mediahub.model.ServerType
import com.mediahub.model.SubtitleTrack
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.Credentials as ProviderCredentials
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderException
import java.io.File
import java.net.URI

/**
 * 本地存储 Provider（真实实现）。
 *
 * - 目录 → MediaItem(FOLDER)，文件 → VIDEO / AUDIO / OTHER；
 * - 播放：解析为 file:// URI 的 [PlaybackSource]（DIRECT_PLAY）；
 * - 不包含媒体库刮削（与 metadata 模块解耦，见 ADR-011）。
 */
class LocalProvider(
    private val server: com.mediahub.model.MediaServer,
    private val rootProvider: LocalRootProvider,
    private val logger: Logger,
) : MediaProvider {

    override val serverId: String get() = server.id
    override val type: ServerType = ServerType.LOCAL
    override val displayName: String get() = server.displayName

    private val libraryId = "local"

    override fun capabilities(): Set<ProviderCapability> =
        setOf(ProviderCapability.BROWSE)

    override suspend fun testConnection(): ConnectionStatus {
        val roots = rootProvider.rootDirectories()
        return if (roots.isEmpty()) {
            ConnectionStatus(ok = false, message = "未配置本地存储目录")
        } else {
            ConnectionStatus(ok = true, message = "本地目录可用（${roots.size} 个）")
        }
    }

    // ---- 认证：本地存储无需认证 ----

    override suspend fun authenticate(credentials: ProviderCredentials): AuthResult =
        AuthResult.Success(localUser())

    override suspend fun refreshSession(): AuthResult = AuthResult.Success(localUser())

    override suspend fun logout() = Unit

    override suspend fun currentUser(): MediaUser? = localUser()

    private fun localUser(): MediaUser =
        MediaUser(serverId = serverId, userId = "local", displayName = "本机")

    // ---- 媒体库 ----

    override suspend fun getLibraries(): List<MediaLibrary> = listOf(
        MediaLibrary(serverId = serverId, id = libraryId, name = "本地存储", type = LibraryType.FILES),
    )

    override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> =
        listFolder(folder = null, page = page)

    override suspend fun getSeasons(seriesId: String): List<Season> = emptyList()

    override suspend fun getEpisodes(seasonId: String): List<Episode> = emptyList()

    override suspend fun getItemDetail(itemId: String): MediaDetail {
        val file = File(itemId)
        if (!file.exists()) throw ProviderException.NotFound(serverId, itemId)
        return MediaDetail(item = file.toMediaItem())
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

    override suspend fun reportProgress(progress: PlaybackProgress) {
        // 本地文件无服务端进度；本地快照由上层（feature:player）负责。
        logger.d(LogTag.PROVIDER, "local reportProgress ignored serverId=$serverId")
    }

    // ---- 搜索 / 字幕 / 继续观看：本地暂不支持，诚实抛出 ----

    override suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "本地存储文件名搜索")

    override suspend fun getSubtitles(itemId: String): List<SubtitleTrack> =
        throw ProviderException.NotYetImplemented(serverId, "本地字幕列表")

    override suspend fun getContinueWatching(limit: Int): List<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "本地继续观看")

    override suspend fun getResumePosition(itemId: String): Long? = null

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
            libraryId = libraryId,
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
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "webm", "ts", "m2ts", "avi", "mov", "wmv", "flv", "3gp",
        )
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma")
    }
}
