package com.mediahub.core.network

/**
 * 网络层起播时间回调（U4-B）。由 player/engine 实现并转发到 [PlaybackStartupTrace]。
 * HttpClientFactory 的 OkHttp EventListener 按请求路径匹配后调用。
 */
interface PlaybackNetworkTraceSink {
    fun onPlaybackInfoStart()
    fun onPlaybackInfoEnd()
    fun onMediaRequestStart()
    fun onMediaFirstByte()
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
