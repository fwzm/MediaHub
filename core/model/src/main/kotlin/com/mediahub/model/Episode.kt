package com.mediahub.model

/** 电视剧单集。 */
data class Episode(
    val serverId: String,
    val id: String,
    val seriesId: String,
    val seasonId: String,
    val name: String,
    val episodeNumber: Int,
    val overview: String? = null,
    val imageUrl: String? = null,
    val runtimeMs: Long? = null,
    val airDate: String? = null,
    val indexNumber: Int? = null,
)
