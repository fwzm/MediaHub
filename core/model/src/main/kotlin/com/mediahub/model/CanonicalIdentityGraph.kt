package com.mediahub.model

/**
 * Canonical identity 连通分量（ADR-037/038 语义 single source of truth）。
 *
 * 输入 = 每个 item 的别名 [CanonicalKey] 集合（按 [CanonicalKeyPolicy.keys] 产出）。
 * 语义（ADR-037 冻结）：同一 item 的多别名互为 alias，共享任一相同
 * (provider, value) 的 item 之间建立 identity edge，**分量 = alias graph 的
 * connected component（传递闭包）**，非 direct-intersection 成对判定。
 *
 * 本 object 只拥有分量语义，不依赖任何 feature 类型（ADR-038）：
 * 搜索侧 SearchAggregator 与 Detail 侧 CanonicalSourceResolver 均以此为唯一实现，
 * 禁止各自手写 union-find 造成语义漂移。
 *
 * 空 keySet（无有效外部 ID）不参与任何分量——title/year 永不参与 identity 判定。
 */
object CanonicalIdentityGraph {

    /** 一个连通分量：[indices] 升序成员位置；[identityKeys] = 组内别名键并集。 */
    data class Component(
        val indices: List<Int>,
        val identityKeys: Set<CanonicalKey>,
    )

    /** 按 keySets 划分连通分量；返回按成员首位置升序排序（与首个命中顺序一致）。 */
    fun components(keySets: List<Set<CanonicalKey>>): List<Component> {
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
            if (l.isNotEmpty()) for (i in 1 until l.size) union(l[0], l[i])
        }

        data class Group(val indices: MutableList<Int>, val keys: MutableSet<CanonicalKey>)

        val groupByRoot = LinkedHashMap<CanonicalKey, Group>()
        keySets.forEachIndexed { idx, ks ->
            if (ks.isEmpty()) return@forEachIndexed
            val root = find(ks.first())
            val bucket = groupByRoot.getOrPut(root) { Group(mutableListOf(), mutableSetOf()) }
            bucket.indices += idx
            bucket.keys += ks
        }

        return groupByRoot.values
            .map { Component(it.indices.sorted(), it.keys.toSet()) }
            .sortedBy { it.indices.first() }
    }
}
