package com.mediahub.core.common.sync

import com.mediahub.core.common.pairing.LoopbackOnly
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 监听端一次接收的结果（W6-34）。
 *
 * 与 SourceSyncTransferResult 一样，失败一定是一个稳定码加一句诊断，绝不抛给调用方；
 * 码全部来自既有码表（SourceSyncTransferReasons / SourceSyncTlsReasons / SourceSyncTransferAuthReasons），
 * 本类不新造任何一个码。
 */
sealed interface SourceSyncListenerTransfer {

    /**
     * 收到一封信封。
     *
     * @param envelope 帧层拼出来的原始字节。
     * @param envelopeJson 同一份字节按 UTF-8 解出来的文本（交给导入服务用；解码仍是它的事）。
     * @param frames 实际读到的帧数（明文无挑战帧时等于块数，开认证时块数加 2）。
     * @param bytes 信封字节数。
     * @param challenge 对端在挑战帧里宣布的挑战，没有则为 null。
     * @param receiptWritten 本端是否真的写出了 0x04 回执（回执关着、没有可写流、'
     *   或解码闸门拒收时为 false）。
     */
    class Received(
        val envelope: ByteArray,
        val envelopeJson: String,
        val frames: Int,
        val bytes: Int,
        val challenge: String?,
        val receiptWritten: Boolean = false,
    ) : SourceSyncListenerTransfer

    /** 没收到信封：码来自既有三张码表，detail 是给人看的诊断。 */
    class Failed(val code: String, val detail: String? = null) : SourceSyncListenerTransfer
}

/** [SourceSyncTransferListener.open] 的结果：要么监听已起来，要么一个稳定码。 */
sealed interface SourceSyncListenerStart {

    /** 监听已在回环上起来；用完必须 close，否则端口一直占着。 */
    class Started(val listener: SourceSyncTransferListener) : SourceSyncListenerStart

    /** 监听没有起来（not-loopback / tls-certificate-unavailable / listener-failed）。 */
    class Failed(val code: String, val detail: String? = null) : SourceSyncListenerStart
}

/**
 * W6-34：只绑回环的接收端。
 *
 * 与 Windows SourceSyncTransferListener（SourceSyncTransferEndpoint.cs:140）对齐的部分：
 * - 解帧一律走 SourceSyncTransfer.receive，本类不复制任何一帧的长度判定；
 * - TLS 开启时先 SourceSyncTls.handshakeServer，明文时先 SourceSyncTls.probePlaintext 做 5 字节嗅探
 *   （读掉的前缀原样回放，所以明文路径与不嗅探逐字节等价）；
 * - 认证开启时把 SourceSyncTransferOptions 原样交给 receive，五码由它给出（先验 tag 再记 nonce）；
 * - 收完一条连接就把它关掉：TLS 走 SourceSyncTls.gracefulClose，明文走 LoopbackOnly.closeQuietly。
 *
 * 与 Windows 的一处刻意差异（docs/android-source-sync-transfer.md 第 19 节记录）：
 * Windows 的监听器只 accept 一次就作废；这里的监听器可以连续 receiveOnce（一次一条连接，串行），
 * 直到 close()。这样 Android 界面才能在同一个端口上等第二次投递，而不用每次重新报端口。
 */
