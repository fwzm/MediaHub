package com.mediahub.core.network

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response

/**
 * OkHttp EventListener（U4-B）：按 URL 路径匹配起播关键请求并回调时间。
 *
 * - POST /PlaybackInfo → PLAYBACK_INFO start/end
 * - GET /stream.* 或 /Videos/ → MEDIA request/firstByte
 * 不记录 URL / header / token。
 */
class StartupNetworkEventListener : EventListener() {

    override fun callStart(call: Call) {
        val sink = PlaybackNetworkTraceRegistry.get() ?: return
        val url = call.request().url
        when {
            isPlaybackInfo(url.encodedPath, call.request().method) -> sink.onPlaybackInfoStart()
            isMediaRequest(url.encodedPath, call.request().method) -> sink.onMediaRequestStart()
        }
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val sink = PlaybackNetworkTraceRegistry.get() ?: return
        val url = call.request().url
        if (isPlaybackInfo(url.encodedPath, call.request().method)) {
            sink.onPlaybackInfoEnd()
        }
    }

    override fun responseBodyStart(call: Call) {
        val sink = PlaybackNetworkTraceRegistry.get() ?: return
        val url = call.request().url
        if (isMediaRequest(url.encodedPath, call.request().method)) {
            sink.onMediaFirstByte()
        }
    }

    private fun isPlaybackInfo(path: String, method: String): Boolean =
        method == "POST" && path.contains("/PlaybackInfo")

    private fun isMediaRequest(path: String, method: String): Boolean =
        method == "GET" && (path.contains("/stream.") || path.contains("/Videos/"))

    /** OkHttp 要求每个 Call 一个新实例；工厂模式。 */
    companion object {
        val FACTORY = EventListener.Factory { StartupNetworkEventListener() }
    }
}