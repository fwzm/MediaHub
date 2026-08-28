package com.mediahub.model

/**
 * 列表排序字段（Phase 1C-2 Query Pipeline）。
 *
 * 跨 Provider 统一语义，协议命名（如 Emby SortBy）止步于各 Provider 映射层，
 * 禁止以 embyXxx 之类命名污染 domain。
 */
enum class MediaSortField {
    /** 服务器自身默认排序（请求不携带任何 SortBy 改写）。 */
    SERVER_DEFAULT,

    /** 加入服务器时间。 */
    DATE_ADDED,

    /** 标题（按 sortName 语义）。 */
    TITLE,

    /** 公众评分。 */
    COMMUNITY_RATING,

    /** 影评人评分。 */
    CRITIC_RATING,

    /** 出品年份。 */
    PRODUCTION_YEAR,

    /** 首映日期。 */
    PREMIERE_DATE,

    /** 官方分级（如 PG-13 / R）。 */
    OFFICIAL_RATING,

    /** 播放时长。 */
    RUNTIME,

    /** 比特率。 */
    BITRATE,

    /** 文件大小。 */
    SIZE,

    /** 随机（快照语义，见 [MediaSort]）。 */
    RANDOM,
}

/** 排序方向。 */
enum class SortDirection {
    ASC,
    DESC,
}

/**
 * 单库列表排序。
 *
 * 方向语义：[MediaSortField.SERVER_DEFAULT] 与 [MediaSortField.RANDOM] 没有方向概念
 * （[hasDirection] 为 false），Provider 映射层必须忽略其 direction，不得拼进请求。
 *
 * RANDOM 是"一次随机快照"语义而非普通无限分页：服务器端随机排序在跨页请求时
 * 通常不可复现（每页各自重随机 → 重复/漏项），因此 Provider 对 RANDOM 只承诺
 * 单次请求返回的快照结果，调用方不得对 RANDOM 结果继续 [PageRequest.next] 翻页。
 */
data class MediaSort(
    val field: MediaSortField,
    val direction: SortDirection = SortDirection.ASC,
) {
    /** 该排序字段是否有方向语义。 */
    val hasDirection: Boolean
        get() {
            // `field` 在 accessor 内是本属性的 backing field 伪变量，必须显式 this.field 取 MediaSort.field
            val f = this.field
            return f != MediaSortField.SERVER_DEFAULT && f != MediaSortField.RANDOM
        }
}

/**
 * 单库列表查询（Phase 1C-2）。
 *
 * 只表达"怎么取"，不表达"取什么"（过滤条件未来在此演进）；
 * 分页只占 [page]，排序只占 [sort] —— 排序必须下沉到 Provider/服务器
 * 在分页之前执行，禁止 UI 对已加载的当前页做本地 sortedBy。
 */
data class MediaListQuery(
    val page: PageRequest = PageRequest(),
    val sort: MediaSort = MediaSort(MediaSortField.SERVER_DEFAULT),
)

/**
 * 数据源真实支持的排序字段集合（Provider 能力自述）。
 *
 * UI 必须依据本能力隐藏/禁用不支持项，禁止按 ServerType 硬编码判断。
 */
data class MediaSortCapabilities(
    val fields: Set<MediaSortField>,
) {
    /** 是否支持某排序字段。 */
    fun supports(field: MediaSortField): Boolean = field in fields

    /** 过滤出该数据源真正支持的排序选项（保持传入顺序）。 */
    fun filter(fields: List<MediaSortField>): List<MediaSortField> = fields.filter(::supports)

    companion object {
        /** 仅服务器默认排序（等同不支持排序改写的 Provider 的最小能力）。 */
        val SERVER_DEFAULT_ONLY = MediaSortCapabilities(setOf(MediaSortField.SERVER_DEFAULT))
    }
}
