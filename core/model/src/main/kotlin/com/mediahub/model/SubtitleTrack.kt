package com.mediahub.model

/**
 * 字幕轨。[index] 为同类型 UI 行序号；播放期地址由引擎行映射提供。
 * 外挂字幕由 [url] 指向文件，不把组号/renderer 下标作为领域身份。
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
    /** 当前实际被选中。 */
    val isSelected: Boolean = false,
    /** 设备/选择器当前是否支持该轨。 */
    val isSupported: Boolean = true,
)

/** 字幕流内容（外挂字幕加载结果）。 */
data class SubtitleStream(
    val track: SubtitleTrack,
    val content: String,
)
