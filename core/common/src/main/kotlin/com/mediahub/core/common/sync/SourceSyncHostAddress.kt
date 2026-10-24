package com.mediahub.core.common.sync

import com.mediahub.core.common.pairing.LoopbackOnly
import java.net.InetAddress

/**
 * 监听端的地址闸门（W6-52）：缺省只绑回环，唯一的例外是调用方自己点开开关并写下一个单播字面量。
 *
 * 与 Windows W6-51 的 SourceSyncTransferAddress.TryResolve 同名同义：开关关着时两端都只接受回环；
 * 开关打开后多接受「一台机器的地址」——单播 IPv4 / IPv6 字面量。通配、广播、组播、保留地址与主机名
 * 一律在创建 socket 之前拒绝，码仍是 SourceSyncTransferReasons.NOT_LOOPBACK，理由写在 detail 句里
 * （与 Windows 相同的取舍：码表不扩容，诊断由句子承载，见 docs/cross-device/W6-51-EXPLICIT-HOST.md 第 5 节）。
 *
 * 为什么不做 DNS：解析结果不受本端控制，把「绑哪块网卡」交给解析器等于没设防（与 LoopbackOnly 同一条理由）。
 * IPv4 由本对象自己按点分十进制逐组解析（decodeIpv4），从不经过名字服务；IPv6 只在形状合格时才交给
 * InetAddress.getByName，形态不合格的串（包括任何主机名）在到达解析器之前就被挡住。
 */
object SourceSyncHostAddress {

    /** 地址串的长度上限（与 Windows 相同）：再长就不是人写得出来的地址了。 */
    const val MAX_LENGTH: Int = 256

    /** 拒绝句里给的例子（与 Windows 文档一致）；只是例子，不参与判定。 */
    const val EXAMPLE_UNICAST: String = "192.168.1.20"

    /** 判定结果：接受就带 InetAddress，拒绝就带稳定码与一句诊断。 */
    sealed interface Resolution {

        /** 地址被接受；[text] 是去空白后的原文（空白时取缺省地址）。 */
        class Accepted(val address: InetAddress, val text: String) : Resolution

        /** 地址被拒绝；[code] 只可能是 not-loopback（本闸门不新造码）。 */
        class Failed(val code: String, val detail: String) : Resolution
    }

    /**
     * 判定一个监听地址。
     *
     * @param host 调用方给的地址；null 或空白表示 [SourceSyncTransferListener.DEFAULT_HOST]。
     * @param allowExplicitHost 调用方是否自己打开了非回环开关；false 时与 W6-52 之前逐字同行为。
     */
    fun resolve(host: String?, allowExplicitHost: Boolean): Resolution {
        val text = host?.trim().orEmpty().ifEmpty { SourceSyncTransferListener.DEFAULT_HOST }
        if (text.length > MAX_LENGTH) {
            return refused("地址最多 " + MAX_LENGTH + " 个字符，这个没有去绑。")
        }
        val loopback = LoopbackOnly.addressOrNull(text)
        if (loopback != null) {
            // 回环永远先过：开关只放宽非回环，不改变回环的判定，也不改变既有那句拒绝文案。
            return Resolution.Accepted(loopback, text)
        }
        if (!allowExplicitHost) {
            return refused("只允许绑定回环地址（127.0.0.1 / ::1 / localhost），实际 " + text)
        }
        val literal = parseLiteral(text)
            ?: return refused(
                "「" + text + "」不是 IP 字面量，本版本传输不做域名解析；请填 127.0.0.1、::1、localhost 或那台机器的单播 IP。",
            )
        if (!isUnicastLiteral(literal)) {
            // 开关只为「一台机器」打开：通配会监听所有网卡，广播/组播/保留地址不指向任何一台机器，都不收。
            return refused(
                "「" + text + "」不是一个具体某台机器的地址（通配 / 广播 / 组播 / 保留地址），"
                    + "建 socket 之前就拒绝了；请填一个单播地址，例如 " + EXAMPLE_UNICAST + "。",
            )
        }
        return Resolution.Accepted(literal, text)
    }

    private fun refused(detail: String): Resolution.Failed =
        Resolution.Failed(SourceSyncTransferReasons.NOT_LOOPBACK, detail)

    /**
     * 只解析字面量，绝不查名字服务。
     *
     * 形状先过一遍（与 LoopbackOnly 同一对正则，避免把主机名交给解析器），IPv4 再自己算四个字节，
     * IPv6 交给 InetAddress.getByName ——含冒号的串不可能是主机名，解析失败只会得到 null。
     */
    private fun parseLiteral(text: String): InetAddress? {
        val literal = text.removePrefix("[").removeSuffix("]")
        decodeIpv4(literal)?.let { return it }
        if (!literal.contains(':') || !IPV6_VALUE.matches(literal)) {
            return null
        }
        return try {
            InetAddress.getByName(literal)
        } catch (error: Exception) {
            null
        }
    }

    /** 点分十进制严格解析：四组、每组 1..3 位、值 0..255，多位的组不接受前导零。 */
    private fun decodeIpv4(text: String): InetAddress? {
        val parts = text.split('.')
        if (parts.size != 4) {
            return null
        }
        val bytes = ByteArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) {
                return null
            }
            if (part.length > 1 && part[0] == '0') {
                return null
            }
            val value = part.toInt()
            if (value > 255) {
                return null
            }
            bytes[index] = value.toByte()
        }
        return try {
            InetAddress.getByAddress(bytes)
        } catch (error: Exception) {
            null
        }
    }

    /**
     * 是否是一个「某台机器」的地址：回环不会走到这里（它先被接受）。
     *
     * 剩下的只该有两种：单播地址（一个对端），以及根本不指向一台机器的那些——0.0.0.0 / ::（所有接口）、
     * 255.255.255.255（本段广播）、组播（224/4、ff00::/8）与保留段（240/4）。
     */
    private fun isUnicastLiteral(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size == 16) {
            if (bytes.all { it == 0.toByte() }) {
                return false
            }
            return (bytes[0].toInt() and 0xFF) != 0xFF
        }
        if (bytes.size != 4) {
            return false
        }
        val first = bytes[0].toInt() and 0xFF
        if (first == 0) {
            // 0.0.0.0/8 是「本网络」：0.0.0.0 本身表示所有接口。
            return false
        }
        if (bytes.all { it == 0xFF.toByte() }) {
            return false
        }
        // 224/4 是组播、240/4 是保留；两者都不是一个对端。
        return first < 224
    }

    private val IPV6_VALUE = Regex("^[0-9a-fA-F:.]+$")
}
