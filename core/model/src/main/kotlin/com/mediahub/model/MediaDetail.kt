package com.mediahub.model

/** 详情页聚合数据。不同数据源提供不同程度的信息，未提供的字段保持 null / 空列表。 */
data class MediaDetail(
    val item: MediaItem,
    val seasons: List<Season> = emptyList(),
    val versions: List<MediaVersion> = emptyList(),
    val streams: List<MediaStream> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val similarItems: List<MediaItem> = emptyList(),
)

/** 同一媒体的多个版本（如 4K Dolby Vision / 1080P），聚合媒体库时用于版本选择。 */
data class MediaVersion(
    val id: String,
    val name: String,
    val qualityLabel: String? = null,
    val container: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val hdrType: HdrType = HdrType.NONE,
    val bitrate: Long? = null,
    val sizeBytes: Long? = null,
)

/** 章节（用于章节跳转）。 */
data class Chapter(
    val id: String,
    val name: String,
    val startMs: Long,
)
