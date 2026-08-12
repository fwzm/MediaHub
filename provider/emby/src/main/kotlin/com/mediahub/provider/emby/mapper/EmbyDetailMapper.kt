package com.mediahub.provider.emby.mapper

import com.mediahub.model.AudioTrack
import com.mediahub.model.Chapter
import com.mediahub.model.HdrType
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaStream
import com.mediahub.model.MediaVersion
import com.mediahub.model.SubtitleTrack
import com.mediahub.provider.emby.api.EmbyMediaSourceInfoDto
import com.mediahub.provider.emby.api.EmbyMediaStreamDto
import com.mediahub.provider.emby.api.EmbyUserItemDto

/**
 * Emby UserItem → 领域 MediaDetail（Phase 1B-2）。
 * 协议差异（MediaSource/MediaStream/Chapter 命名与 HDR 表示）止步于此，
 * 领域层禁止直接使用 Emby DTO。
 */
object EmbyDetailMapper {
    private const val TICKS_PER_MILLIS = 10_000L

    /** 条目映射失败（如 Id 缺失）返回 null，由调用方报错。 */
    fun mapDetail(dto: EmbyUserItemDto, serverId: String): MediaDetail? {
        val item = EmbyMediaItemMapper.map(dto, serverId) ?: return null
        return MediaDetail(
            item = item,
            versions = dto.mediaSources.map { mapVersion(it) },
            streams = dto.mediaStreams.map { mapStream(it) },
            audioTracks = dto.mediaStreams
                .filter { it.type?.lowercase() == "audio" }
                .map { mapAudioTrack(it) },
            subtitles = dto.mediaStreams
                .filter { it.type?.lowercase() == "subtitle" }
                .map { mapSubtitleTrack(it) },
            chapters = dto.chapters.mapIndexed { index, chapter ->
                Chapter(
                    id = chapter.name?.takeIf(String::isNotBlank) ?: "chapter-$index",
                    name = chapter.name.orEmpty(),
                    startMs = (chapter.startPositionTicks ?: 0L) / TICKS_PER_MILLIS,
                )
            },
        )
    }

    /** Emby VideoRange → 统一 HdrType（详情与播放共用，如 HDR10/DOVI/HLG）。 */
    fun mapHdrType(videoRange: String?): HdrType = when (videoRange?.lowercase()) {
        "dovi" -> HdrType.DOLBY_VISION
        "hdr10+" -> HdrType.HDR10_PLUS
        "hdr10" -> HdrType.HDR10
        "hlg" -> HdrType.HLG
        else -> HdrType.NONE
    }

    private fun mapVersion(source: EmbyMediaSourceInfoDto): MediaVersion {
        val video = source.mediaStreams.firstOrNull { it.type?.lowercase() == "video" }
        return MediaVersion(
            id = source.id.orEmpty(),
            name = source.name ?: source.id.orEmpty(),
            container = source.container,
            width = video?.width,
            height = video?.height,
            hdrType = mapHdrType(video?.videoRange),
            bitrate = source.bitrate,
            sizeBytes = source.size,
        )
    }

    private fun mapStream(stream: EmbyMediaStreamDto): MediaStream = MediaStream(
        index = stream.index ?: 0,
        type = when (stream.type?.lowercase()) {
            "video" -> MediaStream.StreamType.VIDEO
            "audio" -> MediaStream.StreamType.AUDIO
            "subtitle" -> MediaStream.StreamType.SUBTITLE
            else -> MediaStream.StreamType.OTHER
        },
        codec = stream.codec,
        width = stream.width,
        height = stream.height,
        bitrate = stream.bitRate,
        language = stream.language,
        title = stream.title,
        channels = stream.channels,
        sampleRate = stream.sampleRate,
        hdrType = mapHdrType(stream.videoRange),
        isDefault = stream.isDefault,
        isForced = stream.isForced,
        profile = stream.profile,
        level = stream.level,
    )

    private fun mapAudioTrack(stream: EmbyMediaStreamDto): AudioTrack = AudioTrack(
        index = stream.index ?: 0,
        language = stream.language,
        title = stream.title,
        codec = stream.codec,
        channels = stream.channels,
        sampleRate = stream.sampleRate,
        isDefault = stream.isDefault,
    )

    private fun mapSubtitleTrack(stream: EmbyMediaStreamDto): SubtitleTrack = SubtitleTrack(
        index = stream.index ?: 0,
        language = stream.language,
        title = stream.title,
        format = stream.codec,
        isDefault = stream.isDefault,
        isForced = stream.isForced,
    )
}
