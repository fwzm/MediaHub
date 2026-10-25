package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.SourceSyncTls
import com.mediahub.core.common.sync.SourceSyncTlsOptions
import com.mediahub.core.common.sync.SourceSyncTlsResult
import com.mediahub.core.common.sync.SourceSyncTlsSession
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * 回环配对传输客户端：用 JDK socket 与对端交换一个配对帧。
 *
 * 帧格式与 PairingFrame 完全一致（4 字节大端长度 + 载荷；没有 magic、没有帧内版本），
 * 上限 8192 字节。读端拿到长度后立刻判上限，绝不为非法长度分配内存——超长声明只值 4 个字节的代价。
 *
 * 能力边界：
 * - 只连回环：127.0.0.1 / ::1 / localhost。其它目标在拨号前拒绝，码为 PairingClientReasons.NON_LOOPBACK_PEER
 *   （W6-59b 只放开 TLS，没有放开地址闸门；放开地址要等候选地址接网卡枚举的那一轮）；
 * - TLS 1.3 + 公钥指纹钉扎可选（W6-59b，对齐 Windows PairingTransportClient）：不给
 *   [PairingTlsOptions] 时字节与 W6-G 至今逐字一致；给了就以钉住的指纹为信任根，链验证不参与
 *   （自签证书没有 CA，二维码才是信任根）；
 * - 单次交换：可选先写一帧请求，再读一帧响应，读完即关 socket，调用方不需要持有连接；
 * - 连接与读取超时都可配；两类超时统一映射为合同码 peer-timeout。
 *
 * 失败码全部复用合同码，唯一例外是 non-loopback-peer（Android 本地策略码）。
 */
class PairingTransportClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {

    init {
        require(connectTimeoutMs > 0) { "连接超时必须为正，实际 " + connectTimeoutMs }
        require(readTimeoutMs > 0) { "读取超时必须为正，实际 " + readTimeoutMs }
    }

    /**
     * 与对端交换一次帧：request 非空时先写一帧，然后读一帧响应。
     *
     * request 为 null 表示只读（Windows 的配对服务端是先发载荷的一方，客户端不需要先说话）。
     * 任何失败都折成 PairingFrameResult.Failed，不抛异常；调用方只按 reason 分支。
     *
     * @param tls 传输层要不要走 TLS 1.3 并钉住指纹。缺省 [PairingTlsOptions.Off]：字节与 W6-G
     *   至今完全一致。给了 Pinned 时，指纹不是合法 hex 会在**拨号之前**判成
     *   [PairingReasons.BAD_FINGERPRINT]（一个字节都不发）；握手里任何失败按 [pairingTlsReasonOf]
     *   收敛成 tls-version 或 fingerprint-mismatch。
     */
    fun exchange(
        host: String,
        port: Int,
        request: ByteArray? = null,
        responseLimit: Int = PairingFrame.MAXIMUM_PAYLOAD_BYTES,
        tls: PairingTlsOptions = PairingTlsOptions.Off,
    ): PairingFrameResult {
        require(port in 1..MAXIMUM_PORT) { "端口必须在 1..65535，实际 " + port }
        require(responseLimit in 1..PairingFrame.MAXIMUM_PAYLOAD_BYTES) {
            "响应上限必须在 1.." + PairingFrame.MAXIMUM_PAYLOAD_BYTES + "，实际 " + responseLimit
        }
        val address = LoopbackOnly.addressOrNull(host)
            ?: return PairingFrameResult.Failed(
                PairingClientReasons.NON_LOOPBACK_PEER,
                "只允许连回环地址，实际 " + host,
            )
        val requestFrame = when {
            request == null -> null
            request.isEmpty() -> return PairingFrameResult.Failed(
                PairingTransportReasons.PAYLOAD_INVALID,
                "请求载荷不能为空",
            )
            request.size > PairingFrame.MAXIMUM_PAYLOAD_BYTES -> return PairingFrameResult.Failed(
                PairingTransportReasons.PAYLOAD_INVALID,
                "请求载荷 " + request.size + " 字节超过上限 " + PairingFrame.MAXIMUM_PAYLOAD_BYTES,
            )
            else -> PairingFrame.encode(request)
        }
        // 指纹在拨号前判：没有可靠指纹就不该把字节发出去，也不该悄悄降级成明文（与 Windows 同序）。
        val pinned = if (!tls.enabled) {
            null
        } else {
            tls.expectedFingerprint?.let { PairingFingerprint.tryParse(it) }
                ?: return PairingFrameResult.Failed(
                    PairingReasons.BAD_FINGERPRINT,
                    "TLS 已开启，但钉住的指纹不是 64 位十六进制：没有可比对的对象，连接没有发起。",
                )
        }
        var socket: Socket? = null
        var session: SourceSyncTlsSession? = null
        var connected = false
        var handshakeCompleted = false
        return try {
            val opened = Socket()
            socket = opened
            opened.connect(InetSocketAddress(address, port), connectTimeoutMs)
            connected = true
            opened.soTimeout = readTimeoutMs
            opened.tcpNoDelay = true
            var input: InputStream = opened.getInputStream()
            var output: OutputStream = opened.getOutputStream()
            if (pinned != null) {
                val handshake = SourceSyncTls.handshakeClient(
                    opened,
                    SourceSyncTlsOptions.Pinned(pinned.hex),
                    timeoutMs = readTimeoutMs,
                    targetHostName = PAIRING_TLS_TARGET_HOST_NAME,
                )
                when (handshake) {
                    is SourceSyncTlsResult.Failed -> return PairingFrameResult.Failed(
                        pairingTlsReasonOf(handshake.reason),
                        handshake.detail,
                    )
                    is SourceSyncTlsResult.Ok -> {
                        session = handshake.session
                        input = handshake.session.input
                        output = handshake.session.output
                        handshakeCompleted = true
                    }
                }
            }
            if (requestFrame != null) {
                output.write(requestFrame)
                output.flush()
            }
            when (val read = PairingFrameIo.readFrame(input, responseLimit)) {
                is FrameRead.Payload -> PairingFrameResult.Ok(read.bytes)
                is FrameRead.Rejected -> PairingFrameResult.Failed(
                    PairingTransportReasons.PAYLOAD_INVALID,
                    read.detail,
                )
            }
        } catch (timeout: SocketTimeoutException) {
            PairingFrameResult.Failed(PairingTransportReasons.PEER_TIMEOUT, "对端在超时前没有完成交换")
        } catch (error: IOException) {
            if (connected) {
                PairingFrameResult.Failed(
                    PairingTransportReasons.PAYLOAD_INVALID,
                    "连接中途失败：" + error.javaClass.simpleName,
                )
            } else {
                PairingFrameResult.Failed(
                    PairingTransportReasons.LISTENER_FAILED,
                    "无法连接对端：" + error.javaClass.simpleName,
                )
            }
        } finally {
            val held = session
            if (held != null) {
                // TLS 1.3 握手后立刻硬关是 abortive close(RST)，对端会读成 io-failed：半关 + 排水 + 关。
                SourceSyncTls.gracefulClose(held.socket, readTimeoutMs)
            } else {
                LoopbackOnly.closeQuietly(socket)
            }
        }
    }

