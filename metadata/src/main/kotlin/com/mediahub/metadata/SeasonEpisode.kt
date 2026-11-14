package com.mediahub.metadata

/**
 * 元数据季（TMDB 等外部源视角，**不要求已入库**）。
 *
 * 与 `core:model.Season` 的区别：Season 带 serverId/seriesId 等本地资源标识，
 * 只描述"库里有什么"；本类描述"外部源声称有什么"，供详情页补全未入库季集。
 */
data class MetadataSeason(
    val seasonNumber: Int,
    val title: String,
    val overview: String? = null,
    val episodeCount: Int? = null,
    val posterUrl: String? = null,
    val airDate: String? = null,
)

/** 元数据单集（外部源视角，未入库/未播出也可存在）。 */
data class MetadataEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val overview: String? = null,
    val stillUrl: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null,
)

/**
 * 季集查询能力（可选能力接口，避免给 [MetadataProvider] 增加无关职责）。
 *
 * 仅剧集类数据源实现；电影详情不需要季集。
 */
interface SeasonEpisodeProvider {

    suspend fun seasons(metadataId: String): List<MetadataSeason>

    suspend fun episodes(metadataId: String, seasonNumber: Int): List<MetadataEpisode>
}
