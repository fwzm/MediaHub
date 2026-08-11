package com.mediahub.model

/** 电视剧季。 */
data class Season(
    val serverId: String,
    val id: String,
    val seriesId: String,
    val name: String,
    val seasonNumber: Int,
    val episodeCount: Int? = null,
    val overview: String? = null,
    val imageUrl: String? = null,
)
