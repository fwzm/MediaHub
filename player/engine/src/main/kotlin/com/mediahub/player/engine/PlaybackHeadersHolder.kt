package com.mediahub.player.engine

/**
 * 播放请求头持有者：DataSource 创建后无法更换，但每次播放的鉴权头不同。
 * 由 HeaderAwareDataSource 在 open() 时读取最新值（见 HeaderAwareDataSource.kt）。
 * 仅内存持有，绝不落库、绝不进日志。
 */
class PlaybackHeadersHolder {
    @Volatile
    var headers: Map<String, String> = emptyMap()
        private set

    fun setHeaders(headers: Map<String, String>) {
        this.headers = headers
    }
}
