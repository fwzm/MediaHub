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
    fun selectDirectStream(sources: List<JellyfinMediaSourceInfoDto>): JellyfinMediaSourceInfoDto? =
        sources.firstOrNull { it.supportsDirectStream && !it.path.isIsoPath() }

    private fun String?.isIsoPath(): Boolean =
        this?.substringBefore('?')?.substringBefore('#')?.trimEnd()?.endsWith(".iso", ignoreCase = true) == true
}
