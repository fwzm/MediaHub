package com.mediahub.core.network

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Session-owned loopback media proxy; credentials remain on the origin-scoped OkHttp stack. */
class MpvHttpBridge internal constructor(
    private val client: OkHttpClient,
    private val headerTimeoutMs: Int = HEADER_TIMEOUT_MS,
) {
    init { require(headerTimeoutMs > 0) }

    constructor(httpClientFactory: HttpClientFactory) : this(httpClientFactory.mediaClient().newBuilder()
        .addNetworkInterceptor(OriginScopedCredentialInterceptor()).build())
    private val workers = ThreadPoolExecutor(0, MAX_CONNECTIONS, 60, TimeUnit.SECONDS,
        SynchronousQueue(), { work -> Thread(work, "mpv-http-conn").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private class Session(val listener: ServerSocket, val path: String,
        val url: String, val headers: Map<String, String>) {
        var running = true // guarded by this Session's monitor
        val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
        val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    }
    private var current: Session? = null

    @Synchronized
    fun start(upstreamUrl: String, upstreamHeaders: Map<String, String>): String {
        stop()
        val listener = ServerSocket(0, MAX_CONNECTIONS, InetAddress.getByName("127.0.0.1"))
        val random = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val path = "/media/" + Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        val session = Session(listener, path, upstreamUrl, upstreamHeaders.toMap())
        current = session
        Thread({ acceptLoop(session) }, "mpv-http-bridge").apply { isDaemon = true }.start()
        return "http://127.0.0.1:${listener.localPort}$path"
    }

    @Synchronized
    fun stop() {
        val session = current ?: return
        current = null
        synchronized(session) { session.running = false }
        runCatching { session.listener.close() }
        // All registrations check running under the same monitor; after invalidation no
        // accepted socket or newly created Call can escape this cleanup snapshot.
        session.calls.forEach { it.cancel() }
        session.sockets.forEach { runCatching { it.close() } }
    }

    private fun acceptLoop(session: Session) {
        while (true) {
            val conn = try { session.listener.accept() } catch (_: Exception) { return }
            val admitted = synchronized(session) {
                if (!session.running || session.sockets.size >= MAX_CONNECTIONS) false
                else { session.sockets += conn; true }
            }
            if (!admitted) { runCatching { conn.close() }; continue }
            try {
                workers.execute { handleConnection(session, conn) }
            } catch (_: Exception) {
                session.sockets -= conn
                runCatching { conn.close() }
            }
        }
    }

    private fun handleConnection(session: Session, socket: Socket) {
        var call: Call? = null
        var responseStarted = false
        var upstreamFinished = false
        try {
            val conn = socket
            run {
                val headerDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(headerTimeoutMs.toLong())
                val input = conn.getInputStream()
                val output = conn.getOutputStream()
                var remaining = MAX_HEADER_BYTES
                fun line(): String? {
                    val value = readLine(input, conn, headerDeadline, minOf(MAX_LINE_BYTES, remaining)) ?: return null
                    remaining -= value.length + 2
                    check(remaining > 0) { "header limit" }
                    return value
                }
                fun reject(code: Int) {
                    output.write("HTTP/1.1 $code Rejected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                }
                val parts = line()?.split(' ') ?: return
                if (parts.size != 3 || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) { reject(400); return }
                val method = parts[0]
                if (method != "GET" && method != "HEAD") { reject(405); return }
                if (parts[1] != session.path) { reject(404); return }
                val forwarded = HashMap<String, String>()
                var count = 0
                while (true) {
                    val header = line() ?: return
                    if (header.isEmpty()) break
                    if (++count > MAX_HEADERS) { reject(431); return }
                    val colon = header.indexOf(':')
                    if (colon <= 0) { reject(400); return }
                    val name = header.substring(0, colon).lowercase()
                    if (name in FORWARDED_HEADERS) forwarded[name] = header.substring(colon + 1).trim()
                }
                val builder = Request.Builder().url(session.url).method(method, null)
                session.headers.forEach { (name, value) -> builder.header(name, value) }
                forwarded.forEach { (name, value) -> builder.header(name, value) }
                val target = client.newCall(builder.build())
                call = target
                val registered = synchronized(session) {
                    if (!session.running) false else { session.calls += target; true }
                }
                if (!registered) { target.cancel(); return }
                target.execute().use { response ->
                    responseStarted = true
                    output.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray())
                    val connectionHeaders = response.headers.values("Connection")
                        .flatMap { it.split(',') }.map { it.trim().lowercase() }.toSet()
                    response.headers.forEach { (name, value) ->
                        // OkHttp exposes a decoded body; hop framing must not be forwarded.
                        val normalized = name.lowercase()
                        if (normalized !in HOP_HEADERS && normalized !in connectionHeaders) {
                            output.write("$name: $value\r\n".toByteArray())
                        }
                    }
                    output.write("Connection: close\r\n\r\n".toByteArray())
                    if (method != "HEAD") response.body?.byteStream()?.use { it.copyTo(output, 32 * 1024) }
                    output.flush()
                }
                upstreamFinished = true
            }
        } catch (_: Exception) {
            if (!responseStarted && call != null && !socket.isClosed) runCatching {
                socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
            }
        } finally {
            call?.let { session.calls -= it; if (!upstreamFinished) it.cancel() }
            session.sockets -= socket
            runCatching { socket.close() }
        }
    }

    /** Reads at most limit bytes and rejects unterminated or overlong lines before allocation grows. */
    private fun readLine(input: InputStream, socket: Socket, deadline: Long, limit: Int): String? {
        val text = StringBuilder()
        for (index in 0 until limit) {
            // One monotonic deadline spans the complete request header. A peer cannot
            // renew its admission by dripping bytes just before an idle timeout.
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) throw SocketTimeoutException("HTTP header deadline")
            socket.soTimeout = ((remainingNanos + 999_999) / 1_000_000)
                .coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val byte = input.read()
            if (byte == -1) return null
            if (byte == 10) return text.toString().removeSuffix("\r")
            text.append(byte.toChar())
        }
        throw IllegalArgumentException("HTTP line too long")
    }

    private companion object {
        const val MAX_CONNECTIONS = 16
        const val HEADER_TIMEOUT_MS = 10_000
        const val MAX_LINE_BYTES = 8_192
        const val MAX_HEADER_BYTES = 32_768
        const val MAX_HEADERS = 100
        val FORWARDED_HEADERS = setOf("range", "if-range", "if-none-match")
        val HOP_HEADERS = setOf("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade")
    }
}
