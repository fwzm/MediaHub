package com.mediahub.core.common.sync

import com.mediahub.core.common.pairing.LoopbackOnly
import java.io.IOException
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
 * - 不做 TLS、不做指纹钉扎（Windows 侧有；Android 侧等真机传输接入再做，见 docs/android-source-sync-transfer.md）；
 * - 无论成功失败都关 socket。
 */
class SourceSyncTransferClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {

    init {
        require(connectTimeoutMs > 0) { "连接超时必须为正，实际 " + connectTimeoutMs }
        require(readTimeoutMs > 0) { "读取超时必须为正，实际 " + readTimeoutMs }
    }

    /** 连上对端并读一封信封（对端是发送方）。 */
    fun receiveFrom(host: String, port: Int, expectedChallenge: String? = null): SourceSyncTransferResult {
        return withConnection(host, port) { socket ->
            SourceSyncTransfer.receive(socket.getInputStream(), expectedChallenge)
        }
    }

    /** 连上对端并送出一封信封（本端是发送方）。 */
    fun sendTo(
        host: String,
        port: Int,
        envelope: ByteArray,
        challenge: String? = null,
    ): SourceSyncTransferResult {
        return withConnection(host, port) { socket ->
            SourceSyncTransfer.sendTo(socket.getOutputStream(), envelope, challenge)
        }
    }

    private fun withConnection(
        host: String,
        port: Int,
        body: (Socket) -> SourceSyncTransferResult,
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
        return try {
            val opened = Socket()
            socket = opened
            opened.connect(InetSocketAddress(address, port), connectTimeoutMs)
            opened.soTimeout = readTimeoutMs
            opened.tcpNoDelay = true
            body(opened)
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
            LoopbackOnly.closeQuietly(socket)
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
