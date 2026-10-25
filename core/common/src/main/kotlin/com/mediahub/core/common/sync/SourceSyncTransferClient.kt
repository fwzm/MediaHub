package com.mediahub.core.common.sync

import com.mediahub.core.common.pairing.LoopbackOnly
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * 分块信封传输的客户端：一次连接只做一件事（送一封信封，或收一封信封），做完即关。
 *
 * 与 PairingTransportClient 同一条纪律：
 * - 缺省只连回环（127.0.0.1 / ::1 / localhost），别的目标在拨号前拒绝，码为 not-loopback；
 *   W6-52b 起可由调用方显式同意放宽到一个**单播字面量**（[allowExplicitHost]，判定在
 *   [SourceSyncHostAddress] 里，与监听端 W6-52 同名同义）；
 * - 主机名不做 DNS 解析，避免把「只连本机」交给解析器决定；
 * - TLS 1.3 + 公钥指纹钉扎是可选的（W6-29，默认 [SourceSyncTlsOptions.Off]）：
 *   关闭时一个字节都不多说，字节与 W6-22 至今完全一致；开启时只谈 TLS 1.3，
 *   指纹不符就硬失败（不信任首次、不降级明文），关闭走 graceful 半关；
 * - 无论成功失败都关 socket。
 */
class SourceSyncTransferClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val handshakeTimeoutMs: Int = SourceSyncTls.DEFAULT_HANDSHAKE_TIMEOUT_MS,
) {

    init {
        require(connectTimeoutMs > 0) { "连接超时必须为正，实际 " + connectTimeoutMs }
        require(readTimeoutMs > 0) { "读取超时必须为正，实际 " + readTimeoutMs }
        require(handshakeTimeoutMs > 0) { "握手超时必须为正，实际 " + handshakeTimeoutMs }
    }

    /**
     * 连上对端并读一封信封（对端是发送方）。
     *
     * @param tls 默认关闭；开启时先握手再读帧，握手失败直接返回 tls- 前缀的稳定码。
     * @param auth 可选的对端认证（W6-25）；默认 null，字节与今天完全一致。
     * @param receipt 可选的回执（W6-40）：本端当接收方时，收全一封信封后写一个裸 0x04；
     *   默认 [SourceSyncReceiptOptions.Off]，一个字节都不多写。
     * @param receiptGate 可选解码闸门：返回 false 时沉默（不写回执，也不把这次接收改成失败）。
     * @param allowExplicitHost 只有 true 时才允许连一个显式的非回环单播字面量（W6-52b）；
     *   默认 false，与 W6-23 起的「只连回环、别的在拨号前拒」逐字同义。
     * @param cancel 可选的本地取消把手（W6-58）：非 null 时，取消会立刻关掉这条连接，
     *   被阻塞的读随即返回本地码 [SourceSyncLocalReasons.CANCELLED]——不是 io-failed，
     *   也与任何跨机码无关。缺省 null 时行为与 W6-52b 逐字相同。
     */
    fun receiveFrom(
        host: String,
        port: Int,
        expectedChallenge: String? = null,
        tls: SourceSyncTlsOptions = SourceSyncTlsOptions.Off,
        auth: SourceSyncTransferOptions? = null,
        receipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off,
        receiptGate: ((ByteArray) -> Boolean)? = null,
        allowExplicitHost: Boolean = false,
        cancel: SourceSyncCancellation? = null,
    ): SourceSyncTransferResult {
        return withConnection(host, port, tls, allowExplicitHost, cancel) { input, output, _ ->
            val received = SourceSyncTransfer.receive(input, expectedChallenge, auth)
            if (receipt.enabled && received is SourceSyncTransferResult.Ok) {
                SourceSyncReceipt.acknowledge(output, received.envelope, receipt, receiptGate)
            }
            received
        }
    }

    /**
     * 连上对端并送出一封信封（本端是发送方）。
     *
     * @param auth 可选的对端认证（W6-25）；非 null 且持口令时信封后追加一帧 HMAC tag。
     * @param receipt 可选的等回执（W6-40）：开启时写完帧后读一个字节，收到 0x04 才算送达；
     *   预算默认 2000 毫秒，会临时改这条连接的读超时（连接随即关闭，所以不恢复）。
     * @param delivery 可选的投递确认（W6-46）：开启时写完帧后读对端直到它关连接，读不到 FIN 就不算送达。
     *   默认 [SourceSyncDeliveryOptions.Off]——一个字节不多写、一次读不多做、一毫秒不多等。
     *   **回执优先**：receipt 开启时 delivery 被忽略（0x04 是应用层答案，比「关了连接」信息量更大，
     *   也与 Windows CLI 的 ConfirmDelivery && !AwaitReceipt 同口径）。
     * @param allowExplicitHost 只有 true 时才允许连一个显式的非回环单播字面量（W6-52b），
     *   判定与诊断句都来自 [SourceSyncHostAddress]，与监听端 W6-52 同名同义。
     */
    fun sendTo(
        host: String,
        port: Int,
        envelope: ByteArray,
        challenge: String? = null,
        tls: SourceSyncTlsOptions = SourceSyncTlsOptions.Off,
        auth: SourceSyncTransferOptions? = null,
        receipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off,
        delivery: SourceSyncDeliveryOptions = SourceSyncDeliveryOptions.Off,
        allowExplicitHost: Boolean = false,
    ): SourceSyncTransferResult {
        val effectiveDelivery: SourceSyncDeliveryOptions =
            if (receipt.enabled) SourceSyncDeliveryOptions.Off else delivery
        return withConnection(host, port, tls, allowExplicitHost) { input, output, setReadTimeout ->
            if (receipt.enabled) {
                SourceSyncTransfer.sendAndAwaitReceipt(
                    output = output,
                    input = input,
                    envelope = envelope,
                    challenge = challenge,
                    options = auth,
                    receipt = receipt,
                    readTimeoutSetter = setReadTimeout,
                )
            } else {
                when (val sent = SourceSyncTransfer.sendTo(output, envelope, challenge, auth)) {
                    is SourceSyncTransferResult.Ok ->
                        if (!effectiveDelivery.confirm) {
                            sent
                        } else {
                            awaitPeerClose(input, sent, envelope, effectiveDelivery, setReadTimeout)
                        }
                    is SourceSyncTransferResult.Failed -> sent
                }
            }
        }
    }

    /**
     * 本地取消的结果（W6-58）。
     *
     * 单独抽出来是为了让「取消」在任何 catch 分支里都优先于网络分类：
     * 关掉本地 socket 会让阻塞的读抛 SocketException/IOException，那些类名对用户毫无意义。
     */
    private fun cancelledResult(): SourceSyncTransferResult =
        SourceSyncTransferResult.Failed(
            SourceSyncLocalReasons.CANCELLED,
            "本次接收已被本地取消：连接在本地关闭，对端可能只看到断开。",
        )

    /** 帧写完之后等对端关连接；只有读到 FIN 才把结果原样（加上确认）交回，否则折成 delivery-unconfirmed。 */
    private fun awaitPeerClose(
        input: InputStream,
        sent: SourceSyncTransferResult.Ok,
        envelope: ByteArray,
        delivery: SourceSyncDeliveryOptions,
        setReadTimeout: (Int) -> Unit,
    ): SourceSyncTransferResult {
        return when (
            val confirmation = SourceSyncDeliveryConfirmation.confirm(
                input = input,
                sentBytes = envelope.size,
                budgetMs = delivery.budgetMs,
                setReadTimeout = setReadTimeout,
            )
        ) {
            is SourceSyncDeliveryResult.Confirmed -> SourceSyncTransferResult.Ok(
                envelope = sent.envelope,
                frameCount = sent.frameCount,
                challenge = sent.challenge,
                delivery = confirmation,
            )
            is SourceSyncDeliveryResult.Unconfirmed -> SourceSyncTransferResult.Failed(
                confirmation.reason,
                confirmation.detail,
            )
        }
    }

    private fun withConnection(
        host: String,
        port: Int,
        tls: SourceSyncTlsOptions,
        allowExplicitHost: Boolean,
        cancel: SourceSyncCancellation? = null,
        body: (InputStream, OutputStream, (Int) -> Unit) -> SourceSyncTransferResult,
    ): SourceSyncTransferResult {
        val address = when (
            val resolution = SourceSyncHostAddress.resolve(host, allowExplicitHost, verb = "连")
        ) {
            is SourceSyncHostAddress.Resolution.Failed ->
                return SourceSyncTransferResult.Failed(resolution.code, resolution.detail)
            is SourceSyncHostAddress.Resolution.Accepted -> resolution.address
        }
        if (port !in 1..MAXIMUM_PORT) {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.CONNECT_FAILED,
                "端口必须在 1.." + MAXIMUM_PORT + "，实际 " + port,
            )
        }
        var socket: Socket? = null
        var tlsSocket: Socket? = null
        return try {
            val opened = Socket()
            socket = opened
            // W6-58：先挂上取消把手再拨号——取消若发生在 connect/read 期间，关掉这个 socket 就能把阻塞解开。
            cancel?.attach(opened)
            opened.connect(InetSocketAddress(address, port), connectTimeoutMs)
            opened.soTimeout = readTimeoutMs
            opened.tcpNoDelay = true
            var input = opened.getInputStream()
            var output = opened.getOutputStream()
            if (tls.enabled) {
                when (val negotiated = SourceSyncTls.handshakeClient(opened, tls, handshakeTimeoutMs)) {
                    is SourceSyncTlsResult.Failed ->
                        return SourceSyncTransferResult.Failed(negotiated.reason, negotiated.detail)
                    is SourceSyncTlsResult.Ok -> {
                        val session = negotiated.session
                        tlsSocket = session.socket
                        session.socket.soTimeout = readTimeoutMs
                        input = session.input
                        output = session.output
                    }
                }
            }
            // 第三个参数把「改这条连接的读超时」交给业务层：等回执必须把预算落到活跃 socket 上，
            // 这样「对端静默」与「对端关了」才分得开，也不必另起线程。
            val activeSocket = tlsSocket ?: opened
            val outcome = body(input, output) { millis -> activeSocket.soTimeout = millis }
            // 取消优先（W6-58）：协议层自己会把「本地关连接」折成 io-failed / frame-truncated，
            // 那些类名对用户毫无意义；用户按的取消必须在任何失败分类之前胜出。只覆盖失败结果，
            // 成功收下的一封信封不会被一次晚到的取消抹掉。
            if (cancel?.isCancelled == true && outcome is SourceSyncTransferResult.Failed) {
                cancelledResult()
            } else {
                outcome
            }
        } catch (timeout: SocketTimeoutException) {
            // 取消优先：本地关连接会让阻塞的读/连立刻返回，这不能被记成对端超时。
            if (cancel?.isCancelled == true) return cancelledResult()
            SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.TRANSFER_TIMEOUT,
                "对端在超时前没有响应",
            )
        } catch (refused: SocketException) {
            if (cancel?.isCancelled == true) return cancelledResult()
            SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.CONNECT_FAILED,
                "无法连接对端：" + refused.javaClass.simpleName,
            )
        } catch (error: IOException) {
            if (cancel?.isCancelled == true) return cancelledResult()
            SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.IO_FAILED,
                "连接失败：" + error.javaClass.simpleName,
            )
        } finally {
            socket?.let { cancel?.detach(it) }
            val negotiatedSocket = tlsSocket
            if (negotiatedSocket != null) {
                SourceSyncTls.gracefulClose(negotiatedSocket)
            } else {
                LoopbackOnly.closeQuietly(socket)
            }
        }
    }

    companion object {
        /** 默认连接超时，毫秒。 */
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 3_000

        /** 默认读取超时，毫秒；与 Windows SourceSyncTransferListener.DefaultTimeout（15 秒）对齐。 */
        const val DEFAULT_READ_TIMEOUT_MS: Int = 15_000

        private const val MAXIMUM_PORT = 65_535
    }
}
