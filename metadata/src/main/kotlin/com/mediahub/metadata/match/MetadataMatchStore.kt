package com.mediahub.metadata.match

import com.mediahub.metadata.MetadataMediaType
import com.mediahub.metadata.MetadataSource

/**
 * 人工确认后的刮削匹配结果（只保存「指向哪个元数据条目」，不改写媒体库数据）。
 */
data class MetadataMatch(
    val source: MetadataSource,
    val metadataId: String,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val mediaType: MetadataMediaType? = null,
)

/**
 * 匹配结果存储：按 (serverId, itemId) 读写。
 *
 * 实现方不得因存储缺失/损坏而抛异常到 UI；读不到返回 null 即可。
 */
interface MetadataMatchStore {

    suspend fun get(serverId: String, itemId: String): MetadataMatch?

    suspend fun put(serverId: String, itemId: String, match: MetadataMatch)
}
