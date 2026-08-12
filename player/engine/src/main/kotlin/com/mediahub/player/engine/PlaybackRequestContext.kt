package com.mediahub.player.engine

import com.mediahub.model.PlaybackSource

/**
 * 单个 MediaSource 的不可变 HTTP 上下文。每次播放解析都创建新实例，
 * 不同播放器、预加载任务或来源之间没有共享可变请求头。
 */
class PlaybackRequestContext private constructor(headers: Map<String, String>) {
    val headers: Map<String, String> = headers.toMap()

    companion object {
        val Empty = PlaybackRequestContext(emptyMap())

        fun from(source: PlaybackSource): PlaybackRequestContext {
            val headers = source.headers.toMutableMap()
            if (source.cookies.isNotEmpty()) {
                headers["Cookie"] = source.cookies.entries.joinToString("; ") { (name, value) ->
                    "$name=$value"
                }
            }
            return PlaybackRequestContext(headers)
        }

        fun of(headers: Map<String, String>): PlaybackRequestContext =
            PlaybackRequestContext(headers)
    }
}
