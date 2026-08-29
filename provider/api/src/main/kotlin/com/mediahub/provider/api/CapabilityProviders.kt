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

/**
 * 查询能力（Phase 1C-2 Query Pipeline；1D 起 sort 与 filter 能力合一）：
 * 把排序/筛选下沉到 Provider / 服务器，在分页之前执行。
 *
 * - 迁移策略：与 [MediaLibraryProvider.getItems] 并存的兼容能力；未实现本能力的 Provider
 *   由调用方回退 `library.getItems(libraryId, query.page)`（服务器默认排序、无筛选）。
 * - 红线：实现方禁止在拿到分页结果后再做本地 sortedBy/filter——那只会作用于当前页，
 *   全库排序/筛选语义会直接错误。
 * - 能力自述：[capabilities] 是 UI 隐藏/禁用不支持排序/筛选项的唯一来源，
 *   禁止上层按 ServerType 硬编码判断。
 */
interface MediaQueryLibraryProvider {
    /** 该数据源真实支持的排序/筛选字段。 */
    val capabilities: com.mediahub.model.MediaQueryCapabilities

    suspend fun getItems(libraryId: String, query: com.mediahub.model.MediaListQuery): PagedResult<MediaItem>
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
