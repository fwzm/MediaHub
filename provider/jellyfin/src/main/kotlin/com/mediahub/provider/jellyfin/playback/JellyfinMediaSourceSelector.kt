package com.mediahub.provider.jellyfin.playback

import com.mediahub.provider.jellyfin.api.JellyfinMediaSourceInfoDto

/**
 * MediaSource 选择（纯函数，Phase 1G-C 无转码版；语义镜像 Emby MediaSourceSelector）：
 * 只接受 SupportsDirectStream==true 的源；多个可用时按服务端返回顺序取第一个
 * （Jellyfin 默认源在前，顺序即服务端优先级）。
 * 没有任何可直接流的源时返回 null，由调用方抛 NotYetImplemented("需要转码")——禁止回退转码。
 * .iso 蓝光镜像不是可直接流式播放的媒体，跳过。
 */
object JellyfinMediaSourceSelector {
    /**
     * @param versionId 指定 MediaSourceId（多版本选择）。null = 服务端默认源；
     * 非 null 时只接受精确命中且可直连、非 ISO 的源，**绝不回退**其它源。
     */
    fun selectDirectStream(
        sources: List<JellyfinMediaSourceInfoDto>,
        versionId: String? = null,
    ): JellyfinMediaSourceInfoDto? {
        if (versionId != null) {
            val selected = findById(sources, versionId) ?: return null
            return selected.takeIf { it.supportsDirectStream && !it.isIso() }
        }
        return sources.firstOrNull { it.supportsDirectStream && !it.isIso() }
    }

    /** 按 MediaSourceId 精确查找；找不到返回 null，由调用方报 NotFound。 */
    fun findById(
        sources: List<JellyfinMediaSourceInfoDto>,
        versionId: String,
    ): JellyfinMediaSourceInfoDto? = sources.firstOrNull { it.id == versionId }

    /** One source may be re-negotiated without server subtitles; capabilities still gate playback. */
    internal fun selectStaticNegotiationCandidate(
        sources: List<JellyfinMediaSourceInfoDto>,
        versionId: String? = null,
    ): JellyfinMediaSourceInfoDto? {
        if (versionId != null) {
            return findById(sources, versionId)?.takeIf { !it.id.isNullOrBlank() && !it.isIso() }
        }
        return sources.firstOrNull { !it.id.isNullOrBlank() && !it.isIso() }
    }

    private fun JellyfinMediaSourceInfoDto.isIso(): Boolean =
        container?.trim()?.equals("iso", ignoreCase = true) == true || path.isIsoPath()

    private fun String?.isIsoPath(): Boolean =
        this?.substringBefore('?')?.substringBefore('#')?.trimEnd()?.endsWith(".iso", ignoreCase = true) == true
}
