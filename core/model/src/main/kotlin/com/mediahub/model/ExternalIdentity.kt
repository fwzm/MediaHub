package com.mediahub.model

/**
 * 跨源身份的外部 ID 集合（Phase 1E）。
 *
 * 各字段间**无优先级**——身份等价判定由 [CanonicalKeyPolicy.keys] 生成的
 * 候选键集合按交集完成，不存在"选一个主键"。
 * 归一化（key 小写 / value trim / 空白丢弃）由 Provider 映射层负责；
 * 本类只承载归一化后的值。
 */
data class ExternalIds(
    val tmdb: String? = null,
    val imdb: String? = null,
    val tvdb: String? = null,
) {
    val isEmpty: Boolean get() = this == ExternalIds()
}

/** 外部 ID 提供方（用于 [CanonicalKey] 的类型维度隔离）。 */
enum class ExternalIdProvider {
    TMDB,
    TVDB,
    IMDB,
}

/**
 * 跨源归一键（Phase 1E）。
 *
 * 三元组 = (MediaType, provider, value)：MediaType 内嵌保证
 * `MOVIE/TMDB/123 ≠ SERIES/TMDB/123`（TMDb 电影与剧集 ID 空间独立）。
 *
 * 身份等价规则（冻结）：同 MediaType + 共享至少一个相同 (provider, value) 键
 * = 可聚合；无任何共享键 = 绝不聚合；title/year 永不参与 identity 判定。
 */
data class CanonicalKey(
    val type: MediaType,
    val provider: ExternalIdProvider,
    val value: String,
)

/**
 * [ExternalIds] + [MediaType] → 候选 [CanonicalKey] 集合。
 *
 * 返回的是**别名集合**（非单一主键）：同一 item 可同时持有 TMDB+IMDb 等多个身份，
 * 跨服务器的 ID 覆盖往往互不完整——集合交集匹配才能聚合"ID 各缺一块"的条目。
 * 空白/缺失值跳过；无任何有效值返回空集（= 不参与聚合）。
 */
object CanonicalKeyPolicy {

    fun keys(type: MediaType, ids: ExternalIds?): Set<CanonicalKey> {
        if (ids == null || ids.isEmpty) return emptySet()
        return buildSet {
            ids.tmdb?.takeIf(String::isNotBlank)?.let { add(CanonicalKey(type, ExternalIdProvider.TMDB, it)) }
            ids.imdb?.takeIf(String::isNotBlank)?.let { add(CanonicalKey(type, ExternalIdProvider.IMDB, it)) }
            ids.tvdb?.takeIf(String::isNotBlank)?.let { add(CanonicalKey(type, ExternalIdProvider.TVDB, it)) }
        }
    }
}
