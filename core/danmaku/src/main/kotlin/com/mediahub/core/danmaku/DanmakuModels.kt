package com.mediahub.core.danmaku

/** 弹幕运动模式（与 B 站/弹弹play 的 1-3 滚动、4 底部、5 顶部对齐）。 */
enum class DanmakuMode { SCROLL, TOP, BOTTOM }

/**
 * 单条弹幕。时间轴为相对视频起点的毫秒数。
 * [colorArgb] 始终是不透明的 ARGB（解析时补 0xFF alpha）；
 * 透明度由渲染层统一控制，避免同一条弹幕忽深忽浅。
 */
data class DanmakuComment(
    val id: String,
    val timeMs: Long,
    val text: String,
    val mode: DanmakuMode = DanmakuMode.SCROLL,
    val colorArgb: Int = DEFAULT_COLOR_ARGB,
    val fontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
    val source: String = SOURCE_LOCAL,
) {
    companion object {
        const val DEFAULT_COLOR_ARGB: Int = -1 // 0xFFFFFFFF
        const val DEFAULT_FONT_SIZE_SP: Int = 25
        const val SOURCE_LOCAL: String = "local"
    }
}

/** 在线剧集搜索结果（弹弹play）。 */
data class DanmakuEpisode(
    val id: String,
    val animeTitle: String = "",
    val episodeTitle: String = "",
    val episodeNumber: Int? = null,
)

/** 弹幕拉取参数；[itemKey] 是缓存与本地弹幕的稳定键（serverId + itemId）。 */
data class DanmakuQuery(
    val itemKey: String,
    val seriesTitle: String,
    val episodeTitle: String? = null,
    val durationMs: Long? = null,
    val onlineEnabled: Boolean = false,
    val danDanPlay: DanDanPlayConfig = DanDanPlayConfig(),
    val forceRefresh: Boolean = false,
)

/** 一次加载的结果。仓库不向上抛网络/解析异常，错误随结果返回（条目 59）。 */
data class DanmakuSnapshot(
    val comments: List<DanmakuComment> = emptyList(),
    val fromCache: Boolean = false,
    val sourceLabel: String? = null,
    val error: String? = null,
)

data class DanmakuSearchResult(
    val episodes: List<DanmakuEpisode> = emptyList(),
    val error: String? = null,
)

/** 数据源不可用（未配置凭据、HTTP 错误、未匹配剧集）。 */
class DanmakuSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 可插拔在线弹幕源。实现必须可被取消，并在失败时抛 [DanmakuSourceException]。 */
interface DanmakuSource {
    val label: String

    suspend fun fetch(query: DanmakuQuery): List<DanmakuComment>
}

/** 支持按关键词搜索剧集的数据源（弹弹play）。 */
interface DanmakuEpisodeSearch {
    suspend fun searchEpisodes(config: DanDanPlayConfig, keyword: String): List<DanmakuEpisode>
}

/** 支持按站点剧集 id 直接取全片弹幕的数据源。 */
interface DanmakuEpisodeCommentSource {
    suspend fun fetchEpisode(config: DanDanPlayConfig, episodeId: String): List<DanmakuComment>
}
