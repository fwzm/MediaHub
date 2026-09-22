package com.mediahub.player.engine

import com.mediahub.model.SubtitleFormats

/**
 * 外挂字幕加载请求（引擎无关描述，P2 字幕中心切片一）。
 *
 * - [uri]：http(s)://（WebDAV 等）、file://（本地）、content://（SAF 导入）；
 * - [mimeType]：必须来自 [SubtitleFormats.mimeTypeOf]（Media3 SubtitleConfiguration 依赖）；
 * - [id]：稳定标识，供匹配记忆回放。
 */
data class ExternalSubtitle(
    val id: String,
    val name: String,
    val uri: String,
    val mimeType: String,
    val language: String? = null,
)

/**
 * 外挂字幕能力矩阵（引擎**如实自述**，UI 据此启用/禁用控件，不得伪造）：
 * - [externalLoad]：支持播放中加载外挂字幕（Media3=媒体项重建 / mpv=sub-add）；
 * - [offsetAdjust]：支持字幕时间轴偏移（**仅 mpv `sub-delay`**；Media3 无公开偏移 API）。
 */
data class SubtitleCapabilities(
    val externalLoad: Boolean,
    val offsetAdjust: Boolean,
)
