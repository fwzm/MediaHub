package com.mediahub.provider.iptv

import com.mediahub.model.EpgProgram
import com.mediahub.model.LibraryType
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackSource
import com.mediahub.model.Season
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaEpgProvider
import com.mediahub.provider.api.MediaLibraryProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaSearchProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** IPTV 类型描述（Factory 与 Provider 共用）。 */
internal val IPTV_PROVIDER_DESCRIPTOR = ProviderDescriptor(
    id = "iptv",
    serverType = ServerType.IPTV,
    displayName = "IPTV 直播",
    category = ProviderCategory.MEDIA_SERVER,
    declaredCapabilities = setOf(
        ProviderCapability.LIBRARY,
        ProviderCapability.PLAYBACK,
        ProviderCapability.SEARCH,
        ProviderCapability.LIVE_TV,
        ProviderCapability.EPG,
    ),
    authMethod = AuthMethod.NONE,
    status = ProviderStatus.EXPERIMENTAL,
    description = "M3U / M3U8 直播源（频道分组、搜索、直播播放与 XMLTV 节目单）",
    // 播放列表本身就是协议探针：testConnection 拉取并校验 #EXTM3U；
    // 不虚构额外的 GET 探针路径（ADR-039）。
    probePath = null,
)

/**
 * IPTV Provider（只读直播源）。
 *
 * 能力：LIBRARY（频道分组）/ PLAYBACK（直播直链）/ SEARCH（本地频道过滤）/ EPG（XMLTV）。
 *
 * 关键约束：
 * - 直播流地址只驻留内存，**不写入 MediaItem.path**，播放时按频道 id 重新解析；
 * - `resolvePlayback` 只接受 [MediaType.LIVE_TV]，不接受点播条目；
 * - 直播源不存在"续播"语义：忽略 [PlaybackOptions.startPositionMs]，
 *   返回 `supportsSeeking = false`，由播放层走独立起播路径。
 */
