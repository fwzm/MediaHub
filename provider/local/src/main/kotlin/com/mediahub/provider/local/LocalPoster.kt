package com.mediahub.provider.local

import com.mediahub.model.MediaType

/**
 * 本地条目的封面来源（配色/布局无关的纯决策，便于 JVM 单测）。
 *
 * 只有真实可解码的视频才带封面：Coil 的 `VideoFrameDecoder` 会对同一个
 * 本地 file/content URI 抽首帧，因此这里**只回传 URI，不做任何解码或落盘**。
 *
 * 目录、音频与未知类型必须返回 null —— 它们没有可抽帧的画面，伪造一个
 * 封面 URI 只会让 Coil 对音频/目录反复失败取帧（#92）。
 */
internal object LocalPoster {
    fun forItem(type: MediaType, uri: String): String? =
        uri.takeIf { type == MediaType.VIDEO && it.isNotBlank() }
}
