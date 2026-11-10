package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.WireText
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网卡枚举的来源（W6-60）。
 *
 * 生产实现读真实网卡，测试给假数据：这样「排序 + 分类」这条链能在普通 JVM 单测里逐条钉住，
 * 既不需要真网卡，也不需要 Android 的运行时权限。
 */
fun interface PairingNetworkInterfaceSource {

    fun read(): List<PairingAddressInput>
}

/**
 * 接口名与系统标志 → [PairingInterfaceKind] 的纯映射（W6-60）。
 *
 * java.net.NetworkInterface 没有 Windows 的 NetworkInterfaceType，只能靠四件事判断：
 * 是不是回环、是不是虚拟、是不是点对点、名字前缀像什么。这些规则单独放在这里，
 * 唯一目的就是让它们能在 JVM 单测里被逐条断言，而不是埋进枚举实现里只能靠真机试。
 */
object PairingInterfaceKinds {

    /** 点对点 / VPN 类接口的名字前缀（Android 上 tun0、ppp0、ipsec0 是常见形态）。 */
    private val TUNNEL_PREFIXES: List<String> =
        listOf("tun", "tap", "ppp", "ipsec", "pptp", "l2tp", "wg")

    /** 无线接口的名字前缀（wlan0 / wl0 / wifi0 / swlan0 都出现过）。 */
    private val WIRELESS_PREFIXES: List<String> = listOf("wlan", "wl", "wifi", "swlan")

    /**
     * 判一类接口。
     *
     * 顺序即优先级：回环 > 隧道 > 无线 > 有线。回环放第一位是因为它一旦被认成有线，
     * 就会去抢「首选地址」的位置；隧道放第二是因为虚拟网卡的名字五花八门，不能靠名字兜底。
     */
    fun of(
        interfaceName: String,
        loopback: Boolean,
        virtual: Boolean,
        pointToPoint: Boolean,
    ): PairingInterfaceKind {
        if (loopback) return PairingInterfaceKind.LOOPBACK
        val name = WireText.lowercaseAscii(WireText.trim(interfaceName))
        if (virtual || pointToPoint || TUNNEL_PREFIXES.any { name.startsWith(it) }) {
            return PairingInterfaceKind.TUNNEL
        }
        if (WIRELESS_PREFIXES.any { name.startsWith(it) }) {
            return PairingInterfaceKind.WIRELESS
        }
        return PairingInterfaceKind.WIRED
    }
}

/**
 * 真实网卡 → [PairingAddressInput] 的适配层，再交给 [PairingAddressCandidates] 排序（W6-60）。
 *
 * 与 Windows 的 PairingAddressCandidates.Describe() 同义、同可见性：
 * - 只收 IPv4（IPv6 不收集，Windows 那边也不收，两端看到的东西必须一样）；
 * - 单个网卡读属性抛异常 → 这个网卡贡献空，其余照算；
 * - 读整个网卡列表抛异常 → [PairingAddressCandidates.failed]，即 address-enumeration-failed；
 * - 从不抛出，也不新造 Android 专用码。
 *
 * 本类只产展示文本：不 bind、不 dial、不监听任何地址。配对监听端仍然只绑回环
 * （与 Windows 侧同类注释一致：在真实网卡上监听会弹防火墙，而留在公网接口上是负债不是特性）。
 */
class PairingAddressEnumerator(
    private val source: PairingNetworkInterfaceSource = JvmNetworkInterfaceSource(),
) {

    fun enumerate(): PairingAddressReport {
        val inputs = try {
            source.read()
        } catch (unreadable: Exception) {
            return PairingAddressCandidates.failed()
        }
        return PairingAddressCandidates.describe(inputs)
    }
}

/** 生产实现：读本机真实网卡，只读属性，不发包。 */
class JvmNetworkInterfaceSource : PairingNetworkInterfaceSource {

    override fun read(): List<PairingAddressInput> {
        val adapters = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        val inputs = ArrayList<PairingAddressInput>()
        for (adapter in adapters) {
            val input = inputOf(adapter) ?: continue
            inputs.add(input)
        }
        return inputs
    }

    private fun inputOf(adapter: NetworkInterface): PairingAddressInput? = try {
        val name = adapter.name ?: ""
        PairingAddressInput(
            interfaceName = name,
            kind = PairingInterfaceKinds.of(
                interfaceName = name,
                loopback = adapter.isLoopback,
                virtual = adapter.isVirtual,
                pointToPoint = adapter.isPointToPoint,
            ),
            up = adapter.isUp,
            addresses = ipv4AddressesOf(adapter),
        )
    } catch (unreadable: Exception) {
        // 与 Windows 同语义：一个网卡读不出来就跳过它，不拖垮整张表。
        null
    }

    private fun ipv4AddressesOf(adapter: NetworkInterface): List<String> =
        adapter.interfaceAddresses.orEmpty()
            .mapNotNull { it.address }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
}
