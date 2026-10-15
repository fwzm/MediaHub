package com.mediahub.core.network

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
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

/**
 * 打开本地媒体文件的**可注入**入口（M14-S4-A 测试缝）。
 *
 * 生产默认就是 `RandomAccessFile(file, "r")`；测试可注入一个**计数/受控**实现，
 * 从而在不使用 sleep、不依赖调度的情况下精确断言"每个打开的句柄恰好被关闭一次"、
 * "会话停止后句柄已关闭"等生命周期契约。
 */
internal fun interface FileOpener {
    fun open(file: File): RandomAccessFile
}

/** Session-owned loopback media proxy; credentials remain on the origin-scoped OkHttp stack. */
class MpvHttpBridge internal constructor(
    private val client: OkHttpClient,
    private val headerTimeoutMs: Int = HEADER_TIMEOUT_MS,
    private val openBody: FileOpener = FileOpener { RandomAccessFile(it, "r") },
    /**
     * 单调时钟（M14-S4-C 可测性缝）。
     *
     * 生产默认就是 [System.nanoTime]，**行为完全不变**；注入可控时钟后，
     * "聚合 header 截止时间"的测试可以精确断言契约（越过截止点即不再接收任何字节），
     * 而不再依赖墙钟预算（原先的 `< 800ms` 断言在慢 runner 上本身就是抖动来源）。
     */
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init { require(headerTimeoutMs > 0) }

    constructor(httpClientFactory: HttpClientFactory) : this(httpClientFactory.mediaClient().newBuilder()
        .addNetworkInterceptor(OriginScopedCredentialInterceptor()).build())
    private val workers = ThreadPoolExecutor(0, MAX_CONNECTIONS, 60, TimeUnit.SECONDS,
        SynchronousQueue(), { work -> Thread(work, "mpv-http-conn").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    /**
     * 上游来源模型（M9）：回环桥只服务**当前播放会话明确选择的一个**上游，
     * 要么是 HTTP(S) 远端（既有行为，凭据仍在 origin-scoped OkHttp 栈上），
     * 要么是本地授权媒体文件（本模块只读取该文件，不是通用文件服务器）。
     */
    internal sealed interface MpvUpstream {
        data class Http(val url: String, val headers: Map<String, String>) : MpvUpstream

        /** [path] 必须是绝对路径或 file:// URI；token 路径由本类随机生成，客户端无法指定文件。 */
        data class LocalFile(val path: String) : MpvUpstream
    }

    private class Session(val listener: ServerSocket, val path: String,
        val upstream: MpvUpstream) {
        var running = true // guarded by this Session's monitor
        val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
        val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

        /**
         * **会话级**本地文件句柄（M14-S4-B）。
         *
         * 为什么必须提升到会话级：
         * - 旧实现**每次请求**都 `resolveLocalFile` + `RandomAccessFile(file)` 打开，
         *   于是 `file.length()`（声明 Content-Length 的依据）与真正流式读取的句柄
         *   来自**两次不同的打开** —— 两者之间发生 truncate 就会给出"声明的长度大于实际字节"
         *   的错误响应（无需攻击者即可触发的正确性缺陷）；
         * - 旧实现的句柄是**请求局部**的，不在 [stop] 的清理集合里，因此 `stop()` 返回后
         *   仍可能有一个句柄存活一段有界时间。
         * 提升到会话级后：长度取自**同一个**句柄（`handle.length()`），且句柄由 [stop]
         * 在会话监视器内关闭，两个问题一并消除。
         *
         * 由本 Session 的监视器保护（与 [running] 同一把锁）。
         */
        var handle: RandomAccessFile? = null
    }
    private var current: Session? = null

    @Synchronized
    fun start(upstreamUrl: String, upstreamHeaders: Map<String, String>): String {
        stop()
        val listener = ServerSocket(0, MAX_CONNECTIONS, InetAddress.getByName("127.0.0.1"))
        val random = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val path = "/media/" + Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        val session = Session(listener, path, MpvUpstream.Http(upstreamUrl, upstreamHeaders.toMap()))
        current = session
        Thread({ acceptLoop(session) }, "mpv-http-bridge").apply { isDaemon = true }.start()
        return "http://127.0.0.1:${listener.localPort}$path"
    }

    /**
     * 以**单个本地媒体文件**作为上游启动回环桥（M9-P0）。
     *
     * 设计边界：token 路径随机不可预测，客户端只能按 token 取该会话的这一个文件；
     * 不接受客户端传入任何路径，因此不构成"任意文件读取服务"。
     */
    @Synchronized
    fun startLocalFile(path: String): String {
        stop()
        val listener = ServerSocket(0, MAX_CONNECTIONS, InetAddress.getByName("127.0.0.1"))
        val random = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val tokenPath = "/media/" + Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        val session = Session(listener, tokenPath, MpvUpstream.LocalFile(path))
        current = session
        Thread({ acceptLoop(session) }, "mpv-http-bridge").apply { isDaemon = true }.start()
        return "http://127.0.0.1:${listener.localPort}$tokenPath"
    }

    @Synchronized
    fun stop() {
        val session = current ?: return
        current = null
        synchronized(session) {
            session.running = false
            // 会话级句柄必须在监视器内关闭：与"读取循环检查 running"用同一把锁，
            // 因此 stop() 返回时句柄**一定**已关闭，不再依赖 socket 关闭的间接副作用。
            session.handle?.let { runCatching { it.close() } }
            session.handle = null
        }
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
                val headerDeadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(headerTimeoutMs.toLong())
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
                when (val upstream = session.upstream) {
                    is MpvUpstream.Http -> {
                val builder = Request.Builder().url(upstream.url).method(method, null)
                upstream.headers.forEach { (name, value) -> builder.header(name, value) }
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

                    is MpvUpstream.LocalFile -> {
                        responseStarted = true
                        serveLocalFile(session, upstream.path, method, forwarded, output)
                    }
                }
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

    private sealed interface RangeResult {
        data object None : RangeResult
        data class Satisfiable(val start: Long, val end: Long) : RangeResult
        data object Unsatisfiable : RangeResult
    }

    /**
     * 服务**当前会话唯一的**本地媒体文件（M9-P0）。
     *
     * - 只读该文件，分块流式写出，绝不整文件载入内存；
     * - 支持 GET/HEAD、单段 Range（含后缀 Range），不可满足返回 416；
     * - 多段 Range 与畸形 Range 按 RFC 7233 忽略（返回完整 200）；
     * - 出现 If-Range 时忽略 Range 返回完整 200（本桥不签发 ETag/Last-Modified，
     *   任何校验器都不匹配，故这是协议一致且安全的行为）；
     * - 打开失败返回明确 HTTP 状态（404），不让 mpv 只看到 TCP 静默关闭；
     * - 日志与响应不输出真实路径。
     */
    private fun serveLocalFile(
        session: Session,
        path: String,
        method: String,
        forwarded: Map<String, String>,
        output: OutputStream,
    ) {
        // 每次请求仍做**廉价的路径校验**（保持既有 404 语义：文件被删除后必须 404）。
        val file = resolveLocalFile(path)
        if (file == null) {
            writeHead(output, "404 Not Found", 0L)
            return
        }
        // 会话级句柄：长度与字节流必须来自**同一次打开**（消除 check-then-open 的 W2：
        // 声明长度与实际字节不一致）。句柄在会话监视器内获取/创建，stop() 在同一把锁内关闭。
        val handle = synchronized(session) {
            if (!session.running) return
            session.handle ?: runCatching { openBody.open(file) }.getOrNull()?.also { session.handle = it }
        }
        if (handle == null) {
            writeHead(output, "404 Not Found", 0L)
            return
        }
        // 长度取自句柄本身，而不是另一次 file.length() 调用。
        val length = runCatching { handle.length() }.getOrDefault(-1L)
        if (length < 0L) {
            writeHead(output, "404 Not Found", 0L)
            return
        }
        val requested = if (forwarded.containsKey("if-range")) null else forwarded["range"]
        val parsed = parseRange(requested, length)
        if (parsed is RangeResult.Unsatisfiable) {
            output.write((("HTTP/1.1 416 Range Not Satisfiable" + CRLF +
                "Content-Range: bytes */" + length + CRLF +
                "Content-Length: 0" + CRLF + CONNECTION_CLOSE + CRLF + CRLF)).toByteArray())
            output.flush()
            return
        }
        val partial = parsed as? RangeResult.Satisfiable
        val start = partial?.start ?: 0L
        val end = if (length == 0L) 0L else (partial?.end ?: (length - 1))
        val contentLength = if (length == 0L) 0L else end - start + 1
        val head = StringBuilder()
            .append(if (partial != null) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK").append(CRLF)
            .append("Content-Type: ").append(contentTypeFor(file.name)).append(CRLF)
            .append("Content-Length: ").append(contentLength).append(CRLF)
            .append("Accept-Ranges: bytes").append(CRLF)
        if (partial != null) {
            head.append("Content-Range: bytes ").append(start).append('-').append(end)
                .append('/').append(length).append(CRLF)
        }
        head.append(CONNECTION_CLOSE).append(CRLF).append(CRLF)
        output.write(head.toString().toByteArray())
        if (method == "HEAD" || contentLength == 0L) {
            output.flush()
            return
        }
        // 句柄是**会话级共享**的，因此 seek+read 必须在句柄上互斥（否则并发请求会互相
        // 破坏文件位置）。句柄**不在此处关闭** —— 它的生命周期属于会话，由 stop() 关闭。
        val buffer = ByteArray(LOCAL_COPY_BUFFER_BYTES)
        var remaining = contentLength
        try {
            synchronized(handle) {
                handle.seek(start)
                while (remaining > 0 && synchronized(session) { session.running }) {
                    val read = handle.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            output.flush()
        } catch (_: java.io.IOException) {
            // stop() 在会话监视器内关闭了句柄（或被客户端断开）：传输按设计中止。
            // 句柄归属会话，不得在此关闭。
            runCatching { output.flush() }
        } catch (_: IllegalArgumentException) {
            // 句柄已关闭：`seek` 会抛 IllegalArgumentException（closed RandomAccessFile）。
            runCatching { output.flush() }
        }
    }

    /** 明确的状态行 + 空体（不输出任何真实路径）。 */
    private fun writeHead(output: OutputStream, status: String, contentLength: Long) {
        output.write(("HTTP/1.1 " + status + CRLF + "Content-Length: " + contentLength + CRLF +
            CONNECTION_CLOSE + CRLF + CRLF).toByteArray())
        output.flush()
    }

    /**
     * 规范化并校验本地路径：接受绝对路径与 file:// URI；
     * canonicalFile 消解 `..` 与符号链接后才能通过 isFile/canRead 校验。
     *
     * file:// 必须经 [File] 的 URI 构造器解析，**不能**用 `URI.path`：
     * 后者返回**未做百分号解码**的字符串，且在 Windows 上把 `file:///C:/x` 解析成
     * `/C:/x`（非法路径）—— 结果使所有 file:// 授权（[MpvPlaybackEngine] 传入的
     * file:// 媒体源、以及 ServerIconStore 风格的引用）恒被 404 拒绝。
     * [File] 的 URI 构造器按平台语义归一化并解码，两种形态因而等价。
     */
    private fun resolveLocalFile(path: String): File? {
        val file = when {
            // 必须匹配整个 "file:" scheme，而不是字面量 "file://"：
            // Java/Kotlin 的 File.toURI() 产出 **单斜杠** 形式（file:/C:/x），
            // Android 的 Uri.fromFile() 产出 file:///x —— 两者都合法，都要接受。
            path.startsWith("file:", ignoreCase = true) ->
                runCatching { File(java.net.URI(path)) }.getOrNull()
            else -> path.takeIf { it.isNotBlank() }?.let(::File)
        } ?: return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!canonical.isFile || !canonical.canRead()) return null
        return canonical
    }

    private fun parseRange(header: String?, length: Long): RangeResult {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty() || !raw.startsWith("bytes=")) return RangeResult.None
        val spec = raw.removePrefix("bytes=")
        if (spec.contains(',')) return RangeResult.None
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.None
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        return try {
            if (startText.isEmpty()) {
                val suffix = endText.toLong()
                if (suffix <= 0L || length == 0L) RangeResult.Unsatisfiable
                else RangeResult.Satisfiable(maxOf(0L, length - suffix), length - 1)
            } else {
                val start = startText.toLong()
                // 未提供 last-byte-pos 时为 null：必须与"显式给出但小于 first"区分，
                // 否则 0 字节文件上的 `bytes=0-` 会被误判成无效 spec（回归已由测试捕获）。
                val rawEnd = if (endText.isEmpty()) null else endText.toLong()
                when {
                    // RFC 7233 §3.1：first 为负、或**显式** last < first → 无效 spec → MUST 忽略（完整 200）。
                    start < 0L || (rawEnd != null && rawEnd < start) -> RangeResult.None
                    // 语法合法但起点超出当前表示长度 → 不可满足 → 416（含 length == 0 的情形）。
                    start >= length -> RangeResult.Unsatisfiable
                    else -> RangeResult.Satisfiable(start, minOf(rawEnd ?: (length - 1), length - 1))
                }
            }
        } catch (_: NumberFormatException) {
            RangeResult.None
        }
    }

    private fun contentTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "flv" -> "video/x-flv"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "srt" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }

    /** Reads at most limit bytes and rejects unterminated or overlong lines before allocation grows. */
    private fun readLine(input: InputStream, socket: Socket, deadline: Long, limit: Int): String? {
        val text = StringBuilder()
        for (index in 0 until limit) {
            // One monotonic deadline spans the complete request header. A peer cannot
            // renew its admission by dripping bytes just before an idle timeout.
            val remainingNanos = deadline - nanoTime()
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

        /** 本地文件分块写出大小：流式，绝不整文件载入内存。 */
        const val LOCAL_COPY_BUFFER_BYTES = 64 * 1024

        /** HTTP 行结束符：用字符码构造，避免源码中的转义序列歧义。 */
        val CRLF: String = String(charArrayOf(13.toChar(), 10.toChar()))
        const val CONNECTION_CLOSE = "Connection: close"
        val FORWARDED_HEADERS = setOf("range", "if-range", "if-none-match")
        val HOP_HEADERS = setOf("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade")
    }
}
