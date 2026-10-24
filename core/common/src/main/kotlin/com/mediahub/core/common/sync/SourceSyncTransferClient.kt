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
 * - 只连回环（127.0.0.1 / ::1 / localhost），别的目标在拨号前拒绝，码为 not-loopback；
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
     */
    fun receiveFrom(
        host: String,
        port: Int,
        expectedChallenge: String? = null,
        tls: SourceSyncTlsOptions = SourceSyncTlsOptions.Off,
        auth: SourceSyncTransferOptions? = null,
    ): SourceSyncTransferResult {
        return withConnection(host, port, tls) { input, _ ->
            SourceSyncTransfer.receive(input, expectedChallenge, auth)
        }
    }

    /**
     * 连上对端并送出一封信封（本端是发送方）。
     *
     * @param auth 可选的对端认证（W6-25）；非 null 且持口令时信封后追加一帧 HMAC tag。
     */
    fun sendTo(
        host: String,
        port: Int,
        envelope: ByteArray,
        challenge: String? = null,
        tls: SourceSyncTlsOptions = SourceSyncTlsOptions.Off,
        auth: SourceSyncTransferOptions? = null,
    ): SourceSyncTransferResult {
        return withConnection(host, port, tls) { _, output ->
            SourceSyncTransfer.sendTo(output, envelope, challenge, auth)
        }
    }

    private fun withConnection(
        host: String,
        port: Int,
        tls: SourceSyncTlsOptions,
        body: (InputStream, OutputStream) -> SourceSyncTransferResult,
    ): SourceSyncTransferResult {
        val address = LoopbackOnly.addressOrNull(host)
            ?: return SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.NOT_LOOPBACK,
                "只允许连回环地址，实际 " + host,
            )
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
            body(input, output)
        } catch (timeout: SocketTimeoutException) {
            SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.TRANSFER_TIMEOUT,
                "对端在超时前没有响应",
            )
        } catch (refused: SocketException) {
            SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.CONNECT_FAILED,
                "无法连接对端：" + refused.javaClass.simpleName,
            )
        } catch (error: IOException) {
            SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.IO_FAILED,
                "连接失败：" + error.javaClass.simpleName,
            )
        } finally {
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
