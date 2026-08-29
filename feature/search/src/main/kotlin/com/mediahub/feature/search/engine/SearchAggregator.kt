package com.mediahub.feature.search.engine

import com.mediahub.model.CanonicalKey
import com.mediahub.model.CanonicalKeyPolicy

/**
 * 搜索结果聚合器（Phase 1E 纯投影）：
 * [GlobalSearchState.hits] → [SearchResultEntry] 列表。
 *
 * - 身份等价（冻结规则）：同 MediaType + 共享至少一个相同 (provider, value)
 *   CanonicalKey = 同组；无任何共享键 = 各自单例；title/year 永不参与。
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

        // union-find：同一 item 的多个别名键互为等价；跨 item 共享键并入同组
        val parent = HashMap<CanonicalKey, CanonicalKey>()
        fun find(k: CanonicalKey): CanonicalKey {
            var root = parent.getOrPut(k) { k }
            while (parent.getValue(root) != root) root = parent.getValue(root)
            var cur = k
            while (parent.getValue(cur) != root) {
                val next = parent.getValue(cur)
                parent[cur] = root
                cur = next
            }
            return root
        }
        fun union(a: CanonicalKey, b: CanonicalKey) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }
        keySets.forEach { ks ->
            val l = ks.toList()
            for (i in 1 until l.size) union(l[0], l[i])
        }

        data class Group(var minIndex: Int, val indices: MutableList<Int>, val keys: MutableSet<CanonicalKey>)

        val groupByRoot = LinkedHashMap<CanonicalKey, Group>()
        val entriesWithPos = mutableListOf<Pair<Int, SearchResultEntry>>()

        hits.forEachIndexed { idx, hit ->
            val ks = keySets[idx]
            if (ks.isEmpty()) {
                // 无任何外部 ID（或空白）：单例，绝不聚合（title/year 不参与）
                entriesWithPos += idx to SearchResultEntry.Single(hit)
                return@forEachIndexed
            }
            val root = find(ks.first())
            val bucket = groupByRoot.getOrPut(root) { Group(idx, mutableListOf(), mutableSetOf()) }
            bucket.indices += idx
            bucket.keys += ks
        }

        groupByRoot.values.forEach { g ->
            val sortedIndices = g.indices.sorted()
            val occurrences = sortedIndices.map { hits[it] }
            if (occurrences.map { it.item.serverId }.distinct().size >= 2) {
                entriesWithPos += g.minIndex to SearchResultEntry.MultiSource(
                    identityKeys = g.keys.toSet(),
                    occurrences = occurrences,
                )
            } else {
                // 组内不足两个不同来源（如同服务器同身份的重复条目）：保持单例行（顺序不变）
                sortedIndices.forEach { idx ->
                    entriesWithPos += idx to SearchResultEntry.Single(hits[idx])
                }
            }
        }

        return entriesWithPos.sortedBy { it.first }.map { it.second }
    }
}
