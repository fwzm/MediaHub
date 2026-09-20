package com.mediahub.model

/**
 * 字幕轨。可以是内嵌（index 指向文件内流）或外挂（url 指向字幕文件）。
 *
 * [index] 是**列表行序号**（0..N-1），只表示 UI 列表位置，**不代表引擎的组号**；
 * 运行时选择地址（组号 + 组内轨号 + 快照令牌）保存在 player:engine，不进入本模块。
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
