package com.mediahub.model

/** NAS / 云盘 / 本地场景下的文件条目。 */
data class MediaFile(
    val serverId: String,
    val id: String,
    val name: String,
    val path: String,
    val parentPath: String? = null,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val container: String? = null,
    val modifiedAtEpochMs: Long? = null,
)

/** 文件（或流）中的媒体流描述。 */
data class MediaStream(
    val index: Int,
    val type: StreamType,
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val language: String? = null,
    val title: String? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val hdrType: HdrType = HdrType.NONE,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val profile: String? = null,
    val level: String? = null,
) {
    enum class StreamType { VIDEO, AUDIO, SUBTITLE, OTHER }
}