    /**
     * 拉取并校验一份配对邀请：帧结构 -> 载荷解码 -> 时效 -> challenge -> 指纹。
     *
     * 顺序是刻意的：先证明字节是帧、再证明内容是载荷、再证明没过期、再证明是同一个 challenge、
     * 最后（可选的）证明公钥指纹就是邀请里钉的那一个。任何一步失败都给稳定码，不给半成品。
     *
     * @param tls 传输层 TLS 选项。开启时载荷里的指纹**也必须**等于钉住的那一枚——
     *   Windows 的第二道冗余检查：回别人邀请的对端不是我们拨的那一台。
     */
    fun fetchInvitation(
        host: String,
        port: Int,
        expectedChallenge: PairingChallenge,
        expectedFingerprint: PairingFingerprint? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
        tls: PairingTlsOptions = PairingTlsOptions.Off,
    ): PairingInvitationResult {
        if (expectedChallenge.isEmpty) {
            return PairingInvitationResult.Failed(
                PairingReasons.BAD_CHALLENGE,
                "没有期望 challenge 就无法判定对端是不是同一个邀请",
            )
        }
        // TLS 钉住的那一枚是信任根（握手已经证明过），所以它优先于调用方另给的期望指纹。
        val pinned = (tls as? PairingTlsOptions.Pinned)?.fingerprint
        val required = pinned ?: expectedFingerprint
        return when (val frame = exchange(host, port, request = null, tls = tls)) {
            is PairingFrameResult.Failed -> PairingInvitationResult.Failed(frame.reason, frame.message)
            is PairingFrameResult.Ok ->
                validate(frame.payload, expectedChallenge, required, nowEpochMs)
        }
    }

    private fun validate(
        bytes: ByteArray,
        expectedChallenge: PairingChallenge,
        expectedFingerprint: PairingFingerprint?,
        nowEpochMs: Long,
    ): PairingInvitationResult {
        val text = String(bytes, StandardCharsets.UTF_8)
        val decoded = PairingPayloadCodec.tryDecode(text)
        if (decoded is PairingPayloadCodec.DecodeResult.Failed) {
            return PairingInvitationResult.Failed(
                PairingTransportReasons.PAYLOAD_INVALID,
                "对端载荷不是合法配对载荷：" + decoded.reason,
            )
        }
        val payload = (decoded as PairingPayloadCodec.DecodeResult.Ok).payload
        if (payload.expiresAtEpochMs <= nowEpochMs) {
            return PairingInvitationResult.Failed(
                PairingTransportReasons.LIFETIME_EXCEEDED,
                "邀请在 " + payload.expiresAtEpochMs + " ms 过期，当前 " + nowEpochMs + " ms",
            )
        }
        if (!payload.challenge.matches(expectedChallenge)) {
            return PairingInvitationResult.Failed(
                PairingReasons.CHALLENGE_MISMATCH,
                "对端回传的 challenge 与本端不一致",
            )
        }
        if (expectedFingerprint != null && !payload.publicKeyFingerprint.matches(expectedFingerprint)) {
            return PairingInvitationResult.Failed(
                PairingTransportReasons.FINGERPRINT_MISMATCH,
                "对端载荷的公钥指纹与邀请不符",
            )
        }
        return PairingInvitationResult.Ok(payload, text)
    }

    companion object {
        /** 默认连接超时，毫秒。 */
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 3_000

        /** 默认读取超时，毫秒；TLS 路径同时用它当握手预算。 */
        const val DEFAULT_READ_TIMEOUT_MS: Int = 5_000

        private const val MAXIMUM_PORT = 65_535
    }
}

/** 一次帧交换的结果：成功只给载荷字节，失败只给稳定码。 */
sealed interface PairingFrameResult {
    class Ok(val payload: ByteArray) : PairingFrameResult
    class Failed(val reason: String, val message: String? = null) : PairingFrameResult
}

/** 一次邀请拉取的结果：成功给已校验的载荷与原始 JSON，失败只给稳定码。 */
sealed interface PairingInvitationResult {
    class Ok(val payload: PairingPayload, val json: String) : PairingInvitationResult
    class Failed(val reason: String, val message: String? = null) : PairingInvitationResult
}