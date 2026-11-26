package com.mediahub.metadata.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** TMDB 媒体类型（电影/剧集 ID 空间独立，必须随 ID 一起携带）。 */
enum class TmdbMediaKind(val path: String) {
    MOVIE("movie"),
    TV("tv"),
}

/**
 * TMDB 资源引用。编码形如 `movie/550` / `tv/1396`。
 *
 * 裸数字 ID 不被接受：TMDB 的电影与剧集 ID 空间重叠，缺类型会静默刮错条目。
 */
data class TmdbRef(val kind: TmdbMediaKind, val id: Int) {
    val encoded: String get() = "${kind.path}/$id"

    companion object {
        fun parse(raw: String): TmdbRef {
            val parts = raw.split('/', limit = 2)
            require(parts.size == 2) { "TMDB ID 必须形如 movie/550 或 tv/1396，实际为 $raw" }
            val kind = TmdbMediaKind.entries.firstOrNull { it.path == parts[0] }
                ?: throw IllegalArgumentException("未知的 TMDB 媒体类型：${parts[0]}")
            val id = parts[1].toIntOrNull()
                ?: throw IllegalArgumentException("TMDB 数值 ID 无效：${parts[1]}")
            return TmdbRef(kind, id)
        }
    }
}

@Serializable
internal data class TmdbSearchResponse(
    val page: Int = 1,
    val results: List<TmdbSearchItem> = emptyList(),
    @SerialName("total_results") val totalResults: Int = 0,
)

@Serializable
internal data class TmdbSearchItem(
    val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
)

@Serializable
internal data class TmdbDetail(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val credits: TmdbCredits? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    val seasons: List<TmdbSeason> = emptyList(),
)

@Serializable
internal data class TmdbGenre(val id: Int = 0, val name: String = "")

@Serializable
internal data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Long? = null,
)

@Serializable
internal data class TmdbCredits(
    val cast: List<TmdbCastMember> = emptyList(),
    val crew: List<TmdbCrewMember> = emptyList(),
)

@Serializable
internal data class TmdbCastMember(
    val id: Int = 0,
    val name: String = "",
    val character: String? = null,
    val order: Int? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
internal data class TmdbCrewMember(
    val id: Int = 0,
    val name: String = "",
    val job: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
internal data class TmdbSeason(
    val id: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
internal data class TmdbSeasonDetail(
    val id: Int = 0,
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val episodes: List<TmdbEpisode> = emptyList(),
)

@Serializable
internal data class TmdbEpisode(
    val id: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    val name: String = "",
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
)
