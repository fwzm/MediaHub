package com.mediahub.provider.jellyfin.mapper

import com.mediahub.model.HdrType
import com.mediahub.model.MediaVersion
import com.mediahub.provider.jellyfin.api.JellyfinMediaSourceInfoDto

/**
 * Jellyfin MediaSource → MediaVersion，语义镜像 EmbyDetailMapper.mapVersion：
 * 服务端返回顺序即优先级（首项为默认版本，不重排）；qualityLabel 不在此计算，
 * 与 Emby 一致由 UI 回退到 name。id 为空的上游脏数据直接丢弃。
 */
object JellyfinVersionMapper {
    fun map(sources: List<JellyfinMediaSourceInfoDto>): List<MediaVersion> =
        sources.filter { !it.id.isNullOrBlank() }.map(::mapVersion)

    private fun mapVersion(source: JellyfinMediaSourceInfoDto): MediaVersion {
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

    internal fun mapHdrType(videoRange: String?): HdrType {
        val range = videoRange?.lowercase() ?: return HdrType.NONE
        if (range.contains("dolby") || range.startsWith("dovi")) return HdrType.DOLBY_VISION
        return when (range) {
            "hdr10+" -> HdrType.HDR10_PLUS
            "hdr10" -> HdrType.HDR10
            "hlg" -> HdrType.HLG
            else -> HdrType.NONE
        }
    }
}
