package com.mediahub.provider.api

import com.mediahub.model.Episode
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
import com.mediahub.model.MediaUser
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.model.Season
import com.mediahub.model.ServerType
import com.mediahub.model.SubtitleTrack

/** 认证能力。 */
interface MediaAuthProvider {
    suspend fun authenticate(credentials: Credentials): AuthResult
    suspend fun refreshSession(): AuthResult
    suspend fun logout()
    suspend fun currentUser(): MediaUser?
}

/** 媒体库浏览能力。 */
interface MediaLibraryProvider {
    suspend fun getLibraries(): List<MediaLibrary>
    suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem>
    suspend fun getSeasons(seriesId: String): List<Season>
    suspend fun getEpisodes(seasonId: String): List<Episode>
    suspend fun getItemDetail(itemId: String): MediaDetail
}

/** 文件树浏览能力（云盘 / NAS / 本地）。 */
interface MediaBrowseProvider {
    suspend fun listFolder(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem>
}

/** 播放能力。 */
interface MediaPlaybackProvider {
    /** 播放时动态解析播放源（临时 URL，见 ADR-003）。 */
    suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource
    suspend fun reportProgress(progress: PlaybackProgress)
}

/** 搜索能力。 */
interface MediaSearchProvider {
    suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem>
}

/** 字幕能力。 */
interface MediaSubtitleProvider {
    suspend fun getSubtitles(itemId: String): List<SubtitleTrack>
}

/** 进度能力。 */
interface MediaProgressProvider {
    suspend fun getContinueWatching(limit: Int): List<MediaItem>
    suspend fun getResumePosition(itemId: String): Long?
}

/**
 * 统一媒体源接口：所有数据源（Emby / Jellyfin / WebDAV / 本地 / 云盘……）
 * 实现该接口，由 [MediaProviderFactory] 创建。
 *
 * UI 层只依赖该接口与 [com.mediahub.model] 领域模型，
 * 绝不感知具体数据源协议。
 */
interface MediaProvider :
    MediaAuthProvider,
    MediaLibraryProvider,
    MediaBrowseProvider,
    MediaPlaybackProvider,
    MediaSearchProvider,
    MediaSubtitleProvider,
    MediaProgressProvider {

    val serverId: String
    val type: ServerType
    val displayName: String

    /** 声明能力集合（UI 据此展示功能入口）。 */
    fun capabilities(): Set<ProviderCapability>

    /** 连通性 / 网络质量探测（不包含鉴权）。 */
    suspend fun testConnection(): ConnectionStatus
}