class SourceSyncTransferListener private constructor(
    private val server: ServerSocket,
    private val boundAddress: String,
    private val boundPort: Int,
    private val identity: SourceSyncTlsIdentity?,
    private val acceptTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val handshakeTimeoutMs: Int,
    private val closeBudgetMs: Int,
) : Closeable {

    private val acceptLock = Any()

    private val closed = AtomicBoolean(false)

    /** 绑定的回环地址文本。 */
    val address: String get() = boundAddress

    /** 实际绑定的端口；open 时给 0 也会在这里拿到系统分配的端口。 */
    val port: Int get() = boundPort

    /** 是否说 TLS 1.3（open 时 tls.enabled 且证书可用才是 true）。 */
    val tlsEnabled: Boolean get() = identity != null

    /** 本端证书的公钥指纹（小写 64 hex）；明文监听为 null。它是公开信息，不属于秘密。 */
    val certificateFingerprint: String? get() = identity?.certificateFingerprint

    /** 是否已经 close。 */
    val isClosed: Boolean get() = closed.get()

    /** 等待对端连上来的预算，毫秒。 */
    val acceptTimeoutMillis: Int get() = acceptTimeoutMs

    /** 连上之后读一封信封的预算，毫秒。 */
    val readTimeoutMillis: Int get() = readTimeoutMs

    /**
     * 接受一条连接，读一封信封，关掉这条连接，返回结果。
     *
     * 同一时刻只处理一条连接（方法整体持锁）；第二条连接要么在 backlog 里等，要么等上一条做完。
     *
     * @param expectedChallenge 非空时，对端在挑战帧里宣布的挑战必须逐字相等，否则 challenge-mismatch；
     *   null 表示接受对端宣布的任何挑战（与 Windows CLI serve 同口径）。
     * @param auth 可选的对端认证（W6-25）：非 null 且持口令时要求对端在最后一块之后跟一帧 HMAC tag。
     * @param receipt 可选的回执（W6-40）：开启且信封按帧收全时，向对端写一个裸 0x04 字节；
     *   默认 [SourceSyncReceiptOptions.Off]，不多写一个字节。
     * @param receiptGate 可选解码闸门：返回 false 时刻意沉默（不写回执，也不把接收改成失败），
     *   对应 Windows ReceiveOnceAsync 的 receiptGate 参数。
     * @return [SourceSyncListenerTransfer.Received] 或 [SourceSyncListenerTransfer.Failed]，不抛异常。
     */
    fun receiveOnce(
        expectedChallenge: String? = null,
        auth: SourceSyncTransferOptions? = null,
        receipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off,
        receiptGate: ((ByteArray) -> Boolean)? = null,
    ): SourceSyncListenerTransfer {
        synchronized(acceptLock) {
            return acceptAndReceive(expectedChallenge, auth, receipt, receiptGate)
        }
    }

    /**
     * 关掉监听并释放端口；重复调用无副作用。
     *
     * 正在等待 accept 的线程会立刻拿到 SocketException，被翻译成 listener-failed（监听已关闭）。
     * 正在读一条连接的线程不受影响，它按自己的读超时收尾。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            server.close()
        } catch (error: IOException) {
            // 关一个已经关掉的监听不算失败。
        }
    }

    private fun acceptAndReceive(
        expectedChallenge: String?,
        auth: SourceSyncTransferOptions?,
        receipt: SourceSyncReceiptOptions,
        receiptGate: ((ByteArray) -> Boolean)?,
    ): SourceSyncListenerTransfer {
        if (closed.get()) {
            return failed(SourceSyncTransferReasons.LISTENER_FAILED, CLOSED_DETAIL)
        }
        val socket = try {
            server.accept()
        } catch (timeout: SocketTimeoutException) {
            return failed(
                SourceSyncTransferReasons.TRANSFER_TIMEOUT,
                "在 " + acceptTimeoutMs + " 毫秒内没有对端连上来，本次接收结束。",
            )
        } catch (closedNow: SocketException) {
            return failed(SourceSyncTransferReasons.LISTENER_FAILED, CLOSED_DETAIL)
        } catch (error: IOException) {
            return failed(
                SourceSyncTransferReasons.IO_FAILED,
                "接受连接时出了 IO 错误：" + error.javaClass.simpleName,
            )
        }
        return receiveOver(socket, expectedChallenge, auth, receipt, receiptGate)
    }

    private fun receiveOver(
        socket: Socket,
        expectedChallenge: String?,
        auth: SourceSyncTransferOptions?,
        receipt: SourceSyncReceiptOptions,
        receiptGate: ((ByteArray) -> Boolean)?,
    ): SourceSyncListenerTransfer {
        var tlsSocket: Socket? = null
        var writeStream: OutputStream? = null
        return try {
            socket.tcpNoDelay = true
            socket.soTimeout = readTimeoutMs
            val input: InputStream = if (identity == null) {
                when (val probe = SourceSyncTls.probePlaintext(socket.getInputStream())) {
                    is SourceSyncTlsProbe.Refused -> return failed(probe.reason, probe.detail)
                    is SourceSyncTlsProbe.Plaintext -> probe.stream
                }
            } else {
                // advertisedFingerprint 用本端自己的指纹：Windows 侧同样只检查"宣告的就是手里这枚"。
                val advertised = identity.certificateFingerprint
                when (val negotiated = SourceSyncTls.handshakeServer(socket, identity, advertised, handshakeTimeoutMs)) {
                    is SourceSyncTlsResult.Failed -> return failed(negotiated.reason, negotiated.detail)
                    is SourceSyncTlsResult.Ok -> {
                        tlsSocket = negotiated.session.socket
                        negotiated.session.socket.soTimeout = readTimeoutMs
                        writeStream = negotiated.session.output
                        negotiated.session.input
                    }
                }
            }
            when (val received = SourceSyncTransfer.receive(input, expectedChallenge, auth)) {
                is SourceSyncTransferResult.Failed -> failed(received.reason, received.message)
                is SourceSyncTransferResult.Ok -> {
                    // 回执只在按帧收全之后写：半帧、校验失败、认证失配一律沉默，
                    // 与 Windows WriteReceiptAsync 的位置（ReceiveAsync 之后、gate 之前写）同形。
                    // 明文时写到 socket 本身而不是嗅探包装流——包装流只读（Windows 同样写 writable socket）。
                    val receiptWritten = if (receipt.enabled) {
                        val writable = writeStream ?: socket.getOutputStream()
                        SourceSyncReceipt.acknowledge(writable, received.envelope, receipt, receiptGate).confirmed
                    } else {
                        false
                    }
                    SourceSyncListenerTransfer.Received(
                        envelope = received.envelope,
                        envelopeJson = received.envelope.toString(Charsets.UTF_8),
                        frames = received.frameCount,
                        bytes = received.envelope.size,
                        challenge = received.challenge,
                        receiptWritten = receiptWritten,
                    )
                }
            }
        } catch (timeout: SocketTimeoutException) {
            failed(
                SourceSyncTransferReasons.TRANSFER_TIMEOUT,
                "对端在这条连接上超时，信封没有收全。",
            )
        } catch (error: IOException) {
            failed(
                SourceSyncTransferReasons.IO_FAILED,
                "读取这条连接时出了 IO 错误：" + error.javaClass.simpleName,
            )
        } finally {
            val negotiatedSocket = tlsSocket
            if (negotiatedSocket != null) {
                SourceSyncTls.gracefulClose(negotiatedSocket, closeBudgetMs)
            } else {
                LoopbackOnly.closeQuietly(socket)
            }
        }
    }

    private fun failed(code: String, detail: String?): SourceSyncListenerTransfer =
        SourceSyncListenerTransfer.Failed(code, detail)

    companion object {

        /** 默认绑定的回环地址（与 Windows SourceSyncTransferAddress.DefaultAddress 一致）。 */
        const val DEFAULT_HOST: String = "127.0.0.1"

        /** 默认等待对端连上来的预算，毫秒（与 Windows SourceSyncTransferListener.DefaultTimeout 对齐）。 */
        const val DEFAULT_ACCEPT_TIMEOUT_MS: Int = 15_000

        /** 默认读取一封信封的预算，毫秒。 */
        const val DEFAULT_READ_TIMEOUT_MS: Int = 15_000

        /** 监听 backlog，够本机一次性重试即可。 */
        private const val BACKLOG = 4

        private const val MAXIMUM_PORT = 65_535

        private const val CLOSED_DETAIL = "监听已经关闭，没有接受连接。"

        /**
         * 在回环上打开监听。
         *
         * 判定次序与 Windows Start 一致：先地址，再证书（TLS 开着但没有证书时在 socket 之前就失败），
         * 最后才 bind，所以 listener-failed 一定是"端口被占用"这一层的问题。
         *
         * @param host 回环地址，null 或空白表示 127.0.0.1；非回环在创建 socket 之前就被拒绝（not-loopback）。
         * @param port 0 表示让系统分配；实际端口从返回的 listener.port 取。
         * @param tls 默认关闭；开启时要求 identity 可用，否则 tls-certificate-unavailable。
         */
        fun open(
            host: String? = null,
            port: Int = 0,
            tls: SourceSyncTlsOptions = SourceSyncTlsOptions.Off,
            identity: SourceSyncTlsIdentity = SourceSyncTlsIdentity.NONE,
            acceptTimeoutMs: Int = DEFAULT_ACCEPT_TIMEOUT_MS,
            readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
            handshakeTimeoutMs: Int = SourceSyncTls.DEFAULT_HANDSHAKE_TIMEOUT_MS,
            closeBudgetMs: Int = SourceSyncTls.DEFAULT_CLOSE_BUDGET_MS,
        ): SourceSyncListenerStart {
            val text = host?.trim().orEmpty()
            val address = LoopbackOnly.addressOrNull(if (text.isEmpty()) DEFAULT_HOST else text)
                ?: return SourceSyncListenerStart.Failed(
                    SourceSyncTransferReasons.NOT_LOOPBACK,
                    "只允许绑定回环地址（127.0.0.1 / ::1 / localhost），实际 " + text,
                )
            if (port !in 0..MAXIMUM_PORT) {
                return SourceSyncListenerStart.Failed(
                    SourceSyncTransferReasons.LISTENER_FAILED,
                    "端口必须在 0.." + MAXIMUM_PORT + "（0 表示让系统分配），实际 " + port + "，监听没有建立。",
                )
            }
            if (acceptTimeoutMs <= 0 || readTimeoutMs <= 0 || handshakeTimeoutMs <= 0 || closeBudgetMs < 0) {
                return SourceSyncListenerStart.Failed(
                    SourceSyncTransferReasons.LISTENER_FAILED,
                    "超时必须是正数，监听没有建立。",
                )
            }
            val useTls = tls.enabled
            if (useTls && !identity.isUsable) {
                return SourceSyncListenerStart.Failed(
                    SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE,
                    "TLS 已开启，但本端没有可用的服务端证书（KeyStore 里没有带私钥的条目），监听没有建立。",
                )
            }
            var opened: ServerSocket? = null
            return try {
                val socket = ServerSocket()
                opened = socket
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(address, port), BACKLOG)
                socket.soTimeout = acceptTimeoutMs
                val bound = socket.inetAddress?.hostAddress ?: DEFAULT_HOST
                SourceSyncListenerStart.Started(
                    SourceSyncTransferListener(
                        server = socket,
                        boundAddress = bound,
                        boundPort = socket.localPort,
                        identity = if (useTls) identity else null,
                        acceptTimeoutMs = acceptTimeoutMs,
                        readTimeoutMs = readTimeoutMs,
                        handshakeTimeoutMs = handshakeTimeoutMs,
                        closeBudgetMs = closeBudgetMs,
                    ),
                )
            } catch (error: SocketException) {
                opened?.let { server -> runCatching { server.close() } }
                SourceSyncListenerStart.Failed(
                    SourceSyncTransferReasons.LISTENER_FAILED,
                    "回环监听没能建立（端口 " + port + " 可能已被占用）：" + error.javaClass.simpleName,
                )
            } catch (error: IOException) {
                opened?.let { server -> runCatching { server.close() } }
                SourceSyncListenerStart.Failed(
                    SourceSyncTransferReasons.LISTENER_FAILED,
                    "回环监听没能建立：" + error.javaClass.simpleName,
                )
            }
        }
    }
}
