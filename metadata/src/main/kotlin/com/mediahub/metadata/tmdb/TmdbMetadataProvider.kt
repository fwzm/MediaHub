package com.mediahub.metadata.tmdb

import com.mediahub.metadata.MetadataDetail
import com.mediahub.metadata.MetadataEpisode
import com.mediahub.metadata.MetadataException
import com.mediahub.metadata.MetadataProvider
import com.mediahub.metadata.MetadataResult
import com.mediahub.metadata.MetadataSeason
import com.mediahub.metadata.MetadataSource
import com.mediahub.metadata.SeasonEpisodeProvider
import com.mediahub.model.Person

/**
 * TMDB 刮削 Provider。
 *
 * ID 约定见 [TmdbRef]（`movie/550` / `tv/1396`）。电影与剧集详情共用同一映射，
 * 季集仅对剧集有效。
 */
class TmdbMetadataProvider internal constructor(
    private val api: TmdbApi,
) : MetadataProvider, SeasonEpisodeProvider {

    override val source: MetadataSource = MetadataSource.TMDB

    override suspend fun search(query: String, year: Int?): List<MetadataResult> {
        if (query.isBlank()) return emptyList()
        return api.search(query).results
            .mapNotNull { it.toResult() }
            .filter { year == null || it.year == null || it.year == year }
    }

    override suspend fun detail(metadataId: String): MetadataDetail? {
        val ref = TmdbRef.parse(metadataId)
        val dto = try {
            api.detail(ref.kind, ref.id)
        } catch (e: MetadataException) {
            if (e.statusCode == 404) return null
            throw e
        }
        return dto.toDetail(ref)
    }

    override suspend fun seasons(metadataId: String): List<MetadataSeason> {
        val ref = requireTv(metadataId)
        return api.detail(TmdbMediaKind.TV, ref.id).seasons
            .sortedBy { it.seasonNumber }
            .map { season ->
                MetadataSeason(
                    seasonNumber = season.seasonNumber,
                    title = season.name,
                    overview = season.overview,
                    episodeCount = season.episodeCount,
                    posterUrl = imageUrl(season.posterPath, POSTER_SIZE),
                    airDate = season.airDate,
                )
            }
    }

    override suspend fun episodes(metadataId: String, seasonNumber: Int): List<MetadataEpisode> {
        val ref = requireTv(metadataId)
        return api.season(ref.id, seasonNumber).episodes
            .sortedBy { it.episodeNumber }
            .map { episode ->
                MetadataEpisode(
                    seasonNumber = seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    title = episode.name,
                    overview = episode.overview,
                    stillUrl = imageUrl(episode.stillPath, STILL_SIZE),
                    airDate = episode.airDate,
                    runtimeMinutes = episode.runtime,
                )
            }
    }

    private fun requireTv(metadataId: String): TmdbRef {
        val ref = TmdbRef.parse(metadataId)
        require(ref.kind == TmdbMediaKind.TV) { "仅剧集支持季集查询：$metadataId" }
        return ref
    }

    companion object {
        /**
         * 生产装配入口：应用层只需提供 API Key（真实密钥不入库）。
         * [http] 可注入以便测试，默认走 [OkHttpMetadataHttpClient]。
         */
        fun create(
            apiKey: String,
            baseUrl: String = TmdbApi.DEFAULT_BASE_URL,
            language: String = "zh-CN",
            http: com.mediahub.metadata.MetadataHttpClient =
                com.mediahub.metadata.OkHttpMetadataHttpClient(),
        ): TmdbMetadataProvider = TmdbMetadataProvider(TmdbApi(http, apiKey, baseUrl, language))
    }
}

private fun TmdbSearchItem.toResult(): MetadataResult? {
    val kind = when (mediaType) {
        "tv" -> TmdbMediaKind.TV
        "movie" -> TmdbMediaKind.MOVIE
        else -> return null
    }
    val displayTitle = when (kind) {
        TmdbMediaKind.TV -> name ?: originalName
        TmdbMediaKind.MOVIE -> title ?: originalTitle
    } ?: return null
    val date = if (kind == TmdbMediaKind.TV) firstAirDate else releaseDate
    return MetadataResult(
        source = MetadataSource.TMDB,
        id = TmdbRef(kind, id).encoded,
        title = displayTitle,
        year = date.toYear(),
        posterUrl = imageUrl(posterPath, POSTER_SIZE),
        overview = overview,
    )
}

private fun TmdbDetail.toDetail(ref: TmdbRef): MetadataDetail {
    val displayTitle = when (ref.kind) {
        TmdbMediaKind.TV -> name ?: title
        TmdbMediaKind.MOVIE -> title ?: name
    } ?: ""
    val date = if (ref.kind == TmdbMediaKind.TV) firstAirDate else releaseDate
    return MetadataDetail(
        source = MetadataSource.TMDB,
        id = ref.encoded,
        title = displayTitle,
        overview = overview,
        year = date.toYear(),
        genres = genres.map { it.name },
        people = people(),
        posterUrl = imageUrl(posterPath, POSTER_SIZE),
        backdropUrl = imageUrl(backdropPath, BACKDROP_SIZE),
        rating = voteAverage,
    )
}

private fun TmdbDetail.people(): List<Person> {
    val credits = credits ?: return emptyList()
    val cast = credits.cast
        .sortedBy { it.order ?: Int.MAX_VALUE }
        .take(MAX_CAST)
        .map { member ->
            Person(
                name = member.name,
                role = Person.Role.ACTOR,
                id = member.id.toString(),
                type = "Actor",
                characterName = member.character,
                imageUrl = imageUrl(member.profilePath, PROFILE_SIZE),
            )
        }
    val crew = credits.crew.mapNotNull { member ->
        val role = when (member.job) {
            "Director" -> Person.Role.DIRECTOR
            "Writer", "Screenplay", "Story" -> Person.Role.WRITER
            "Producer", "Executive Producer" -> Person.Role.PRODUCER
            else -> return@mapNotNull null
        }
        Person(
            name = member.name,
            role = role,
            id = member.id.toString(),
            type = member.job,
            imageUrl = imageUrl(member.profilePath, PROFILE_SIZE),
        )
    }
    return cast + crew
}

private fun String?.toYear(): Int? = this?.take(4)?.toIntOrNull()

private fun imageUrl(path: String?, size: String): String? {
    if (path.isNullOrBlank()) return null
    return "${TmdbApi.IMAGE_BASE_URL}/$size/${path.trimStart('/')}"
}

private const val POSTER_SIZE = "w500"
private const val BACKDROP_SIZE = "w780"
private const val STILL_SIZE = "w300"
private const val PROFILE_SIZE = "w185"
private const val MAX_CAST = 20
