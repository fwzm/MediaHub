package com.mediahub.core.common.pairing

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
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
 * 能力边界（W6-G 刻意收窄，未做项见 docs/android-pairing.md 第 10 节）：
 * - 只连回环：127.0.0.1 / ::1 / localhost。其它目标在拨号前拒绝，码为 PairingClientReasons.NON_LOOPBACK_PEER；
 * - 不做 TLS 1.3、不做证书指纹钉扎（Windows 侧有；Android 侧要等真机传输接入）；
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
     */
    fun exchange(
        host: String,
        port: Int,
        request: ByteArray? = null,
        responseLimit: Int = PairingFrame.MAXIMUM_PAYLOAD_BYTES,
    ): PairingFrameResult {
        require(port in 1..MAXIMUM_PORT) { "端口必须在 1..65535，实际 " + port }
        require(responseLimit in 1..PairingFrame.MAXIMUM_PAYLOAD_BYTES) {
            "响应上限必须在 1.." + PairingFrame.MAXIMUM_PAYLOAD_BYTES + "，实际 " + responseLimit
        }
        val address = loopbackAddressOrNull(host)
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
        var socket: Socket? = null
        var connected = false
        return try {
            val opened = Socket()
            socket = opened
            opened.connect(InetSocketAddress(address, port), connectTimeoutMs)
            connected = true
            opened.soTimeout = readTimeoutMs
            opened.tcpNoDelay = true
            if (requestFrame != null) {
                val output = opened.getOutputStream()
                output.write(requestFrame)
                output.flush()
            }
            when (val read = readFrame(opened.getInputStream(), responseLimit)) {
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
            closeQuietly(socket)
        }
    }

    /**
     * 拉取并校验一份配对邀请：帧结构 -> 载荷解码 -> 时效 -> challenge -> 指纹。
     *
     * 顺序是刻意的：先证明字节是帧、再证明内容是载荷、再证明没过期、再证明是同一个 challenge、
     * 最后（可选的）证明公钥指纹就是邀请里钉的那一个。任何一步失败都给稳定码，不给半成品。
     */
    fun fetchInvitation(
        host: String,
        port: Int,
        expectedChallenge: PairingChallenge,
        expectedFingerprint: PairingFingerprint? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): PairingInvitationResult {
        if (expectedChallenge.isEmpty) {
            return PairingInvitationResult.Failed(
                PairingReasons.BAD_CHALLENGE,
                "没有期望 challenge 就无法判定对端是不是同一个邀请",
            )
        }
        return when (val frame = exchange(host, port, request = null)) {
            is PairingFrameResult.Failed -> PairingInvitationResult.Failed(frame.reason, frame.message)
            is PairingFrameResult.Ok ->
                validate(frame.payload, expectedChallenge, expectedFingerprint, nowEpochMs)
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

    private sealed interface FrameRead {
        class Payload(val bytes: ByteArray) : FrameRead
        class Rejected(val detail: String) : FrameRead
    }

    private fun readFrame(input: InputStream, limit: Int): FrameRead {
        val header = readFully(input, PairingFrame.HEADER_BYTES)
            ?: return FrameRead.Rejected("帧头不足 " + PairingFrame.HEADER_BYTES + " 字节")
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (length <= 0) {
            return FrameRead.Rejected("帧长度非法：" + length)
        }
        if (length > limit) {
            return FrameRead.Rejected("帧长度 " + length + " 字节超过上限 " + limit)
        }
        val body = readFully(input, length)
            ?: return FrameRead.Rejected("帧体不足：声明 " + length + " 字节，实际提前结束")
        return FrameRead.Payload(body)
    }

    /** 读满 size 字节；对端提前关闭返回 null。超时与其它 IO 错误交给 exchange 的 catch 统一映射。 */
    private fun readFully(input: InputStream, size: Int): ByteArray? {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) {
                return null
            }
            offset += read
        }
        return buffer
    }

    /**
     * 只解析字面量地址，不做 DNS：主机名解析结果不受本端控制，等于把「只连回环」交给解析器决定。
     * 返回 null 一律由调用方映射成 non-loopback-peer。
     */
    private fun loopbackAddressOrNull(host: String): InetAddress? {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        if (trimmed.equals("localhost", ignoreCase = true)) {
            return InetAddress.getLoopbackAddress()
        }
        val literal = trimmed.removePrefix("[").removeSuffix("]")
        val looksLikeIpv4 = IPV4_LITERAL.matches(literal)
        val looksLikeIpv6 = literal.contains(':') && IPV6_LITERAL.matches(literal)
        if (!looksLikeIpv4 && !looksLikeIpv6) {
            return null
        }
        val address = try {
            InetAddress.getByName(literal)
        } catch (error: Exception) {
            return null
        }
        return if (address.isLoopbackAddress) address else null
    }

    private fun closeQuietly(socket: Socket?) {
        if (socket == null) {
            return
        }
        try {
            socket.close()
        } catch (error: IOException) {
        }
    }

    companion object {
        /** 默认连接超时，毫秒。 */
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 3_000

        /** 默认读取超时，毫秒。 */
        const val DEFAULT_READ_TIMEOUT_MS: Int = 5_000

        private const val MAXIMUM_PORT = 65_535

        private val IPV4_LITERAL = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}")
        private val IPV6_LITERAL = Regex("^[0-9a-fA-F:.]+$")
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
