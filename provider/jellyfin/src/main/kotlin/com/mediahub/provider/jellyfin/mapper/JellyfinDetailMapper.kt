package com.mediahub.provider.jellyfin.mapper

import com.mediahub.model.AudioTrack
import com.mediahub.model.Chapter
import com.mediahub.model.HdrType
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaStream
import com.mediahub.model.MediaVersion
import com.mediahub.model.SubtitleTrack
import com.mediahub.provider.jellyfin.api.JellyfinChapterInfoDto
import com.mediahub.provider.jellyfin.api.JellyfinItemDto
import com.mediahub.provider.jellyfin.api.JellyfinMediaSourceInfoDto
import com.mediahub.provider.jellyfin.api.JellyfinMediaStreamDto

/**
 * Jellyfin BaseItemDto → 领域 MediaDetail（Phase 1G-B parity）。
 *
 * 详情端点会带 MediaSources（含各版本 MediaStreams）、MediaStreams 与 Chapters；
 * 映射语义与 EmbyDetailMapper 对齐（同一领域模型），但**独立实现，禁止 import provider:emby**。
 * MediaStreams 缺失时回退到首个 MediaSource 的轨道列表（部分 Jellyfin 版本只在版本内回填）。
 */
object JellyfinDetailMapper {
    private const val TICKS_PER_MILLIS = 10_000L

    /** 条目映射失败（如 Id 缺失）返回 null，由调用方报错。 */
    fun mapDetail(dto: JellyfinItemDto, serverId: String): MediaDetail? {
        val item = JellyfinItemMapper.map(dto, serverId) ?: return null
        val streams = dto.mediaStreams.ifEmpty {
            dto.mediaSources.firstOrNull()?.mediaStreams.orEmpty()
        }
        return MediaDetail(
            item = item,
            versions = dto.mediaSources.map { mapVersion(it) },
            streams = streams.map { mapStream(it) },
            audioTracks = streams
                .filter { it.type?.lowercase() == "audio" }
                .map { mapAudioTrack(it) },
            subtitles = streams
                .filter { it.type?.lowercase() == "subtitle" }
                .map { mapSubtitleTrack(it) },
            chapters = dto.chapters.mapIndexed { index, chapter -> mapChapter(index, chapter) },
        )
    }

    /**
     * Jellyfin HDR 元数据 → 统一 HdrType。
     * 优先 VideoRange；ExtendedVideoType/ExtendedVideoSubType 明确表示 Dolby Vision
     * （如 "DolbyVision"/"DOVI"/"DoviProfile81"）时补充 DOLBY_VISION 映射。
     * 仅 metadata mapping，不扩展 Dolby Vision 播放策略。
     */
    fun mapHdrType(
        videoRange: String?,
        extendedVideoType: String? = null,
        extendedVideoSubType: String? = null,
    ): HdrType {
        val range = videoRange?.lowercase()
        if (
            range.isDolbyVisionSignal() ||
            extendedVideoType.isDolbyVisionSignal() ||
            extendedVideoSubType.isDolbyVisionSignal()
        ) {
            return HdrType.DOLBY_VISION
        }
        return when (range) {
            "hdr10+" -> HdrType.HDR10_PLUS
            "hdr10" -> HdrType.HDR10
            "hlg" -> HdrType.HLG
            else -> HdrType.NONE
        }
    }

    /**
     * Jellyfin 的 ExtendedVideoSubType 使用 DoviProfile81 等枚举值，未必包含 "Dolby"。
     * 按 HTTP/JSON 协议值判断，避免只覆盖 ExtendedVideoType=DolbyVision 的半套映射。
     */
    private fun String?.isDolbyVisionSignal(): Boolean {
        val normalized = this?.trim()?.lowercase().orEmpty()
        return normalized.contains("dolby") || normalized.startsWith("dovi")
    }

    private fun mapVersion(source: JellyfinMediaSourceInfoDto): MediaVersion {
        val video = source.mediaStreams.firstOrNull { it.type?.lowercase() == "video" }
        return MediaVersion(
            id = source.id.orEmpty(),
            name = source.name ?: source.id.orEmpty(),
            container = source.container,
            width = video?.width,
            height = video?.height,
            hdrType = mapHdrType(video?.videoRange, video?.extendedVideoType, video?.extendedVideoSubType),
            bitrate = source.bitrate,
            sizeBytes = source.size,
        )
    }

    private fun mapStream(stream: JellyfinMediaStreamDto): MediaStream = MediaStream(
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
        hdrType = mapHdrType(stream.videoRange, stream.extendedVideoType, stream.extendedVideoSubType),
        isDefault = stream.isDefault,
        isForced = stream.isForced,
        profile = stream.profile,
        level = stream.level?.toString(),
    )

    private fun mapAudioTrack(stream: JellyfinMediaStreamDto): AudioTrack = AudioTrack(
        index = stream.index ?: 0,
        language = stream.language,
        title = stream.title,
        codec = stream.codec,
        channels = stream.channels,
        sampleRate = stream.sampleRate,
        isDefault = stream.isDefault,
    )

    private fun mapSubtitleTrack(stream: JellyfinMediaStreamDto): SubtitleTrack = SubtitleTrack(
        index = stream.index ?: 0,
        language = stream.language,
        title = stream.title,
        format = stream.codec,
        isDefault = stream.isDefault,
        isForced = stream.isForced,
    )

    private fun mapChapter(index: Int, chapter: JellyfinChapterInfoDto): Chapter = Chapter(
        id = chapter.name?.takeIf(String::isNotBlank) ?: "chapter-$index",
        name = chapter.name.orEmpty(),
        startMs = (chapter.startPositionTicks ?: 0L) / TICKS_PER_MILLIS,
    )
}
