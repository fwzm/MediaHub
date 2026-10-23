package com.mediahub.core.common.pairing

/**
 * 网络接口类型。Android 没有 System.Net.NetworkInformation.NetworkInterfaceType，
 * 因此用本端自有枚举表达 Windows 的三档优先级（有线 / 无线 / 其它）。
 */
enum class PairingInterfaceKind {
    WIRED,
    WIRELESS,
    OTHER,
    LOOPBACK,
    TUNNEL,
}

/**
 * 一条候选/被拒地址。reason 用 PairingAddressReasons 的稳定码。
 */
data class PairingAddressCandidate(
    val address: String,
    val interfaceName: String,
    val priority: Int,
    val reason: String,
) {
    /** 展示用地址：https://<ip>:<port>/pairing（从不用于监听，只给对端拼 URL）。 */
    fun toUrl(port: Int): String = "https://" + address + ":" + port + "/pairing"
}

/**
 * 候选地址报告：candidates 已排序，rejected 保持输入顺序（便于原样解释为什么被拒）。
 */
data class PairingAddressReport(
    val candidates: List<PairingAddressCandidate>,
    val rejected: List<PairingAddressCandidate>,
    val reason: String?,
) {
    val isEmpty: Boolean
        get() = candidates.isEmpty()

    val count: Int
        get() = candidates.size

    val primary: PairingAddressCandidate?
        get() = candidates.firstOrNull()

    companion object {
        fun of(candidates: List<PairingAddressCandidate>, rejected: List<PairingAddressCandidate>): PairingAddressReport =
            PairingAddressReport(
                candidates = candidates,
                rejected = rejected,
                reason = if (candidates.isEmpty()) PairingAddressReasons.NO_USABLE_ADDRESS else null,
            )
    }
}

/** 接口输入：接口名、类型、是否 Up、接口上的地址字符串列表（可含非 IPv4）。 */
data class PairingAddressInput(
    val interfaceName: String,
    val kind: PairingInterfaceKind,
    val up: Boolean,
    val addresses: List<String>,
)

/**
 * 纯计算的局域网候选地址排序，与 Windows PairingAddressCandidates.cs 逐字对齐。
 *
 * 规则（顺序即实现顺序）：
 * 1. Loopback 接口：接口上每条地址都 rejected / loopback-address；
 * 2. Tunnel 接口：每条地址 rejected / other-interface；
 * 3. 其余接口逐条地址：非 IPv4 → not-ipv4；接口非 Up → interface-down；
 *    地址是回环 → loopback-address；地址是 APIPA(169.254/16) → apipa-address；
 *    重复地址 → 直接丢弃（不进 rejected）；否则 usable。
 * 4. 排序：priority 升序 → 地址字节逐字节升序 → 接口名（忽略大小写）。
 *
 * 本类不监听端口、不发包、不枚举真实网卡：真实枚举由调用方（Android WifiManager/
 * ConnectivityManager）完成，随后把结果交给这里排序。
 */
object PairingAddressCandidates {

    private fun priorityOf(kind: PairingInterfaceKind): Int = when (kind) {
        PairingInterfaceKind.WIRED -> 0
        PairingInterfaceKind.WIRELESS -> 1
        PairingInterfaceKind.OTHER -> 2
        PairingInterfaceKind.LOOPBACK -> 2
        PairingInterfaceKind.TUNNEL -> 2
    }

    private fun reasonOf(kind: PairingInterfaceKind): String = when (kind) {
        PairingInterfaceKind.WIRED -> PairingAddressReasons.WIRED
        PairingInterfaceKind.WIRELESS -> PairingAddressReasons.WIRELESS
        else -> PairingAddressReasons.OTHER
    }

