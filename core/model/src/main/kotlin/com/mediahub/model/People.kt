package com.mediahub.model

/** 演职人员。 */
data class Person(
    val name: String,
    val role: Role,
    /** Provider 层人员 ID（用于图片 URL 等后续请求）。 */
    val id: String? = null,
    /** 原始类型字符串（Actor/Director/Writer 等，保留 Provider 语义）。 */
    val type: String? = null,
    val imageUrl: String? = null,
) {
    enum class Role { ACTOR, DIRECTOR, WRITER, PRODUCER, OTHER }
}

/** 类型标签。 */
data class Genre(val name: String)

/** 收藏（跨数据源统一）。 */
data class Favorite(
    val serverId: String,
    val itemId: String,
    val addedAtEpochMs: Long,
)

/** 合集 / 片单。 */
data class Collection(
    val serverId: String,
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val itemCount: Int? = null,
)
