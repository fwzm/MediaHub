package com.mediahub.provider.api

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
import com.mediahub.model.SubtitleTrack

/**
 * 可选能力接口（Interface Segregation，见 ADR-014）。
 * 每个 Provider 只实现自己真实具备的能力；ProviderHandle 负责组合。
 */

/** 认证能力。 */
interface MediaAuthProvider {
    suspend fun authenticate(credentials: Credentials): AuthResult
    suspend fun refreshSession(): AuthResult

    /**
     * 恢复会话（App 启动 / 页面进入时调用）：读取本地凭据并做真实服务器验证，
     * 不无条件信任本地 Token。返回 [AuthSessionState] 驱动 UI 登录态。
     */
    suspend fun restoreSession(): AuthSessionState

    suspend fun logout()
    suspend fun currentUser(): MediaUser?
}

/** 媒体库能力（服务器型数据源）。 */
interface MediaLibraryProvider {
    suspend fun getLibraries(): List<MediaLibrary>
    suspend fun getItems(libraryId: String, page: PageRequest): PagedResult<MediaItem>
    suspend fun getSeasons(seriesId: String): List<Season>
    suspend fun getEpisodes(seasonId: String): List<com.mediahub.model.Episode>
}

/** 条目详情能力。 */
interface MediaDetailProvider {
    suspend fun getItemDetail(itemId: String): MediaDetail
}

/** 文件树浏览能力（云盘 / NAS / 本地）。 */
interface MediaBrowseProvider {
    suspend fun listFolder(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem>
}

/** 播放能力：播放时动态解析播放源（临时 URL，见 ADR-003）。 */
interface MediaPlaybackProvider {
    suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource
}

/** 搜索能力。 */
interface MediaSearchProvider {
    suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem>
}

/** 字幕能力。 */
interface MediaSubtitleProvider {
    suspend fun getSubtitles(itemId: String): List<SubtitleTrack>
}

/**
 * 进度能力。
 *
 * [remoteReportIntervalMs]：远端进度上报节流间隔（见 ADR-017），
 * 由 Provider 按自身协议要求覆写（如 Emby/Jellyfin 建议 ≥10s）。
 */
interface MediaProgressProvider {
    suspend fun reportProgress(progress: PlaybackProgress)
    suspend fun getContinueWatching(limit: Int): List<MediaItem>
    suspend fun getResumePosition(itemId: String): Long?

    /** 远端进度上报最小间隔；默认 10s（本地快照另有 5s 采样，见 ADR-017）。 */
    val remoteReportIntervalMs: Long get() = DEFAULT_REMOTE_INTERVAL_MS

    companion object {
        const val DEFAULT_REMOTE_INTERVAL_MS = 10_000L
    }
}
