package com.mediahub.provider.emby.playback

import com.mediahub.provider.emby.api.EmbyMediaSourceInfoDto

/**
 * MediaSource 选择（纯函数，Phase 1B-2 无转码版）。
 *
 * 规则（任务书红线）：只接受 SupportsDirectStream==true 的源；多个可用时按服务端
 * 返回顺序取第一个（Emby 默认源在前，顺序即服务端优先级）。
 * 没有任何可直接流的源时返回 null，由调用方抛 NotYetImplemented("需要转码")——
 * 本阶段禁止回退转码。
 */
object MediaSourceSelector {
    fun selectDirectStream(sources: List<EmbyMediaSourceInfoDto>): EmbyMediaSourceInfoDto? =
        sources.firstOrNull { it.supportsDirectStream }
}
