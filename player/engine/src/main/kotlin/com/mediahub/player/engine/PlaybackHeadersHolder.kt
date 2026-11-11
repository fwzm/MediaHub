package com.mediahub.player.engine

/**
 * 播放请求头持有者：DataSource 创建后无法更换，但每次播放的鉴权头不同。
 * MediaSource 创建时捕获当前快照，后续会话更新不能改写旧媒体源的请求。
 * 仅内存持有，绝不落库、绝不进日志。
 */
class PlaybackHeadersHolder {
    @Volatile
    var headers: Map<String, String> = emptyMap()
        private set

    fun setHeaders(headers: Map<String, String>) {
        this.headers = java.util.Collections.unmodifiableMap(LinkedHashMap(headers))
    }
}
