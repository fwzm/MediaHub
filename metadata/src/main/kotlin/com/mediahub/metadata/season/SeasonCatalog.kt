package com.mediahub.metadata.season

import com.mediahub.metadata.MetadataEpisode
import com.mediahub.metadata.MetadataSeason
import com.mediahub.model.Episode
import com.mediahub.model.Season

/**
 * 季在"外部源声明"与"本地库"之间的可用性视图。
 *
 * [inLibrary] = false 的项就是"尚未入库"的季，详情页仍需展示（条目 128）。
 */
data class SeasonAvailability(
    val seasonNumber: Int,
    val title: String,
    val overview: String? = null,
    val episodeCount: Int? = null,
    val posterUrl: String? = null,
    val airDate: String? = null,
    val inLibrary: Boolean = false,
    val librarySeasonId: String? = null,
)

/** 单集可用性视图，语义同 [SeasonAvailability]。 */
data class EpisodeAvailability(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val overview: String? = null,
    val stillUrl: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null,
    val inLibrary: Boolean = false,
    val libraryEpisodeId: String? = null,
)

/**
 * 季/集目录合并：外部源提供"声称存在"，本地库提供"实际已有"。
 *
 * 规则：取两者编号并集；同编号时标题/简介/集数以外部源优先，缺失字段回退本地；
 * 本地独有季（如本地花絮）保留，避免"未匹配元数据"的季从列表消失。
 */
object SeasonCatalog {

    fun merge(external: List<MetadataSeason>, library: List<Season>): List<SeasonAvailability> {
        val externalByNumber = external.associateBy { it.seasonNumber }
        val libraryByNumber = library.associateBy { it.seasonNumber }
        val numbers = (externalByNumber.keys + libraryByNumber.keys).sorted()
        return numbers.map { number ->
            val ext = externalByNumber[number]
            val local = libraryByNumber[number]
            SeasonAvailability(
                seasonNumber = number,
                title = ext?.title ?: local?.name.orEmpty(),
                overview = ext?.overview ?: local?.overview,
                episodeCount = ext?.episodeCount ?: local?.episodeCount,
                posterUrl = ext?.posterUrl ?: local?.imageUrl,
                airDate = ext?.airDate,
                inLibrary = local != null,
                librarySeasonId = local?.id,
            )
        }
    }
}

object EpisodeCatalog {

    fun merge(
        seasonNumber: Int,
        external: List<MetadataEpisode>,
        library: List<Episode>,
    ): List<EpisodeAvailability> {
        val externalByNumber = external.associateBy { it.episodeNumber }
        val libraryByNumber = library.associateBy { it.episodeNumber }
        val numbers = (externalByNumber.keys + libraryByNumber.keys).sorted()
        return numbers.map { number ->
            val ext = externalByNumber[number]
            val local = libraryByNumber[number]
            EpisodeAvailability(
                seasonNumber = seasonNumber,
                episodeNumber = number,
                title = ext?.title ?: local?.name.orEmpty(),
                overview = ext?.overview ?: local?.overview,
                stillUrl = ext?.stillUrl ?: local?.imageUrl,
                airDate = ext?.airDate ?: local?.airDate,
                runtimeMinutes = ext?.runtimeMinutes ?: local?.runtimeMs?.let { (it / 60_000L).toInt() },
                inLibrary = local != null,
                libraryEpisodeId = local?.id,
            )
        }
    }
}
