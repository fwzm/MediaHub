package com.mediahub.provider.api

import com.mediahub.model.MediaItem
import com.mediahub.model.SubtitleFormats

/**
 * 同目录可访问的外挂字幕候选（P2 字幕中心切片一）。
 *
 * - [id]：稳定标识（http(s) 绝对 URL 或本地绝对路径），直接作为匹配记忆的值；
 * - [uri]：加载用地址（同 [id] 语义，但允许 content:// 等 SAF 形式）；
 * - [language]：文件名尾缀猜测，null = 未知（不猜测）。
 */
data class DiscoveredSubtitle(
    val id: String,
    val name: String,
    val fileName: String,
    val extension: String,
    val language: String?,
    val uri: String,
) {
    val mimeType: String? get() = SubtitleFormats.mimeTypeOf(extension)
}

/**
 * 同目录字幕发现能力（ADR-014 能力隔离；P2 字幕中心切片一）。
 *
 * 语义边界（不得夸大）：
 * - 只发现**与视频同目录、当前凭据可访问**的字幕文件（SRT/ASS/SSA/VTT）；
 * - **不做**在线字幕站查询，不上传任何内容到任何服务器（用户约束红线）；
 * - 返回顺序即展示顺序（实现方按名称排序），空列表表示同目录无可访问字幕。
 */
interface MediaSubtitleDiscoveryProvider {
    suspend fun discoverSubtitles(video: MediaItem): List<DiscoveredSubtitle>
}
