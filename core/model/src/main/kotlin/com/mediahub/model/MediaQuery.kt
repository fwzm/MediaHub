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
 * 列表筛选字段（Phase 1D Query Pipeline）。
 *
 * 首版只开放四种子能力；协议命名止步于各 Provider 映射层。
 */
enum class MediaFilterField {
    /** 按媒体类型（首版标准语义限定 MOVIE / SERIES / EPISODE）。 */
    MEDIA_TYPE,

    /** 出品年份（单年等值）。 */
    YEAR,

    /** 已看 / 未看（tri-state，服务器语义）。 */
    PLAYED,

    /** 收藏（tri-state）。 */
    FAVORITE,
}

/**
 * 列表筛选（immutable aggregate，Phase 1D）。
 *
 * 每个字段 tri-state：null = 不过滤。全部为 null 即默认态（[isDefault]）。
 *
 * 重要语义（1D 评审锁定）：filter 是「当前 container listing 的状态」，
 * **不跨容器继承**——进入子容器时必须重置、返回父容器时必须恢复，
 * 与 Sort（跨容器保持）刻意不同；导航栈负责 push/reset/restore。
 */
data class MediaFilter(
    val mediaType: MediaType? = null,
    val year: Int? = null,
    val played: Boolean? = null,
    val favorite: Boolean? = null,
) {
    init {
        require(year == null || year > 0) { "year 必须为正整数或 null" }
    }

    /** 全部字段均不过滤（默认态）。 */
    val isDefault: Boolean get() = this == MediaFilter()
}

/**
 * 单库列表查询（Phase 1C-2 起，1D 增加 filter）。
 *
 * 只表达"怎么取"，不表达"取什么"以外的跨容器语义；
 * 分页只占 [page]，排序只占 [sort]，筛选只占 [filter]——
 * 三者都必须下沉到 Provider/服务器执行，禁止 UI 对已加载的当前页做本地 sortedBy/filter。
 */
data class MediaListQuery(
    val page: PageRequest = PageRequest(),
    val sort: MediaSort = MediaSort(MediaSortField.SERVER_DEFAULT),
    val filter: MediaFilter = MediaFilter(),
)

/**
 * 查询能力自述（Phase 1D 起 sort 与 filter 合一）。
 *
 * UI 依据本能力隐藏/禁用不支持的排序与筛选项，禁止按 ServerType 硬编码。
 */
data class MediaQueryCapabilities(
    val sortFields: Set<MediaSortField> = setOf(MediaSortField.SERVER_DEFAULT),
    val filterFields: Set<MediaFilterField> = emptySet(),
) {
    /** 是否支持某排序字段。 */
    fun supportsSort(field: MediaSortField): Boolean = field in sortFields

    /** 是否支持某筛选字段。 */
    fun supportsFilter(field: MediaFilterField): Boolean = field in filterFields

    /** 过滤出该数据源真正支持的排序选项（保持传入顺序）。 */
    fun filterSortFields(order: List<MediaSortField>): List<MediaSortField> = order.filter(::supportsSort)

    /** 过滤出该数据源真正支持的筛选选项（保持传入顺序）。 */
    fun filterFilterFields(order: List<MediaFilterField>): List<MediaFilterField> = order.filter(::supportsFilter)

    companion object {
        /** 仅服务器默认排序、无筛选（等同不支持排序改写/筛选的 Provider 的最小能力）。 */
        val SERVER_DEFAULT_ONLY = MediaQueryCapabilities(
            sortFields = setOf(MediaSortField.SERVER_DEFAULT),
            filterFields = emptySet(),
        )
    }
}
