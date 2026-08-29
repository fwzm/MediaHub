package com.mediahub.feature.search.engine

import com.mediahub.model.CanonicalKey

/**
 * 搜索结果的聚合投影条目（Phase 1E）：
 * [GlobalSearchState.hits] 经 [SearchAggregator.group] 的纯函数投影，
 * 引擎的并发/取消/部分成功语义零改动。
 */
sealed interface SearchResultEntry {

    /** 无（有效）跨源身份，或同源重复身份未构成多来源——按现有单源行渲染。 */
    data class Single(
        val hit: UnifiedSearchHit,
    ) : SearchResultEntry

    /**
     * 多来源聚合卡：canonical identity 匹配 **且 distinct serverId ≥ 2**。
     *
     * - [identityKeys]：组内全部别名键的并集（TMDB+IMDb 互为别名）
     * - [occurrences]：组成员，保持搜索结果原序
     * - [sourceCount]：distinct serverId 数（"N 个来源" 的唯一口径，
     *   同服务器的重复条目不计为第二个来源）
     * - 卡片元数据取 [occurrences] 首条（不做字段级融合）
     */
    data class MultiSource(
        val identityKeys: Set<CanonicalKey>,
        val occurrences: List<UnifiedSearchHit>,
    ) : SearchResultEntry {
        val sourceCount: Int get() = occurrences.map { it.item.serverId }.distinct().size
    }
}
