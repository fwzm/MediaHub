package com.mediahub.core.common.pairing

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 假 Windows 配对主机：只在 127.0.0.1:0 上监听，按 Windows 的帧格式收发字节。
 *
 * 只用于测试替身：真机 / 局域网 TLS 服务端不存在于本轮范围，但它必须说同一套帧语言，
 * 否则测试就是在自证。responder 在独立工作线程里跑，异常记到 failure 上由断言线程检查，
 * 避免断言失败被吞进线程栈里无法归因。close() 关闭监听与在途连接并等待线程退出。
 */
internal class FakeWindowsPairingHost(
    label: String = "fake",
    private val responder: FakeWindowsPairingHost.(Socket) -> Unit,
) : AutoCloseable {

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val worker = Thread({ serve() }, "fake-windows-pairing-" + label)
    private val peerClosedLatch = CountDownLatch(1)
    private val lock = Any()
    private var accepted = 0
    private var inFlight: Socket? = null

    @Volatile
    private var lastRequest: ByteArray? = null

    @Volatile
    private var responderFailure: Throwable? = null

    init {
        worker.isDaemon = true
        worker.start()
    }

    /** 监听端口（0 表示由系统分配，这里已经是真实端口）。 */
    val port: Int
        get() = server.localPort

    /** 已经 accept 过的连接数；用于证明某些失败在拨号前就被拒绝。 */
    val acceptedCount: Int
        get() = synchronized(lock) { accepted }

    /** 是否只绑了回环地址。 */
    val loopbackOnly: Boolean
        get() = server.inetAddress.isLoopbackAddress

    /** 假主机收到的请求帧载荷；没收到为 null。 */
    val requestPayload: ByteArray?
        get() = lastRequest

    /** responder 里抛出的异常（断言失败会落在这里）。 */
    val failure: Throwable?
        get() = responderFailure

    val isClosed: Boolean
        get() = server.isClosed

    val workerAlive: Boolean
        get() = worker.isAlive

    private fun serve() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (error: IOException) {
                return
            }
            synchronized(lock) {
                accepted += 1
                inFlight = socket
            }
            try {
                socket.soTimeout = 200
                responder.invoke(this, socket)
            } catch (error: Throwable) {
                responderFailure = error
            } finally {
                synchronized(lock) { inFlight = null }
                closeQuietly(socket)
            }
        }
    }

    /** 写一帧（4 字节大端长度 + 载荷），与 Windows PairingFrame 同格式。 */
    fun writeFrame(socket: Socket, payload: ByteArray) {
        val output = socket.getOutputStream()
        output.write(PairingFrame.encode(payload))
        output.flush()
    }

    /** 写原始字节：用于制造非法帧（长度 0、超长、截断）。 */
    fun writeRaw(socket: Socket, bytes: ByteArray) {
        val output = socket.getOutputStream()
        output.write(bytes)
        output.flush()
    }

    /** 读一帧请求；客户端没发或提前关闭返回 null。 */
    fun readFrame(socket: Socket): ByteArray? {
        val payload = readFrameOrNull(socket.getInputStream()) ?: return null
        lastRequest = payload
        return payload
    }

    /**
     * 阻塞到对端关闭（读到 EOF），最多 timeoutMs。返回是否真的读到关闭；
     * 用于证明客户端读完就关 socket（不留半开连接）。
     */
    fun awaitEndOfStream(socket: Socket, timeoutMs: Int = 2_000): Boolean {
        val input = socket.getInputStream()
        val buffer = ByteArray(64)
        val deadline = System.currentTimeMillis() + timeoutMs
        socket.soTimeout = 100
        var closed = false
        while (System.currentTimeMillis() < deadline) {
            val read = try {
                input.read(buffer)
            } catch (timeout: SocketTimeoutException) {
                continue
            } catch (error: IOException) {
                closed = true
                break
            }
            if (read < 0) {
                closed = true
                break
            }
        }
        if (closed) {
            peerClosedLatch.countDown()
        }
        return closed
    }

    /** 等待 responder 观察到对端关闭。 */
    fun awaitPeerClosed(timeoutMs: Long): Boolean = peerClosedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        closeQuietly(server)
        synchronized(lock) { inFlight?.let { closeQuietly(it) } }
        worker.join(3_000)
        if (!worker.isAlive) {
            peerClosedLatch.countDown()
        }
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        if (closeable == null) {
            return
        }
        try {
            closeable.close()
        } catch (error: IOException) {
        }
    }

    private fun readFrameOrNull(input: InputStream): ByteArray? {
        val header = readFully(input, PairingFrame.HEADER_BYTES) ?: return null
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (length <= 0 || length > PairingFrame.MAXIMUM_PAYLOAD_BYTES) {
            return null
        }
        return readFully(input, length)
    }

    private fun readFully(input: InputStream, size: Int): ByteArray? {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = try {
                input.read(buffer, offset, size - offset)
            } catch (error: IOException) {
                return null
            }
            if (read < 0) {
                return null
            }
            offset += read
        }
        return buffer
    }
}
