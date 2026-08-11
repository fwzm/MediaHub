package com.mediahub.model

/** 音轨（供播放器选择器使用，字段与 Media3 轨道信息解耦）。 */
data class AudioTrack(
    val index: Int,
    val language: String? = null,
    val title: String? = null,
    val codec: String? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val isDefault: Boolean = false,
)
