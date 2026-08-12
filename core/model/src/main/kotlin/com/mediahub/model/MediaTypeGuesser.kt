package com.mediahub.model

/**
 * 根据文件引用（路径 / URI / 文件名）推断媒体类型。
 * browse-only 数据源（如本地文件树）播放时没有媒体库详情，
 * 用它重建 MediaItem，避免类型退化为 OTHER 污染"继续观看"元数据（review #5）。
 */
object MediaTypeGuesser {

    fun forPath(ref: String): MediaType {
        val fileName = ref.substringAfterLast('/').substringAfterLast(':')
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext in VIDEO_EXTENSIONS -> MediaType.VIDEO
            ext in AUDIO_EXTENSIONS -> MediaType.AUDIO
            else -> MediaType.OTHER
        }
    }

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "ts", "m2ts", "avi", "mov", "wmv", "flv", "3gp",
    )
    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma")
}
