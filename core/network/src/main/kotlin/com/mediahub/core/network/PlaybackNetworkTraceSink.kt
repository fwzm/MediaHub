package com.mediahub.core.network

/**
 * 网络层起播时间回调（U4-B→U4-C）。由 player/engine 实现并转发到 [PlaybackStartupTrace]。
 * HttpClientFactory 的 OkHttp EventListener 按请求路径匹配后调用。
 */
interface PlaybackNetworkTraceSink {
    fun onPlaybackInfoStart()
    fun onPlaybackInfoEnd()
    fun onMediaRequestStart()
    fun onMediaFirstByte()
    /**
     * 媒体响应元数据（U4-C.3）。
     * [contentLengthBytes] 为 -1 表示未知（chunked）。
     */
    fun onMediaResponseMetadata(
        code: Int,
        protocol: String,
        redirectCount: Int,
        acceptsRanges: Boolean,
        contentLengthBytes: Long,
        contentRange: String?,
    )
}

/**
 * 线程安全 sink 持有器。PlayerViewModel 在 resolve() 前设置、完成后清空。
 * 不是全局单例 trace——只是 HTTP 层到当前 trace 的桥接。
 */
object PlaybackNetworkTraceRegistry {
    @Volatile
    private var sink: PlaybackNetworkTraceSink? = null

    fun set(s: PlaybackNetworkTraceSink?) { sink = s }

    fun get(): PlaybackNetworkTraceSink? = sink
}