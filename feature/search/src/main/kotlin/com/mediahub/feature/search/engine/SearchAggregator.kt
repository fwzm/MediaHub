package com.mediahub.feature.search.engine

import com.mediahub.model.CanonicalIdentityGraph
import com.mediahub.model.CanonicalKeyPolicy

/**
 * 搜索结果聚合器（Phase 1E 纯投影；ADR-038 起为 CanonicalIdentityGraph 的搜索侧 adapter）：
 * [GlobalSearchState.hits] → [SearchResultEntry] 列表。
 *
 * - 身份等价语义由 core:model 的 CanonicalIdentityGraph 唯一承载
 *   （同 MediaType + 共享至少一个相同 (provider, value) CanonicalKey = 同组，
 *   connected component 传递闭包；title/year 永不参与）。
 * - 多来源卡成立条件：组内 distinct serverId ≥ 2（同服务器重复条目不构成
 *   "多来源"，按单例渲染）。
 * - 纯函数重算，不发起请求、不缓存、不改 [GlobalSearchEngine] 的
 *   并发/取消/部分成功语义。
 * - 输出顺序：按各组首个命中在原 hits 中的位置稳定排序（与服务器完成顺序无关）。
 */
object SearchAggregator {

    fun group(hits: List<UnifiedSearchHit>): List<SearchResultEntry> {
        if (hits.isEmpty()) return emptyList()

        val keySets = hits.map { CanonicalKeyPolicy.keys(it.item.type, it.item.externalIds) }
        val components = CanonicalIdentityGraph.components(keySets)

        val entriesWithPos = mutableListOf<Pair<Int, SearchResultEntry>>()

        hits.forEachIndexed { idx, hit ->
            if (keySets[idx].isEmpty()) {
                // 无任何外部 ID（或空白）：单例，绝不聚合（title/year 不参与）
                entriesWithPos += idx to SearchResultEntry.Single(hit)
            }
        }

        components.forEach { c ->
            val occurrences = c.indices.map { hits[it] }
            if (occurrences.map { it.item.serverId }.distinct().size >= 2) {
                entriesWithPos += c.indices.first() to SearchResultEntry.MultiSource(
                    identityKeys = c.identityKeys,
                    occurrences = occurrences,
                )
            } else {
                // 组内不足两个不同来源（如同服务器同身份的重复条目）：保持单例行（顺序不变）
                c.indices.forEach { idx ->
                    entriesWithPos += idx to SearchResultEntry.Single(hits[idx])
                }
            }
        }

        return entriesWithPos.sortedBy { it.first }.map { it.second }
    }
}
