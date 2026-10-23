package com.mediahub.provider.api

import com.mediahub.model.CanonicalKey
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
 * Canonical identity 精确查找能力（Phase 1F B1，ADR-038）：
 * 按候选 [CanonicalKey] 集合在服务端做精确身份匹配（非文本搜索），
 * 供 Detail 侧 CanonicalSourceResolver 发现同作品来源。
 *
 * - [keys] 必须非空且同 MediaType（CanonicalKey 内嵌类型维度）；
 *   空集/混合类型属调用方契约错误，实现方抛 IllegalArgumentException。
 * - 返回 MediaItem 必须携带 serverId 与归一化 externalIds（resolver 复用
 *   CanonicalIdentityGraph 校验并扩张 frontier）；实现禁止本地 filter。
 */
interface MediaIdentityLookupProvider {
    suspend fun findByCanonicalKeys(keys: Set<CanonicalKey>, page: PageRequest): PagedResult<MediaItem>
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

    /**
     * 最终退出上报（Phase 1G-C，ADR-039）：播放器 final flush 的**唯一权威远端操作**。
     *
     * 默认实现 = [reportProgress]（对无 session-lifecycle 语义的 Provider 零变化）；
     * 有 server session lifecycle 的协议（如 Jellyfin 的
     * /Sessions/Playing/Stopped）override 本方法发送各自的 final 操作。
     * 调用时机：PlayerViewModel.stopAndFlush 的单次退出 flush——每段播放恰好一次。
     */
    suspend fun reportFinalProgress(progress: PlaybackProgress) {
        reportProgress(progress)
    }

    suspend fun getContinueWatching(limit: Int): List<MediaItem>
    suspend fun getResumePosition(itemId: String): Long?

    /** 远端进度上报最小间隔；默认 10s（本地快照另有 5s 采样，见 ADR-017）。 */
    val remoteReportIntervalMs: Long get() = DEFAULT_REMOTE_INTERVAL_MS

    companion object {
        const val DEFAULT_REMOTE_INTERVAL_MS = 10_000L
    }
}

/**
 * 收藏能力（task-22 / A1：Android 收藏端到端，数据层 + 提供者层）。
 *
 * 语义硬约束（真机验证，Emby 4.10.2 与 Jellyfin 12.2 一致）：
 * - 写端点两端均为 `POST/DELETE /Users/{userId}/FavoriteItems/{itemId}`；
 *   `/Users/{userId}/Favorites/{itemId}` 在两个真服上**都是 404**，禁止使用。
 * - **写后必须回读**服务器真实状态：返回值是"服务器回读到的 IsFavorite"，
 *   不是"我们发出了什么"。写成功但服务器忽略时返回 false（不得谎报成功）。
 * - 回读字段缺失（服务器未返回 UserData.IsFavorite）视为**协议破损**，
 *   抛 [ProviderException.Parse]，绝不静默降级为 false。
 *
 * 收藏状态**只在服务端**（UserData），本能力不引入本地表（见 docs/android-favorites.md）。
 */
interface MediaFavoriteProvider {
    /**
     * 收藏 / 取消收藏并回读。
     *
     * @return 服务器回读到的真实收藏状态（true=已收藏）。
     * @throws ProviderException.AuthRequired 未登录 / 会话缺失。
     * @throws ProviderException.AuthExpired 服务器返回 401。
     * @throws ProviderException.NotFound 服务器返回 404（条目不存在 / 端点不存在）。
     * @throws ProviderException.Http 其他非 2xx。
     * @throws ProviderException.Parse 回读缺 UserData.IsFavorite。
     */
    suspend fun setFavorite(itemId: String, favorite: Boolean): Boolean

    /**
     * 只读查询服务器侧收藏状态（同样以回读为准）。
     *
     * @throws ProviderException.Parse 回读缺 UserData.IsFavorite。
     */
    suspend fun isFavorite(itemId: String): Boolean

    /**
     * 列出服务器侧收藏（「我的收藏」列表页的数据源，task-25 / A2）。
     *
     * 服务端侧筛选：`GET /Users/{userId}/Items?Filters=IsFavorite&Recursive=true&…`。
     * 服务器侧筛选只是**优化不是保证**（服务器可能忽略 Filters，也可能返回
     * IsFavorite=false 的条目），实现**必须**在客户端再按 [MediaItem.isFavorite]
     * 兜底过滤，不得把未收藏条目显示成收藏。
     *
     * 顺序由服务器决定（默认 SortName 升序）；不承诺分页：[limit] 是单次上限，
     * 实现会 coerce 到 1..[MAX_FAVORITES_LIMIT]；`limit <= 0` 直接返回空列表且不发 HTTP。
     *
     * @throws ProviderException.AuthRequired 未登录 / 会话缺失。
     * @throws ProviderException.AuthExpired 服务器返回 401。
     * @throws ProviderException.NotFound 服务器返回 404。
     * @throws ProviderException.Http 其他非 2xx。
     */
    suspend fun listFavorites(limit: Int = DEFAULT_FAVORITES_LIMIT): List<MediaItem>

    companion object {
        /** 默认单次拉取上限（列表页首屏）。 */
        const val DEFAULT_FAVORITES_LIMIT = 100

        /** 单次拉取硬上限（防止误传巨大值把服务器打满）。 */
        const val MAX_FAVORITES_LIMIT = 200
    }
}
