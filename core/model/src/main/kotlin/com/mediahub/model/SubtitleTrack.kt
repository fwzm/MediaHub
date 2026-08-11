package com.mediahub.model

/**
 * 字幕轨。可以是内嵌（index 指向文件内流）或外挂（url 指向字幕文件）。
 */
data class SubtitleTrack(
    val index: Int,
    val language: String? = null,
    val title: String? = null,
    val format: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isExternal: Boolean = false,
    val url: String? = null,
    val mimeType: String? = null,
)

/** 字幕流内容（外挂字幕加载结果）。 */
data class SubtitleStream(
    val track: SubtitleTrack,
    val content: String,
)