internal class IptvProvider(
    private val server: MediaServer,
    private val repository: IptvPlaylistRepository,
) : MediaProvider,
    MediaLibraryProvider,
    MediaPlaybackProvider,
    MediaSearchProvider,
    MediaEpgProvider {

    override val serverId: String get() = server.id
    override val type: ServerType = ServerType.IPTV
    override val displayName: String get() = server.displayName
    override val descriptor: ProviderDescriptor = IPTV_PROVIDER_DESCRIPTOR

    private val playlistUrl: String get() = server.baseUrl.trim()

    override suspend fun testConnection(): ConnectionStatus {
        if (playlistUrl.isBlank()) {
            return ConnectionStatus(ok = false, message = "请填写 IPTV 播放列表地址")
        }
        val startedAt = System.nanoTime()
        return try {
            val playlist = repository.playlist(playlistUrl, forceReload = true)
            val latencyMs = (System.nanoTime() - startedAt) / 1_000_000
            if (playlist.channels.isEmpty()) {
                ConnectionStatus(
                    ok = false,
                    latencyMs = latencyMs,
                    message = "播放列表中没有可播放频道",
                    errorCode = ProviderException.ErrorCode.PARSE,
                )
            } else {
                ConnectionStatus(
                    ok = true,
                    latencyMs = latencyMs,
                    message = "IPTV 可用（${playlist.channels.size} 个频道）",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ProviderException) {
            ConnectionStatus(ok = false, message = connectionMessage(e), errorCode = e.code)
        }
    }

    // ---- 频道库 ----

    override suspend fun getLibraries(): List<MediaLibrary> {
        val groups = LinkedHashMap<String, Int>()
        loadChannels().forEach { channel ->
            groups[channel.group] = (groups[channel.group] ?: 0) + 1
        }
        return groups.map { (group, count) ->
            MediaLibrary(
                serverId = serverId,
                id = group,
                name = group,
                type = LibraryType.LIVE_TV,
                itemCount = count,
            )
        }
    }

    override suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem> {
        val channels = loadChannels().filter { it.group == libraryId }
        return channels.toPage(page) { it.toMediaItem() }
    }

    override suspend fun getSeasons(seriesId: String): List<Season> {
        throw ProviderException.NotYetImplemented(serverId, "IPTV 季/集")
    }

    override suspend fun getEpisodes(seasonId: String): List<com.mediahub.model.Episode> {
        throw ProviderException.NotYetImplemented(serverId, "IPTV 季/集")
    }

    // ---- 搜索（本地过滤，不额外发请求） ----

    override suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem> {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            return PagedResult(emptyList(), totalCount = 0, hasMore = false, nextOffset = null)
        }
        val matched = loadChannels().filter {
            it.name.contains(keyword, ignoreCase = true) ||
                it.group.contains(keyword, ignoreCase = true)
        }
        return matched.toPage(page) { it.toMediaItem() }
    }

    // ---- 直播播放 ----

    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource {
        if (item.type != MediaType.LIVE_TV) {
            throw ProviderException.NotYetImplemented(serverId, "IPTV 仅支持直播频道播放")
        }
        val cached = loadChannels().firstOrNull { it.id == item.id }
        val channel = cached
            ?: repository.playlist(playlistUrl, forceReload = true).channels.firstOrNull { it.id == item.id }
            ?: throw ProviderException.NotFound(serverId, item.title)

        return PlaybackSource(
            url = channel.streamUrl,
            mimeType = mimeTypeOf(channel.streamUrl),
            container = extensionOf(channel.streamUrl),
            durationMs = null,
            mode = PlaybackMode.DIRECT_PLAY,
            supportsSeeking = false,
        )
    }

    // ---- EPG ----

    override suspend fun getPrograms(
        channelIds: Set<String>,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): List<EpgProgram> {
        if (channelIds.isEmpty() || toEpochMs <= fromEpochMs) return emptyList()
        val playlist = repository.playlist(playlistUrl)
        val epgUrl = playlist.epgUrl ?: return emptyList()

        val idToEpg = playlist.channels
            .filter { it.id in channelIds }
            .associate { it.id to it.epgChannelId }
        if (idToEpg.isEmpty()) return emptyList()
        val epgToId = idToEpg.entries.associate { (providerId, epgId) -> epgId to providerId }

        return repository.epg(resolveEpgUrl(playlistUrl, epgUrl))
            .asSequence()
            .filter { program ->
                epgToId.containsKey(program.channelId) &&
                    program.startEpochMs < toEpochMs &&
                    program.endEpochMs > fromEpochMs
            }
            .mapNotNull { program ->
                epgToId[program.channelId]?.let { program.copy(channelId = it) }
            }
            .sortedBy { it.startEpochMs }
            .toList()
    }

    // ---- 内部 ----

    private suspend fun loadChannels(): List<IptvChannel> {
        if (playlistUrl.isBlank()) {
            throw ProviderException.Connection(serverId, "IPTV 播放列表地址为空")
        }
        return repository.playlist(playlistUrl).channels
    }

    private fun resolveEpgUrl(base: String, epgUrl: String): String =
        base.toHttpUrlOrNull()?.resolve(epgUrl)?.toString() ?: epgUrl

    private fun IptvChannel.toMediaItem(): MediaItem = MediaItem(
        serverId = serverId,
        id = id,
        type = MediaType.LIVE_TV,
        title = name,
        libraryId = group,
        posterUrl = logoUrl,
        sortName = name,
    )

    private fun <T> List<T>.toPage(page: PageRequest, map: (T) -> MediaItem): PagedResult<MediaItem> {
        if (page.limit <= 0) {
            return PagedResult(emptyList(), totalCount = size, hasMore = false, nextOffset = null)
        }
        val start = page.offset.coerceAtLeast(0)
        val paged = drop(start).take(page.limit)
        val nextOffset = start + paged.size
        val hasMore = nextOffset < size
        return PagedResult(
            items = paged.map(map),
            totalCount = size,
            hasMore = hasMore,
            nextOffset = if (hasMore) nextOffset else null,
        )
    }

    private fun connectionMessage(e: ProviderException): String = when (e) {
        is ProviderException.Http -> when (e.statusCode) {
            401, 403 -> "无权访问播放列表（HTTP ${e.statusCode}）"
            404 -> "播放列表不存在（HTTP 404）"
            else -> "服务器返回 HTTP ${e.statusCode}"
        }
        is ProviderException.Parse -> "不是有效的 M3U 播放列表"
        is ProviderException.Network -> "连接失败"
        is ProviderException.Connection -> e.message ?: "连接失败"
        else -> "IPTV 连接失败"
    }

    private fun mimeTypeOf(url: String): String? = when (extensionOf(url)) {
        "m3u8" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        "ts", "m2ts" -> "video/mp2t"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "flv" -> "video/x-flv"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        else -> null
    }

    private fun extensionOf(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        if (!name.contains('.')) return null
        return name.substringAfterLast('.').lowercase().takeIf { it.isNotBlank() }
    }
}
