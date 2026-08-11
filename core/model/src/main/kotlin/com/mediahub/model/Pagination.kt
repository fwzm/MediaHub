package com.mediahub.model

/** 分页请求。 */
data class PageRequest(
    val offset: Int = 0,
    val limit: Int = 50,
) {
    val next: PageRequest get() = copy(offset = offset + limit)
}

/** 分页结果。 */
data class PagedResult<T>(
    val items: List<T>,
    val totalCount: Int? = null,
    val hasMore: Boolean = items.isNotEmpty(),
    val nextOffset: Int? = if (hasMore) items.size else null,
)
