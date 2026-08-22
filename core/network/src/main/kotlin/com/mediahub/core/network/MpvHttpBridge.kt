package com.mediahub.core.network

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * mpv 安全媒体桥（U2 / ADR-030）。
 *
 * libmpv 不直接访问 Emby 直链 URL，而是访问本桥暴露的 localhost 地址：
 * mpv -> http://127.0.0.1:<port>/media -> MpvHttpBridge -> OkHttp + Emby 凭据 -> Emby ->307-> CDN/S3
 * 这样 mpv 从始至终只访问 localhost、永远不知道 Emby Token；凭据仍由已验证的
 * OkHttp 栈管理，跨 origin redirect 剥离复用 OriginScopedCredentialInterceptor（ADR-030）。
 *
 * 最小流式 HTTP 代理：GET/HEAD + Range 透传 + 206 响应体流式转发（不落盘），
 * seek 后 mpv 断开旧连接发起新 Range 请求（socket 关闭即取消旧转发）。
 */
class MpvHttpBridge(
    httpClientFactory: HttpClientFactory,
) {
    private val client: OkHttpClient = httpClientFactory.mediaClient().newBuilder()
        .addNetworkInterceptor(OriginScopedCredentialInterceptor())
        .build()

    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null

    /** 启动桥并返回 localhost 媒体 URL。upstreamUrl=Emby Direct Stream URL，upstreamHeaders=凭据/媒体头。 */
    fun start(upstreamUrl: String, upstreamHeaders: Map<String, String>): String {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        serverSocket = socket
        running.set(true)
        Thread({ acceptLoop(socket, upstreamUrl, upstreamHeaders) }, "mpv-http-bridge").start()
        return "http://127.0.0.1:" + socket.localPort + "/media"
    }

    fun stop() {
        running.set(false)
        serverSocket?.close()
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket, url: String, headers: Map<String, String>) {
        while (running.get()) {
            try {
                val conn = socket.accept()
                Thread({ handleConnection(conn, url, headers) }, "mpv-http-conn").start()
            } catch (e: Exception) {
                if (!running.get()) return
            }
        }
    }

    private fun handleConnection(socket: Socket, upstreamUrl: String, upstreamHeaders: Map<String, String>) {
        try {
            socket.use { conn ->
                val input = conn.getInputStream()
                val output = conn.getOutputStream()
                val reader = input.bufferedReader()
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val headers = HashMap<String, String>()
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                    line = reader.readLine()
                }
                val builder = Request.Builder().url(upstreamUrl).method(method, null)
                upstreamHeaders.forEach { (k, v) -> builder.header(k, v) }
                headers["Range"]?.let { builder.header("Range", it) }
                headers["If-Range"]?.let { builder.header("If-Range", it) }
                headers["If-None-Match"]?.let { builder.header("If-None-Match", it) }

                try {
                    client.newCall(builder.build()).execute().use { resp ->
                        val body = resp.body
                        output.write(("HTTP/1.1 " + resp.code + " " + resp.message + "\r\n").toByteArray())
                        resp.headers.forEach { (name, value) ->
                            output.write((name + ": " + value + "\r\n").toByteArray())
                        }
                        output.write("\r\n".toByteArray())
                        if (method != "HEAD" && body != null) {
                            val buf = ByteArray(32 * 1024)
                            body.byteStream().use { bodyIn ->
                                var n = bodyIn.read(buf)
                                while (n >= 0) {
                                    output.write(buf, 0, n)
                                    n = bodyIn.read(buf)
                                }
                            }
                        }
                        output.flush()
                    }
                } catch (e: Exception) {
                    runCatching {
                        output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        output.flush()
                    }
                }
            }
        } catch (e: Exception) {
            // 连接已断开（mpv seek/取消），忽略
        }
    }
}