    /**
     * 排序并分类。
     */
    fun describe(inputs: List<PairingAddressInput>): PairingAddressReport {
        val usable = ArrayList<PairingAddressCandidate>()
        val rejected = ArrayList<PairingAddressCandidate>()
        val seen = HashSet<String>()
        for (input in inputs) {
            val priority = priorityOf(input.kind)
            when (input.kind) {
                PairingInterfaceKind.LOOPBACK -> {
                    for (raw in input.addresses) {
                        rejected.add(
                            PairingAddressCandidate(
                                address = raw.trim(),
                                interfaceName = input.interfaceName,
                                priority = priority,
                                reason = PairingAddressReasons.LOOPBACK,
                            )
                        )
                    }
                }
                PairingInterfaceKind.TUNNEL -> {
                    for (raw in input.addresses) {
                        rejected.add(
                            PairingAddressCandidate(
                                address = raw.trim(),
                                interfaceName = input.interfaceName,
                                priority = priority,
                                reason = PairingAddressReasons.OTHER,
                            )
                        )
                    }
                }
                else -> {
                    for (raw in input.addresses) {
                        val address = raw.trim()
                        val octets = parseIpv4(address)
                        if (octets == null) {
                            rejected.add(reject(address, input.interfaceName, priority, PairingAddressReasons.NOT_IPV4))
                            continue
                        }
                        if (!input.up) {
                            rejected.add(reject(address, input.interfaceName, priority, PairingAddressReasons.INTERFACE_DOWN))
                            continue
                        }
                        if (isLoopback(octets)) {
                            rejected.add(reject(address, input.interfaceName, priority, PairingAddressReasons.LOOPBACK))
                            continue
                        }
                        if (isApipa(octets)) {
                            rejected.add(reject(address, input.interfaceName, priority, PairingAddressReasons.APIPA))
                            continue
                        }
                        if (!seen.add(address)) {
                            continue
                        }
                        usable.add(
                            PairingAddressCandidate(
                                address = address,
                                interfaceName = input.interfaceName,
                                priority = priority,
                                reason = reasonOf(input.kind),
                            )
                        )
                    }
                }
            }
        }
        val sorted = usable.sortedWith(
            compareBy<PairingAddressCandidate> { it.priority }
                .thenComparator { left, right -> compareAddresses(left.address, right.address) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.interfaceName }
        )
        return PairingAddressReport.of(sorted, rejected)
    }

    /** 枚举网卡本身失败时的报告（没有候选，理由是 address-enumeration-failed）。 */
    fun failed(): PairingAddressReport =
        PairingAddressReport(
            candidates = emptyList(),
            rejected = emptyList(),
            reason = PairingAddressReasons.ENUMERATION_FAILED,
        )

    /**
     * 严格 IPv4 解析：四段十进制、每段 0..255、不接受空段/前导符号/端口/空格。
     * 手写而不走 InetAddress，避免触发 DNS 或把域名当地址。
     */
    fun parseIpv4(text: String): IntArray? {
        if (text.isEmpty()) return null
        val parts = text.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (index in parts.indices) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3) return null
            var value = 0
            for (character in part) {
                if (character !in '0'..'9') return null
                value = value * 10 + (character - '0')
            }
            if (value > 255) return null
            octets[index] = value
        }
        return octets
    }

    private fun isLoopback(octets: IntArray): Boolean = octets[0] == 127

    private fun isApipa(octets: IntArray): Boolean = octets[0] == 169 && octets[1] == 254

    private fun reject(address: String, interfaceName: String, priority: Int, reason: String): PairingAddressCandidate =
        PairingAddressCandidate(address = address, interfaceName = interfaceName, priority = priority, reason = reason)

    private fun compareAddresses(left: String, right: String): Int {
        val leftOctets = parseIpv4(left)
        val rightOctets = parseIpv4(right)
        if (leftOctets == null || rightOctets == null) return left.compareTo(right)
        for (index in 0 until 4) {
            val delta = leftOctets[index] - rightOctets[index]
            if (delta != 0) return delta
        }
        return left.compareTo(right)
    }
}
