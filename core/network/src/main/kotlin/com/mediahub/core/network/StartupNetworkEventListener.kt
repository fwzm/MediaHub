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
 *
 * 重定向（U4-B 补充）：OkHttp 对每一个 hop 都会回调 responseHeadersEnd /
 * responseBodyStart，而回调时 [Response.priorResponse] 恒为 null（实测），
 * 所以 hop 数只能自己累计。中间 hop 只计数，**不上报**媒体元数据与首字节——
 * 否则 302 的响应体会被当成媒体首字节，TTFF 被提前盖戳，元数据也被 302 的
 * code / Content-Length 覆盖。
 *
 * 每个 Call 一个实例（[FACTORY]），因此下面的计数器天然是 per-call 的。
 */
class StartupNetworkEventListener : EventListener() {

    /** 已完成的中间 hop 数（3xx 且带 Location）。 */
    private var redirectCount = 0

    /** 已收到最终响应头、但还没收到第一个响应体字节。 */
    private var awaitingFinalBody = false

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
        val playbackInfo = isPlaybackInfo(url.encodedPath, call.request().method)
        val media = isMediaRequest(url.encodedPath, call.request().method)
        if (!playbackInfo && !media) return

        if (isRedirect(response)) {
            redirectCount++
            return
        }

        if (playbackInfo) {
            sink.onPlaybackInfoEnd()
        }
        if (media) {
            awaitingFinalBody = true
            sink.onMediaResponseMetadata(
                code = response.code,
                protocol = response.protocol.toString(),
                redirectCount = redirectCount,
                acceptsRanges = response.header("Accept-Ranges") == "bytes",
                contentLengthBytes = response.header("Content-Length")?.toLongOrNull() ?: -1L,
                contentRange = response.header("Content-Range"),
            )
        }
    }

    override fun responseBodyStart(call: Call) {
        if (!awaitingFinalBody) return
        awaitingFinalBody = false
        val sink = PlaybackNetworkTraceRegistry.get() ?: return
        sink.onMediaFirstByte()
    }

    private fun isRedirect(response: Response): Boolean =
        response.code in 300..399 && response.header("Location") != null

    private fun isPlaybackInfo(path: String, method: String): Boolean =
        method == "POST" && path.contains("/PlaybackInfo")

    private fun isMediaRequest(path: String, method: String): Boolean =
        method == "GET" && (path.contains("/stream.") || path.contains("/Videos/"))

    /** OkHttp 要求每个 Call 一个新实例；工厂模式。 */
    companion object {
        val FACTORY = EventListener.Factory { StartupNetworkEventListener() }
    }
}
