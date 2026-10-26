package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * 帧读的一段结果：要么拿到载荷，要么拿到一句诊断（诊断只进日志/异常消息，永不进稳定码）。
 *
 * 抽到包级可见是因为同步传输（SourceSyncTransferProtocol）也要读同样的 4 字节大端长度帧；
 * 复制第三份帧读法意味着以后改一处上限要改三处，这类分歧迟早会让两端对不上。
 */
internal sealed interface FrameRead {
    class Payload(val bytes: ByteArray) : FrameRead

    class Rejected(val detail: String) : FrameRead
}

/**
 * 4 字节大端长度帧的读写实现（唯一一份）。
 *
 * 读端的次序是刻意的：先读满帧头，再判长度是否在 1..limit 内，最后才读帧体。
 * 非法长度在分配内存之前就被拒绝——超长声明只值 4 个字节的代价。
 */
internal object PairingFrameIo {

    /** 写一帧（空载荷或超过上限会由 PairingFrame.encode 抛 IllegalArgumentException）。 */
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        output.write(PairingFrame.encode(payload))
    }

    /** 读一帧。帧头不足、长度非法、帧体不足都返回 Rejected，不抛异常。 */
    fun readFrame(input: InputStream, limit: Int): FrameRead {
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

    /** 读满 size 字节；对端提前关闭返回 null。超时与其它 IO 错误交给调用方统一映射。 */
    fun readFully(input: InputStream, size: Int): ByteArray? {
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
}

/**
 * 回环闸门：只解析字面量地址，不做 DNS。
 *
 * 主机名解析结果不受本端控制——把「只连本机」交给解析器决定等于没设防；
 * 所以 localhost 特判、IPv4/IPv6 字面量正则先过一遍，解析后再要求 isLoopbackAddress。
 */
internal object LoopbackOnly {

    /** 回环地址；不是回环或不是字面量一律返回 null，由调用方映射成 non-loopback-peer。 */
    fun addressOrNull(host: String): InetAddress? {
        val trimmed = WireText.trim(host)
        if (trimmed.isEmpty()) {
            return null
        }
        if (WireText.equalsAsciiIgnoreCase(trimmed, "localhost")) {
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

    /** 关 socket 且吞掉关闭期的 IO 异常：finally 里再抛一个异常会盖掉真正的原因。 */
    fun closeQuietly(socket: Socket?) {
        if (socket == null) {
            return
        }
        try {
            socket.close()
        } catch (error: IOException) {
        }
    }

    private val IPV4_LITERAL = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}")
    private val IPV6_LITERAL = Regex("^[0-9a-fA-F:.]+$")
}
