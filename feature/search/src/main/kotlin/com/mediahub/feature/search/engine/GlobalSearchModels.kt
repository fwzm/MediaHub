package com.mediahub.feature.search.engine

import com.mediahub.model.MediaItem
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult

/**
 * 聚合搜索单个数据源目标（Phase 1C-1）。
 *
 * [search] 由调用方从 ProviderHandle.search 适配而来（或测试 fake），
 * 引擎不依赖 provider/api 类型，保持纯 coroutines + core:model。
 */
data class SearchTarget(
    /** 稳定身份 = MediaServer.id（状态键、错误键、取消路由都用它）。 */
    val serverId: String,
    val serverName: String,
    val search: suspend (query: String, page: PageRequest) -> PagedResult<MediaItem>,
)

/**
 * 聚合搜索命中：条目 + 来源服务器名。
 *
 * 红线：不把 serverName 塞进 [MediaItem]（domain 不被展示层污染）；
 * 同名影片在多个服务器是两个真实播放源，首版不按标题去重，全部保留。
 */
data class UnifiedSearchHit(
    val item: MediaItem,
    val serverName: String,
)

/**
 * 聚合搜索状态（partial success 语义）。
 *
 * - [hits]：已完成服务器的全部命中，按 targets 传入顺序稳定排列（与完成顺序无关）。
 * - [searchingServers] / [completedServers] / [errors] 一律以 serverId 为键。
 * - 部分失败绝不吞掉其它服务器的结果：B 超时 ≠ 整个搜索 Error。
 */
data class GlobalSearchState(
    val query: String = "",
    val hits: List<UnifiedSearchHit> = emptyList(),
    val searchingServers: Set<String> = emptySet(),
    val completedServers: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
) {
    /** 仍有服务器未返回（UI 显示进行中指示）。 */
    val isSearching: Boolean get() = searchingServers.isNotEmpty()

    companion object {
        /** 无查询（或空白）时的空态。 */
        fun idle(query: String = "") = GlobalSearchState(query = query)
    }
}
